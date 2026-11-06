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

import android.view.ViewGroup;

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
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * WebView.saveState and WebView.restoreState: a new view gets the Back/Forward list of another one, as when an app
 * brings its pages back after Android ended it, and loads its current page.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewSaveStateTest {
    private static final long TIMEOUT_SECONDS = 30;
    // Long enough for a load that should not start to start.
    private static final long QUIET_MILLISECONDS = 1000;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    // The view of the test, which a new one replaces to restore a state.
    private WebView webView;
    private final BlockingQueue<String> startedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            webView = activity.getWebView();
            listen(webView);
        });
    }

    @After
    public void tearDown() {
        onWebView(view -> {
            if (view.getInternalWebKitWebView() != null)
                view.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private void listen(WebView view) {
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(@NonNull WebView view, @NonNull String url) {
                startedUrls.add(url);
            }

            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                finishedUrls.add(url);
            }
        });
    }

    private String setPage(String path) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody("<title>" + path + "</title><p>" + path + "</p>");
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

    private void loadAndWait(String url) throws InterruptedException {
        onWebView(view -> view.loadUrl(url));
        waitForPageFinished(url);
    }

    private void waitForPageFinished(String url) throws InterruptedException {
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
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
        startedUrls.clear();
        finishedUrls.clear();
    }

    private void assertNavigation(boolean canGoBack, boolean canGoForward) {
        assertEquals(canGoBack, fromWebView(WebView::canGoBack));
        assertEquals(canGoForward, fromWebView(WebView::canGoForward));
    }

    @Test
    public void testRestoreHistory() throws Exception {
        String first = setPage("/first.html");
        String second = setPage("/second.html");
        String third = setPage("/third.html");

        loadAndWait(first);
        loadAndWait(second);
        loadAndWait(third);
        byte[] state = fromWebView(WebView::saveState);
        assertNotNull(state);

        replaceWebView();
        assertTrue(fromWebView(view -> view.restoreState(state)));
        // Known at once, before the page loads.
        assertNavigation(true, false);
        waitForPageFinished(third);
        assertEquals(third, fromWebView(WebView::getUrl));

        onWebView(WebView::goBack);
        waitForPageFinished(second);
        assertNavigation(true, true);
        onWebView(WebView::goBack);
        waitForPageFinished(first);
        assertNavigation(false, true);
    }

    /** The current page may be any page of the list, with pages to go forward to. */
    @Test
    public void testRestoreForwardHistory() throws Exception {
        String first = setPage("/first.html");
        String second = setPage("/second.html");

        loadAndWait(first);
        loadAndWait(second);
        onWebView(WebView::goBack);
        waitForPageFinished(first);
        byte[] state = fromWebView(WebView::saveState);

        replaceWebView();
        assertTrue(fromWebView(view -> view.restoreState(state)));
        assertNavigation(false, true);
        waitForPageFinished(first);
        onWebView(WebView::goForward);
        waitForPageFinished(second);
        assertNavigation(true, false);
    }

    /** A state that WebKit cannot read, such as one saved by a newer version, restores nothing and loads nothing. */
    @Test
    public void testUnreadableState() throws Exception {
        replaceWebView();
        assertFalse(fromWebView(view -> view.restoreState(new byte[] {1, 2, 3})));
        assertNull(startedUrls.poll(QUIET_MILLISECONDS, TimeUnit.MILLISECONDS));
        assertNavigation(false, false);
    }

    /** A view that never loaded a page has no page to restore. */
    @Test
    public void testStateWithoutPages() throws Exception {
        byte[] state = fromWebView(WebView::saveState);
        assertNotNull(state);

        replaceWebView();
        assertFalse(fromWebView(view -> view.restoreState(state)));
        assertNull(startedUrls.poll(QUIET_MILLISECONDS, TimeUnit.MILLISECONDS));
    }

    /** Nor does it into a view that already has a page, which keeps its own history and does not load again. */
    @Test
    public void testStateWithoutPagesIntoAViewWithAPage() throws Exception {
        byte[] state = fromWebView(WebView::saveState);
        String page = setPage("/page.html");
        loadAndWait(page);
        startedUrls.clear();

        assertFalse(fromWebView(view -> view.restoreState(state)));
        assertNull(startedUrls.poll(QUIET_MILLISECONDS, TimeUnit.MILLISECONDS));
        assertEquals(page, fromWebView(WebView::getUrl));
    }

    @Test
    public void testDestroyedView() throws Exception {
        loadAndWait(setPage("/page.html"));
        byte[] state = fromWebView(WebView::saveState);
        onWebView(WebView::destroy);
        assertNull(fromWebView(WebView::saveState));
        assertFalse(fromWebView(view -> view.restoreState(state)));
    }
}
