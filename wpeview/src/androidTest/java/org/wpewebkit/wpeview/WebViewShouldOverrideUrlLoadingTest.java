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

import androidx.annotation.NonNull;
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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The app is asked about the navigations of the page, and can take them over, as with links to other apps. It is not
 * asked about its own loads.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewShouldOverrideUrlLoadingTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final Client client = new Client();

    /** Takes over the addresses that the WebView cannot load, and records what it is asked and what loads. */
    private static class Client extends TestWebViewClient {
        final BlockingQueue<WPEResourceRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<String> finishedPages = new LinkedBlockingQueue<>();
        final BlockingQueue<WPEResourceError> errors = new LinkedBlockingQueue<>();
        volatile boolean takeOver = true;

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WPEResourceRequest request) {
            requests.add(request);
            String scheme = request.getUrl().getScheme();
            return takeOver && !"http".equals(scheme) && !"about".equals(scheme);
        }

        @Override
        public void onPageFinished(@NonNull WebView view, @NonNull String url) {
            finishedPages.add(url);
            super.onPageFinished(view, url);
        }

        @Override
        public void onReceivedError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                    @NonNull WPEResourceError error) {
            errors.add(error);
        }
    }

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
    }

    @After
    public void tearDown() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private String serve(String path, String body) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(body);
        return httpServer.setResponse(path, response);
    }

    private ActivityScenario<WebViewTestActivity> load(String url) throws Exception {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> {
            activity.getWebView().setWebViewClient(client);
            activity.getWebView().loadUrl(url);
        });
        assertEquals(url, client.finishedPages.poll(30, TimeUnit.SECONDS));
        return scenario;
    }

    private void run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Exception {
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, script + "; true");
    }

    private WPEResourceRequest nextRequest() throws InterruptedException {
        WPEResourceRequest request = client.requests.poll(30, TimeUnit.SECONDS);
        assertNotNull("The app was not asked about the navigation", request);
        return request;
    }

    /** Goes to another page and waits for it, so that whatever happened before has happened. */
    private void loadNext(ActivityScenario<WebViewTestActivity> scenario) throws Exception {
        String next = serve("/next.html", "<p>Next");
        run(scenario, "location.href = '" + next + "'");
        assertEquals(next, nextRequest().getUrl().toString());
        assertEquals(next, client.finishedPages.poll(30, TimeUnit.SECONDS));
    }

    // Waits for the page to be painted, so that a tap reaches what it shows.
    private void waitForRendering(ActivityScenario<WebViewTestActivity> scenario) throws Exception {
        run(scenario,
            "rendered = false; requestAnimationFrame(() => requestAnimationFrame(() => { rendered = true; }))");
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "rendered", "true"));
    }

    private static void tapMiddle(ActivityScenario<WebViewTestActivity> scenario) {
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            float x = view.getWidth() / 2f;
            float y = view.getHeight() / 2f;
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        });
    }

    // A link over the whole page, so that a tap in the middle of the view follows it.
    private static String pageWithLink(String href, String target) {
        return "<html><head><meta name=viewport content='width=device-width'></head><body style='margin:0'>"
            + "<a href='" + href + "' target='" + target + "' style='position:fixed;top:0;left:0;width:100%;"
            + "height:100%;display:block'>Link</a></body></html>";
    }

    @Test
    public void testScriptNavigationIsTakenWithoutGesture() throws Exception {
        // The page changes its address by itself. A script the app runs would count as a user gesture.
        String page = serve("/page.html", "<p>Page<script>addEventListener('load', () => "
                                              + "setTimeout(() => location.href = 'mailto:someone@example.com', 500))"
                                              + "</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        WPEResourceRequest request = nextRequest();
        assertEquals("mailto:someone@example.com", request.getUrl().toString());
        assertEquals("GET", request.getMethod());
        assertTrue(request.isForMainFrame());
        assertFalse(request.hasGesture());
        assertFalse(request.isRedirect());

        // The navigation taken over does not load, so the page stays and nothing fails.
        loadNext(scenario);
        assertNull(client.errors.poll());
        assertNull("A navigation taken over loaded", client.finishedPages.poll());
    }

    @Test
    public void testTappedLinkHasGesture() throws Exception {
        String page = serve("/link.html", pageWithLink("tel:+1234", "_self"));
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        waitForRendering(scenario);
        tapMiddle(scenario);

        WPEResourceRequest request = nextRequest();
        assertEquals("tel:+1234", request.getUrl().toString());
        assertTrue(request.isForMainFrame());
        assertTrue(request.hasGesture());
        assertFalse(request.isRedirect());
        scenario.onActivity(activity -> assertEquals(page, activity.getWebView().getUrl()));
    }

    @Test
    public void testLinkToNewWindowIsAskedBeforeTheWindow() throws Exception {
        String page = serve("/blank.html", pageWithLink("mailto:someone@example.com", "_blank"));
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        waitForRendering(scenario);
        tapMiddle(scenario);

        WPEResourceRequest request = nextRequest();
        assertEquals("mailto:someone@example.com", request.getUrl().toString());
        assertTrue(request.isForMainFrame());
        assertTrue(request.hasGesture());
    }

    @Test
    public void testNavigationNotTakenFailsAsUnsupportedScheme() throws Exception {
        client.takeOver = false;
        String page = serve("/page.html", "<p>Page");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = 'unknown-scheme:thing'");
        assertEquals("unknown-scheme:thing", nextRequest().getUrl().toString());
        WPEResourceError error = client.errors.poll(30, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals(WebViewClient.ERROR_UNSUPPORTED_SCHEME, error.getErrorCode());
    }

    @Test
    public void testFrameNavigationIsNotForMainFrame() throws Exception {
        String frame = serve("/frame.html", "<p>Frame");
        String page = serve("/frames.html", "<iframe src='" + frame + "'></iframe>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        // The frame's own load, which the page started.
        WPEResourceRequest frameLoad = nextRequest();
        assertEquals(frame, frameLoad.getUrl().toString());
        assertFalse(frameLoad.isForMainFrame());

        run(scenario, "frames[0].location.href = 'sms:+1234'");
        WPEResourceRequest request = nextRequest();
        assertEquals("sms:+1234", request.getUrl().toString());
        assertFalse(request.isForMainFrame());
    }

    @Test
    public void testServerRedirectIsAsked() throws Exception {
        HttpResponse redirect = new HttpResponse();
        redirect.setStatusCode(302);
        redirect.setStatusMessage("Found");
        redirect.addHeader("Location", "geo:0,0");
        String redirecting = httpServer.setResponse("/redirect", redirect);
        String page = serve("/page.html", "<p>Page");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + redirecting + "'");
        WPEResourceRequest first = nextRequest();
        assertEquals(redirecting, first.getUrl().toString());
        assertFalse(first.isRedirect());
        WPEResourceRequest request = nextRequest();
        assertEquals("geo:0,0", request.getUrl().toString());
        assertTrue(request.isRedirect());
        assertTrue(request.isForMainFrame());
    }

    @Test
    public void testAppNavigationsAreNotAsked() throws Exception {
        String first = serve("/first.html", "<p>First");
        String second = serve("/second.html", "<p>Second");
        ActivityScenario<WebViewTestActivity> scenario = load(first);
        scenario.onActivity(activity -> activity.getWebView().loadUrl(second));
        assertEquals(second, client.finishedPages.poll(30, TimeUnit.SECONDS));
        scenario.onActivity(activity -> activity.getWebView().goBack());
        assertEquals(first, client.finishedPages.poll(30, TimeUnit.SECONDS));
        scenario.onActivity(activity -> activity.getWebView().reload());
        assertEquals(first, client.finishedPages.poll(30, TimeUnit.SECONDS));
        scenario.onActivity(activity -> activity.getWebView().loadHtml("<p>HTML", null));
        assertEquals("about:blank", client.finishedPages.poll(30, TimeUnit.SECONDS));
        assertNull("The app was asked about its own navigation", client.requests.poll());
    }

    @Test
    public void testPostIsNotAsked() throws Exception {
        String result = serve("/post", "<p>Posted");
        String page = serve("/form.html", "<form method=post action='" + result + "'><input name=a value=b></form>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "document.forms[0].submit()");
        assertEquals(result, client.finishedPages.poll(30, TimeUnit.SECONDS));
        assertNull("The app was asked about a POST request", client.requests.poll());
    }
}
