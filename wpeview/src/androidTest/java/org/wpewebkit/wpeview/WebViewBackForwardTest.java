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

import androidx.annotation.NonNull;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * WebViewClient.doUpdateVisitedHistory is called once for each navigation that is done, also for pages without a title
 * and for navigations within the page. WebView.canGoBack and WebView.canGoForward then follow the Back/Forward list.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewBackForwardTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    // The address and the Back/Forward state at each doUpdateVisitedHistory.
    private final BlockingQueue<String> history = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        webViewActivityTestRule.getScenario().onActivity(
            activity -> activity.getWebView().setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }

                @Override
                public void doUpdateVisitedHistory(@NonNull WebView view, @NonNull String url, boolean isReload) {
                    history.add(state(url, view.canGoBack(), view.canGoForward()));
                }
            }));
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

    private static String state(String url, boolean canGoBack, boolean canGoForward) {
        return url + (canGoBack ? " back" : "") + (canGoForward ? " forward" : "");
    }

    private String setPage(String path) { return setPage(path, "<p>No title</p>"); }

    private String setPage(String path, String html) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(html);
        return httpServer.setResponse(path, response);
    }

    private void onWebView(Consumer<WebView> action) {
        webViewActivityTestRule.getScenario().onActivity(activity -> action.accept(activity.getWebView()));
    }

    private void loadAndWait(String url) throws InterruptedException {
        onWebView(view -> view.loadUrl(url));
        waitForPageFinished(url);
    }

    // Going back or forward again while WebKit still loads the page drops that navigation.
    private void waitForPageFinished(String url) throws InterruptedException {
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
    }

    // The next doUpdateVisitedHistory must have this address and state, and the WebView must still have them.
    private void waitForHistory(String url, boolean canGoBack, boolean canGoForward) throws InterruptedException {
        String expected = state(url, canGoBack, canGoForward);
        assertEquals(expected, history.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        AtomicReference<String> current = new AtomicReference<>();
        onWebView(view -> current.set(state(view.getUrl(), view.canGoBack(), view.canGoForward())));
        assertEquals(expected, current.get());
    }

    /** Pages without a title, which used to update the state only when the title changed. */
    @Test
    public void testPagesWithoutTitle() throws Exception {
        String first = setPage("/first.html");
        String second = setPage("/second.html");

        loadAndWait(first);
        waitForHistory(first, false, false);
        loadAndWait(second);
        waitForHistory(second, true, false);
        onWebView(WebView::goBack);
        waitForPageFinished(first);
        waitForHistory(first, false, true);
        onWebView(WebView::goForward);
        waitForPageFinished(second);
        waitForHistory(second, true, false);
    }

    @Test
    public void testNavigationsWithinThePage() throws Exception {
        String page = setPage("/page.html");
        String pushed = page.replace("/page.html", "/pushed.html");

        loadAndWait(page);
        waitForHistory(page, false, false);
        onWebView(view -> view.evaluateJavascript("location.hash = 'part'", null));
        waitForHistory(page + "#part", true, false);
        onWebView(view -> view.evaluateJavascript("history.pushState(null, '', '/pushed.html')", null));
        waitForHistory(pushed, true, false);
        onWebView(WebView::goBack);
        waitForHistory(page + "#part", true, true);
        onWebView(WebView::goBack);
        waitForHistory(page, false, true);
        onWebView(view -> view.evaluateJavascript("history.forward()", null));
        waitForHistory(page + "#part", true, true);
        onWebView(view -> view.evaluateJavascript("history.replaceState(null, '', '/replaced.html')", null));
        waitForHistory(page.replace("/page.html", "/replaced.html"), true, true);
    }

    /**
     * Navigations that add a Back/Forward entry but keep the address of the page: a pushState to the same address, and
     * a navigation in a frame, which is reported with the address of the page.
     */
    @Test
    public void testNavigationsThatKeepTheAddress() throws Exception {
        String page = setPage("/page.html", "<iframe id=frame src='/first.html'></iframe>");
        setPage("/first.html");
        setPage("/second.html");

        loadAndWait(page);
        waitForHistory(page, false, false);
        onWebView(view -> view.evaluateJavascript("history.pushState(null, '', location.href)", null));
        waitForHistory(page, true, false);
        onWebView(view -> view.evaluateJavascript("history.back()", null));
        waitForHistory(page, false, true);
        onWebView(view -> view.evaluateJavascript("document.getElementById('frame').src = '/second.html'", null));
        waitForHistory(page, true, false);
    }

    /** A reload, or a load of the same address, commits a page, so it is reported, also with the same state. */
    @Test
    public void testReload() throws Exception {
        String page = setPage("/page.html");

        loadAndWait(page);
        waitForHistory(page, false, false);
        onWebView(WebView::reload);
        waitForPageFinished(page);
        waitForHistory(page, false, false);
        loadAndWait(page);
        waitForHistory(page, false, false);
    }

    /** A load that fails is reported once WebKit's error page for it commits, not when it starts. */
    @Test
    public void testFailedLoad() throws Exception {
        String page = setPage("/page.html");
        // Nothing listens on this port.
        String failing = "http://localhost:1/failing.html";

        loadAndWait(page);
        waitForHistory(page, false, false);
        onWebView(view -> view.loadUrl(failing));
        waitForHistory(failing, true, false);
    }
}
