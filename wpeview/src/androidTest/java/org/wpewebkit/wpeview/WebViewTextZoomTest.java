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

import android.content.Context;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * WebSettings.setTextZoom: the text of the page grows or shrinks, and the width of the page and what it sizes in pixels
 * stay. The zoom stays for the pages loaded after, also on another site, in a new web process and in a restored state.
 * Until the app sets one, the text zoom is the font size of the device. The tests start at font size 1.0.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewTextZoomTest {
    private static final long TIMEOUT_SECONDS = 30;

    // The same text twice: WebKit's text zoom leaves out the text that has -webkit-text-zoom: reset, so the width of
    // the first one divided by the width of the second is the text zoom of the page.
    private static final String PAGE = "<meta name='viewport' content='width=device-width'>"
                                       + "<p style='font: 16px monospace; white-space: pre'>"
                                       + "<span id='zoomed'>MMMMMMMMMMMMMMMMMMMM</span></p>"
                                       + "<p style='font: 16px monospace; white-space: pre; -webkit-text-zoom: reset'>"
                                       + "<span id='reference'>MMMMMMMMMMMMMMMMMMMM</span></p>"
                                       + "<div id='box' style='width: 100px; height: 10px'></div>";
    private static final String PAGE_TEXT_ZOOM = "zoomed.getBoundingClientRect().width"
                                                 + " / reference.getBoundingClientRect().width";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    // The font size of the device before the test, "null" if it was never set.
    private String deviceFontScale;
    // The view of the test, which a new one replaces to restore a state.
    private WebView webView;
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> terminations = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        deviceFontScale = WebViewActivityScenarioHelper.shell("settings get system font_scale").trim();
        setFontScale(1);
        httpServer = HttpServer.start();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            webView = activity.getWebView();
            listen(webView);
        });
    }

    // The device goes back to its font size, so that the tests after this one do not run with another one.
    @After
    public void tearDown() throws Exception {
        onWebView(view -> {
            if (view.getInternalWebKitWebView() != null)
                view.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
        if (deviceFontScale == null)
            return;
        if (deviceFontScale.equals("null")) {
            WebViewActivityScenarioHelper.shell("settings delete system font_scale");
            waitForFontScale(1);
        } else {
            setFontScale(Float.parseFloat(deviceFontScale));
        }
    }

    // Changes the font size of the device, and waits for the app to have it. The test activity stays as it is.
    private void setFontScale(float scale) throws Exception {
        WebViewActivityScenarioHelper.shell("settings put system font_scale " + scale);
        waitForFontScale(scale);
    }

    // Read on the main thread, where the views that follow the font size get it in the same task.
    private void waitForFontScale(float scale) throws InterruptedException {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        AtomicReference<Float> current = new AtomicReference<>();
        for (int i = 0; i < 100; i++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> current.set(app.getResources().getConfiguration().fontScale));
            if (current.get() == scale)
                return;
            Thread.sleep(100);
        }
        fail("The app has font size " + current.get() + ", not " + scale);
    }

    private int textZoom() { return fromWebView(view -> view.getSettings().getTextZoom()); }

    private void listen(WebView view) {
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                finishedUrls.add(url);
            }

            @Override
            public void onWebProcessTerminated(@NonNull WebView view, int reason) {
                terminations.add(reason);
            }
        });
    }

    // Replaces the view with a new one, in its own context, as a view made after the app started again.
    private void replaceWebView() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            ViewGroup parent = (ViewGroup)webView.getParent();
            WebView newView = new WebView(activity);
            newView.setLayoutParams(webView.getLayoutParams());
            listen(newView);
            parent.removeView(webView);
            webView.destroy();
            webView = newView;
            parent.addView(newView);
        });
        finishedUrls.clear();
    }

    private String setPage(String path) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(PAGE);
        return httpServer.setResponse(path, response);
    }

    private void onWebView(Consumer<WebView> action) {
        webViewActivityTestRule.getScenario().onActivity(activity -> action.accept(webView));
    }

    private <T> T fromWebView(Function<WebView, T> getter) {
        AtomicReference<T> value = new AtomicReference<>();
        onWebView(view -> value.set(getter.apply(view)));
        return value.get();
    }

    private void waitForPageFinished(String url) throws InterruptedException {
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
    }

    private void loadAndWait(String url) throws InterruptedException {
        onWebView(view -> view.loadUrl(url));
        waitForPageFinished(url);
    }

    private double evaluate(String script) throws InterruptedException {
        BlockingQueue<String> results = new LinkedBlockingQueue<>();
        onWebView(view -> view.evaluateJavascript(script, results::add));
        String result = results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No result for " + script, result);
        return Double.parseDouble(result);
    }

    // The page gets a new text zoom a moment after the view does. Glyph widths are rounded, so the measure is close
    // to the text zoom, not equal.
    private void waitForPageTextZoom(double expected) throws InterruptedException {
        double textZoom = 0;
        for (int i = 0; i < 100; i++) {
            textZoom = evaluate(PAGE_TEXT_ZOOM);
            if (Math.abs(textZoom - expected) < 0.02)
                return;
            Thread.sleep(100);
        }
        fail("The text of the page is zoomed by " + textZoom + ", not " + expected);
    }

    @Test
    public void testTextZoomScalesOnlyTheText() throws Exception {
        String page = setPage("/page.html");

        loadAndWait(page);
        waitForPageTextZoom(1);
        double width = evaluate("innerWidth");

        onWebView(view -> view.getSettings().setTextZoom(150));
        assertEquals(150, textZoom());
        waitForPageTextZoom(1.5);
        assertEquals("The page got another width", width, evaluate("innerWidth"), 0);
        assertEquals("A box sized in pixels changed", 100, evaluate("box.getBoundingClientRect().width"), 0);

        onWebView(view -> view.getSettings().setTextZoom(100));
        waitForPageTextZoom(1);
    }

    @Test
    public void testTextZoomStaysForLaterPages() throws Exception {
        String page = setPage("/page.html");
        String otherSite = page.replace("://localhost:", "://127.0.0.1:");

        onWebView(view -> view.getSettings().setTextZoom(130));
        loadAndWait(page);
        waitForPageTextZoom(1.3);

        loadAndWait(otherSite);
        waitForPageTextZoom(1.3);

        onWebView(WebView::terminateWebProcess);
        assertNotNull("The web process did not end", terminations.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        onWebView(WebView::reload);
        waitForPageFinished(otherSite);
        waitForPageTextZoom(1.3);
    }

    /**
     * Until the app sets a text zoom, it is the font size of the device times 100, and changes with it in the views
     * that are there, also out of a window. A view made after the change starts with it, and a destroyed one keeps the
     * one it had.
     */
    @Test
    public void testTextZoomFollowsTheFontSize() throws Exception {
        String page = setPage("/page.html");
        assertEquals(100, textZoom());
        loadAndWait(page);
        waitForPageTextZoom(1);
        AtomicReference<WebView> outOfWindow = new AtomicReference<>();
        AtomicReference<WebSettings> destroyed = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            outOfWindow.set(new WebView(activity));
            WebView view = new WebView(activity);
            destroyed.set(view.getSettings());
            view.destroy();
        });

        setFontScale(1.3f);
        assertEquals(130, textZoom());
        waitForPageTextZoom(1.3);
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            assertEquals(130, outOfWindow.get().getSettings().getTextZoom());
            outOfWindow.get().destroy();
            assertEquals(100, destroyed.get().getTextZoom());
            WebView newView = new WebView(activity);
            assertEquals(130, newView.getSettings().getTextZoom());
            newView.destroy();
        });
    }

    /**
     * A text zoom that the app sets stays when the font size of the device changes, also when it is the one the device
     * had.
     */
    @Test
    public void testTextZoomOfTheAppStays() throws Exception {
        String page = setPage("/page.html");
        onWebView(view -> view.getSettings().setTextZoom(100));
        loadAndWait(page);
        waitForPageTextZoom(1);

        setFontScale(1.3f);
        assertEquals(100, textZoom());
        // Long enough for a new zoom to reach the page.
        Thread.sleep(500);
        waitForPageTextZoom(1);
    }

    /** A new view that gets the history of another one shows the restored page with its own text zoom. */
    @Test
    public void testTextZoomOfARestoredPage() throws Exception {
        String page = setPage("/page.html");
        loadAndWait(page);
        byte[] state = fromWebView(WebView::saveState);
        assertNotNull(state);

        replaceWebView();
        onWebView(view -> view.getSettings().setTextZoom(130));
        assertTrue(fromWebView(view -> view.restoreState(state)));
        waitForPageFinished(page);
        waitForPageTextZoom(1.3);
    }

    @Test
    public void testTextZoomMustBeMoreThanZero() {
        onWebView(view -> {
            WebSettings settings = view.getSettings();
            assertThrows(IllegalArgumentException.class, () -> settings.setTextZoom(0));
            assertThrows(IllegalArgumentException.class, () -> settings.setTextZoom(-50));
            assertEquals(100, settings.getTextZoom());
        });
    }

    /** The settings of a destroyed WebView keep their text zoom, and can still change it. */
    @Test
    public void testTextZoomOfADestroyedWebView() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            WebView webView = new WebView(activity);
            WebSettings settings = webView.getSettings();
            settings.setTextZoom(150);
            webView.destroy();

            assertEquals(150, settings.getTextZoom());
            settings.setTextZoom(120);
            assertEquals(120, settings.getTextZoom());
        });
    }
}
