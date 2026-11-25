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

import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * WebView.loadAlternateHtml: the content shows in place of a page, which keeps its address and comes back with a
 * reload. In onReceivedError, the content takes the entry of the page that failed in the Back/Forward list; at other
 * times, it takes a new one.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewAlternateHtmlTest {
    private static final long TIMEOUT_SECONDS = 30;
    private static final String ERROR_PAGE = "<title>Error</title><p>Error page</p>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final BlockingQueue<String> startedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> titles = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> terminations = new LinkedBlockingQueue<>();
    // Whether the client shows the error page in onReceivedError.
    private volatile boolean showErrorPageOnFailure;
    // The addresses that the app was asked about.
    private final BlockingQueue<String> overrideRequests = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        onWebView(view -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WPEResourceRequest request) {
                    overrideRequests.add(request.getUrl().toString());
                    return false;
                }

                @Override
                public void onPageStarted(@NonNull WebView view, @NonNull String url) {
                    startedUrls.add(url);
                }

                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }

                @Override
                public void onReceivedError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                            @NonNull WPEResourceError error) {
                    if (showErrorPageOnFailure)
                        view.loadAlternateHtml(ERROR_PAGE, request.getUrl().toString(), null);
                }

                @Override
                public void onWebProcessTerminated(@NonNull WebView view, int reason) {
                    terminations.add(reason);
                }
            });
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
                    titles.add(title);
                }
            });
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

    private String setPage(String path, String text) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody("<title>" + text + "</title><p>" + text + "</p>");
        return httpServer.setResponse(path, response);
    }

    private void onWebView(Consumer<WebView> action) {
        webViewActivityTestRule.getScenario().onActivity(activity -> action.accept(activity.getWebView()));
    }

    private <T> T fromWebView(Function<WebView, T> getter) {
        AtomicReference<T> value = new AtomicReference<>();
        onWebView(view -> value.set(getter.apply(view)));
        return value.get();
    }

    private void waitFor(BlockingQueue<String> queue, String expected, String what) throws InterruptedException {
        String value;
        do {
            value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("No " + what + " " + expected, value);
        } while (!value.equals(expected));
    }

    private void waitForPageFinished(String url) throws InterruptedException {
        waitFor(finishedUrls, url, "end of the load of");
    }

    private void loadAndWait(String url) throws InterruptedException {
        onWebView(view -> view.loadUrl(url));
        waitForPageFinished(url);
    }

    private String evaluate(String script) throws InterruptedException {
        BlockingQueue<String> results = new LinkedBlockingQueue<>();
        onWebView(view -> view.evaluateJavascript(script, results::add));
        String result = results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No result for " + script, result);
        return result;
    }

    // Shows the error page in place of the page at url, and waits until it is shown.
    private void loadErrorPage(String url) throws InterruptedException {
        startedUrls.clear();
        overrideRequests.clear();
        titles.clear();
        onWebView(view -> view.loadAlternateHtml(ERROR_PAGE, url, null));
        waitForPageFinished(url);
        assertEquals(url, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        waitForErrorPage();
        assertTrue("The app was asked about its own load: " + overrideRequests, overrideRequests.isEmpty());
    }

    private void waitForErrorPage() throws InterruptedException {
        waitFor(titles, "Error", "title");
        assertEquals("\"Error page\"", evaluate("document.body.textContent"));
    }

    // A local port where nothing listens, so that connections to it are refused.
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The view keeps the address of the page, and its document has the base address. */
    @Test
    public void testKeepsAddress() throws Exception {
        String page = setPage("/page.html", "Page");

        loadAndWait(page);
        loadErrorPage(page);
        assertEquals(page, fromWebView(WebView::getUrl));
        assertEquals("Error", fromWebView(WebView::getTitle));
        assertEquals("\"about:blank\"", evaluate("location.href"));
    }

    /** A reload loads the page again in the entry of the content. */
    @Test
    public void testReload() throws Exception {
        String first = setPage("/first.html", "First");
        String second = setPage("/second.html", "Second");

        loadAndWait(first);
        loadAndWait(second);
        loadErrorPage(second);
        onWebView(WebView::reload);
        waitForPageFinished(second);
        waitFor(titles, "Second", "title");
        assertEquals("\"Second\"", evaluate("document.body.textContent"));
        assertEquals(false, fromWebView(WebView::canGoForward));
    }

    /** Over a page that loaded, the content takes a new entry, and going back finds the page it replaced. */
    @Test
    public void testNewEntryOverPage() throws Exception {
        String first = setPage("/first.html", "First");
        String second = setPage("/second.html", "Second");

        loadAndWait(first);
        loadAndWait(second);
        loadErrorPage(second);
        assertEquals(true, fromWebView(WebView::canGoBack));
        assertEquals(false, fromWebView(WebView::canGoForward));
        onWebView(WebView::goBack);
        waitForPageFinished(second);
        assertEquals("\"Second\"", evaluate("document.body.textContent"));
        assertEquals(true, fromWebView(WebView::canGoBack));
        assertEquals(true, fromWebView(WebView::canGoForward));
    }

    /** In onReceivedError, the content takes the entry of the page that failed, in place of WebKit's error page. */
    @Test
    public void testInReceivedError() throws Exception {
        String first = setPage("/first.html", "First");
        String failing = "http://127.0.0.1:" + closedPort() + "/";

        loadAndWait(first);
        showErrorPageOnFailure = true;
        titles.clear();
        onWebView(view -> view.loadUrl(failing));
        waitForPageFinished(failing);
        waitForErrorPage();
        assertEquals(failing, fromWebView(WebView::getUrl));
        assertEquals(true, fromWebView(WebView::canGoBack));
        assertEquals(false, fromWebView(WebView::canGoForward));
        onWebView(WebView::goBack);
        waitForPageFinished(first);
        assertEquals(false, fromWebView(WebView::canGoBack));
        assertEquals(true, fromWebView(WebView::canGoForward));
    }

    /** A script of the content loads the page again with location.replace(), in the entry of the content. */
    @Test
    public void testLocationReplace() throws Exception {
        String page = setPage("/page.html", "Page");

        loadAndWait(page);
        loadErrorPage(page);
        onWebView(view -> view.evaluateJavascript("location.replace('" + page + "')", null));
        waitForPageFinished(page);
        assertEquals("\"Page\"", evaluate("document.body.textContent"));
        assertEquals(false, fromWebView(WebView::canGoForward));
    }

    /** The content replaces a load that the app asked for just before, and its start has the content's address. */
    @Test
    public void testReplacesLoad() throws Exception {
        String first = setPage("/first.html", "First");
        String second = setPage("/second.html", "Second");

        loadAndWait(first);
        startedUrls.clear();
        titles.clear();
        onWebView(view -> {
            view.loadUrl(second);
            view.loadAlternateHtml(ERROR_PAGE, first, null);
        });
        waitForPageFinished(first);
        waitForErrorPage();
        String started;
        while ((started = startedUrls.poll()) != null)
            assertEquals(first, started);
        assertEquals(first, fromWebView(WebView::getUrl));
    }

    /** After the end of the web process, the content shows in a new one, and a reload loads the page again. */
    @Test
    public void testAfterTermination() throws Exception {
        String page = setPage("/page.html", "Page");

        loadAndWait(page);
        onWebView(WebView::terminateWebProcess);
        Integer reason = terminations.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The view did not report the end of its web process", reason);
        assertEquals(WebViewClient.WEB_PROCESS_TERMINATED_BY_API, reason.intValue());
        loadErrorPage(page);
        assertEquals(page, fromWebView(WebView::getUrl));
        onWebView(WebView::reload);
        waitForPageFinished(page);
        assertEquals("\"Page\"", evaluate("document.body.textContent"));
    }
}
