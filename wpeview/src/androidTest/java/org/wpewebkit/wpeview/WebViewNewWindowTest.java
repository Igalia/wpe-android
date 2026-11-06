/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpeview;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The windows that pages open, with window.open() or a link to a new window. The app makes the WebView of each in
 * WebChromeClient.onCreateWindow, as a browser makes a tab, and WebKit loads the page into it. The page can close it
 * again, which calls onCloseWindow. Without a user gesture, or when the app opens none, no window opens.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewNewWindowTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    // Used on the main thread only, like the windows.
    private WebViewTestActivity activity;
    private String openerUrl;
    private String windowUrl;
    private final TestWebViewClient openerClient = new TestWebViewClient();
    // Whether each call to onCreateWindow came from a user gesture.
    private final BlockingQueue<Boolean> createWindowCalls = new LinkedBlockingQueue<>();
    // The windows made, used on the main thread only, and the addresses they finished loading.
    private final List<WebView> windows = new ArrayList<>();
    private final BlockingQueue<String> windowFinishedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<WebView> closedWindows = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        webViewActivityTestRule.getScenario().onActivity(activity -> this.activity = activity);
        windowUrl = setPage("/window.html", "<html><head><title>Window</title></head><body>Window</body></html>");
        // The link covers the whole page, so that a tap in the middle of the view follows it.
        openerUrl = setPage("/opener.html", "<html><head><title>Opener</title>"
                                                + "<meta name=viewport content='width=device-width'></head>"
                                                + "<body style='margin:0'><a href='/window.html' target=_blank "
                                                + "style='display:block;height:100vh'>Link</a><script>"
                                                + "var rendered = false; var clicked = false;"
                                                + "addEventListener('click', () => clicked = true);"
                                                + "requestAnimationFrame(() => requestAnimationFrame(() => {"
                                                + "  rendered = true; }));"
                                                + "</script></body></html>");
    }

    @After
    public void tearDown() {
        // The activity destroys its own WebView. The windows share the WebContext that WebView made for itself, which
        // goes with the last of them, and with it the network and web processes of the test.
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            for (WebView window : windows) {
                if (window.getParent() != null)
                    ((ViewGroup)window.getParent()).removeView(window);
                window.destroy();
            }
            windows.clear();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private String setPage(String path, String html) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(html);
        return httpServer.setResponse(path, response);
    }

    /**
     * Opens each window in a WebView over the activity's one, as a browser shows a new tab, and closes it as a browser
     * closes a tab, while WebKit asks for it. The pages in the windows can open windows too.
     */
    private class WindowOpener extends WebChromeClient {
        @Override
        public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
            createWindowCalls.add(isUserGesture);
            return makeWindow(view);
        }

        @Override
        public void onCloseWindow(@NonNull WebView closed) {
            if (!windows.remove(closed))
                return;
            closedWindows.add(closed);
            ((ViewGroup)closed.getParent()).removeView(closed);
            closed.destroy();
        }
    }

    // Makes the WebView of a window that the page in opener asks for, which reports the pages it finishes loading.
    private WebView makeWindow(@NonNull WebView opener) {
        WebView window = new WebView(activity, opener);
        windows.add(window);
        window.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                windowFinishedUrls.add(url);
            }
        });
        window.setWebChromeClient(new WindowOpener());
        activity.addContentView(window, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                   ViewGroup.LayoutParams.MATCH_PARENT));
        return window;
    }

    private ActivityScenario<WebViewTestActivity> loadOpener(@Nullable WebChromeClient chromeClient) throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        if (chromeClient != null)
            scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(chromeClient));
        WebViewActivityScenarioHelper.loadUrlSync(scenario, openerClient, openerUrl);
        return scenario;
    }

    private String run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, openerClient, script);
    }

    private WebView waitForWindow(String url) throws InterruptedException {
        assertEquals("The window did not load its page", url,
                     windowFinishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        AtomicReference<WebView> window = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            assertEquals(1, windows.size());
            window.set(windows.get(0));
        });
        return window.get();
    }

    private void tapTheMiddle(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        assertEquals("The page was not rendered", "true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "rendered", "true"));
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            float x = view.getWidth() / 2f;
            float y = view.getHeight() / 2f;
            long downTime = SystemClock.uptimeMillis();
            for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                long eventTime = action == MotionEvent.ACTION_DOWN ? downTime : downTime + 50;
                MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
                view.dispatchTouchEvent(event);
                event.recycle();
            }
        });
    }

    /** window.open() opens a window, whose page and its opener can reach each other. */
    @Test
    public void testWindowOpen() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener());

        // A script that the app runs counts as a user gesture.
        assertEquals("true", run(scenario, "opened = window.open('/window.html'); opened !== null"));
        assertEquals(Boolean.TRUE, createWindowCalls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        WebView window = waitForWindow(windowUrl);

        Function<WebViewTestActivity, WebView> inWindow = activity -> window;
        assertEquals("\"Opener\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                       scenario, inWindow, "window.opener.document.title", "\"Opener\""));
        assertEquals("\"Window\"", run(scenario, "opened.document.title"));
        scenario.onActivity(activity -> assertEquals(windowUrl, window.getUrl()));
    }

    /**
     * window.close() in a window that a page opened calls onCloseWindow with its WebView, which can be destroyed right
     * away. The page that opened it sees it closed.
     */
    @Test
    public void testWindowClose() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener());
        assertEquals("true", run(scenario, "opened = window.open('/window.html'); opened !== null"));
        WebView window = waitForWindow(windowUrl);

        Function<WebViewTestActivity, WebView> inWindow = activity -> window;
        WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inWindow, "setTimeout(() => window.close()); 0",
                                                              "0");
        assertSame(window, closedWindows.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "opened.closed", "true"));
    }

    /** The page that opened a window can close it too. */
    @Test
    public void testOpenerClosesTheWindow() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener());
        assertEquals("true", run(scenario, "opened = window.open('/window.html'); opened !== null"));
        WebView window = waitForWindow(windowUrl);

        run(scenario, "opened.close(); 0");
        assertSame(window, closedWindows.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "opened.closed", "true"));
    }

    /** A tap on a link with target=_blank opens its page in a window, without an opener. */
    @Test
    public void testLinkToANewWindow() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener());

        tapTheMiddle(scenario);
        assertEquals(Boolean.TRUE, createWindowCalls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        WebView window = waitForWindow(windowUrl);

        Function<WebViewTestActivity, WebView> inWindow = activity -> window;
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inWindow,
                                                                                   "window.opener === null", "true"));
        scenario.onActivity(activity -> assertEquals(openerUrl, activity.getWebView().getUrl()));
    }

    /** Without a chrome client that opens windows, window.open() returns null and the link does nothing. */
    @Test
    public void testNoWindowByDefault() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(null);

        assertEquals("true", run(scenario, "window.open('/window.html') === null"));
        // WebKit asks for the window while the click is handled, so it is done once the page has the click.
        tapTheMiddle(scenario);
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "clicked", "true"));
        scenario.onActivity(activity -> assertEquals(openerUrl, activity.getWebView().getUrl()));
        assertEquals("\"Opener\"", run(scenario, "document.title"));
    }

    /** When the app opens no window, window.open() returns null. */
    @Test
    public void testWindowDeclined() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WebChromeClient() {
            @Override
            public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
                createWindowCalls.add(isUserGesture);
                return null;
            }
        });

        assertEquals("true", run(scenario, "window.open('/window.html') === null"));
        assertEquals(Boolean.TRUE, createWindowCalls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** A page cannot open a window by itself, without a user gesture: the app is not even asked. */
    @Test
    public void testNoWindowWithoutUserGesture() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(new WindowOpener()));
        String url = setPage("/popup.html", "<html><body><script>"
                                                + "var opened = window.open('/window.html') !== null;"
                                                + "</script></body></html>");
        WebViewActivityScenarioHelper.loadUrlSync(scenario, openerClient, url);

        // WebKit refuses the window as window.open() runs.
        assertEquals("false", run(scenario, "opened"));
        assertTrue(createWindowCalls.isEmpty());
    }

    /**
     * A window outlives the WebView whose page opened it, also when that WebView made its WebContext for itself, as the
     * activity's does: the window shares it.
     */
    @Test
    public void testWindowOutlivesItsOpener() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener());
        assertEquals("true", run(scenario, "window.open('/window.html') !== null"));
        WebView window = waitForWindow(windowUrl);

        scenario.onActivity(activity -> activity.getWebView().destroy());
        String otherUrl = setPage("/other.html", "<html><head><title>Other</title></head><body>Other</body></html>");
        scenario.onActivity(activity -> {
            window.getCookieManager().setAcceptCookie(true);
            window.loadUrl(otherUrl);
        });
        assertEquals("The window did not load another page", otherUrl,
                     windowFinishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Function<WebViewTestActivity, WebView> inWindow = activity -> window;
        assertEquals("\"Other\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inWindow,
                                                                                        "document.title", "\"Other\""));
    }

    /** The WebView of a window can only be made in onCreateWindow, which must return it. */
    @Test
    public void testWindowMadeElsewhere() throws Throwable {
        AtomicReference<WebView> other = new AtomicReference<>();
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WebChromeClient() {
            @Override
            public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
                createWindowCalls.add(isUserGesture);
                return other.get();
            }
        });
        try {
            scenario.onActivity(activity -> {
                AtomicReference<RuntimeException> error = new AtomicReference<>();
                try {
                    new WebView(activity, activity.getWebView());
                } catch (IllegalStateException exception) {
                    error.set(exception);
                }
                assertNotNull("A window was made outside onCreateWindow", error.get());
                other.set(new WebView(activity, WebViewActivityScenarioHelper.sharedWebContext(activity)));
            });

            assertEquals("true", run(scenario, "window.open('/window.html') === null"));
            assertEquals(Boolean.TRUE, createWindowCalls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            scenario.onActivity(activity -> {
                if (other.get() != null)
                    other.get().destroy();
            });
        }
        assertTrue(windowFinishedUrls.isEmpty());
    }

    /** A WebView made for a window but not returned opens no window. It stays the app's, to use or destroy. */
    @Test
    public void testWindowMadeButNotReturned() throws Throwable {
        AtomicReference<WebView> made = new AtomicReference<>();
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WebChromeClient() {
            @Override
            public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
                createWindowCalls.add(isUserGesture);
                made.set(makeWindow(view));
                return null;
            }
        });

        assertEquals("true", run(scenario, "window.open('/window.html') === null"));
        assertEquals(Boolean.TRUE, createWindowCalls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        String otherUrl = setPage("/other.html", "<html><head><title>Other</title></head><body>Other</body></html>");
        scenario.onActivity(activity -> made.get().loadUrl(otherUrl));
        assertEquals("The WebView made for the window did not load another page", otherUrl,
                     windowFinishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** The WebView of a window can be made only once in each call, and the first one opens. */
    @Test
    public void testOneWindowInEachCall() throws Throwable {
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener() {
            @Override
            public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
                WebView window = super.onCreateWindow(view, isUserGesture);
                try {
                    new WebView(activity, view);
                } catch (IllegalStateException exception) {
                    error.set(exception);
                }
                return window;
            }
        });

        assertEquals("true", run(scenario, "window.open('/window.html') !== null"));
        WebView window = waitForWindow(windowUrl);
        assertNotNull("A second window was made in the same call", error.get());
        Function<WebViewTestActivity, WebView> inWindow = activity -> window;
        assertEquals("\"Opener\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                       scenario, inWindow, "window.opener.document.title", "\"Opener\""));
    }

    /**
     * The page in a window can open a window too. They all share the WebContext that the first WebView made for
     * itself, which stays while any of them is left.
     */
    @Test
    public void testWindowOfAWindow() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener());
        assertEquals("true", run(scenario, "window.open('/window.html') !== null"));
        WebView second = waitForWindow(windowUrl);

        String thirdUrl = setPage("/third.html", "<html><head><title>Third</title></head><body>Third</body></html>");
        Function<WebViewTestActivity, WebView> inSecond = activity -> second;
        // The script returns at once, so that it runs only once.
        WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inSecond,
                                                              "opened = window.open('/third.html'); 0", "0");
        assertEquals("The third window did not load its page", thirdUrl,
                     windowFinishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inSecond,
                                                                                   "opened !== null", "true"));
        AtomicReference<WebView> third = new AtomicReference<>();
        scenario.onActivity(activity -> {
            assertEquals(2, windows.size());
            third.set(windows.get(1));
        });
        Function<WebViewTestActivity, WebView> inThird = activity -> third.get();
        assertEquals("\"Window\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                       scenario, inThird, "window.opener.document.title", "\"Window\""));

        // The second goes before the first, whose WebContext the third then has alone.
        scenario.onActivity(activity -> {
            windows.remove(second);
            ((ViewGroup)second.getParent()).removeView(second);
            second.destroy();
            activity.getWebView().destroy();
        });
        String otherUrl = setPage("/other.html", "<html><head><title>Other</title></head><body>Other</body></html>");
        scenario.onActivity(activity -> {
            third.get().getCookieManager().setAcceptCookie(true);
            third.get().loadUrl(otherUrl);
        });
        assertEquals("The third window did not load another page", otherUrl,
                     windowFinishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("\"Other\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inThird,
                                                                                        "document.title", "\"Other\""));
    }

    /** The app can destroy the WebView whose page asks for a window while it opens the window, which still opens. */
    @Test
    public void testOpenerDestroyedWhileItOpensAWindow() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadOpener(new WindowOpener() {
            @Override
            public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
                WebView window = super.onCreateWindow(view, isUserGesture);
                view.destroy();
                return window;
            }
        });

        // A tap, as a script that the app runs would keep the WebView of the opener alive until its result comes.
        tapTheMiddle(scenario);
        WebView window = waitForWindow(windowUrl);
        Function<WebViewTestActivity, WebView> inWindow = activity -> window;
        assertEquals("\"Window\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                       scenario, inWindow, "document.title", "\"Window\""));
    }
}
