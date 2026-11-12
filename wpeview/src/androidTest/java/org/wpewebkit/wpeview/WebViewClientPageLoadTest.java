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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The addresses that WebViewClient.onPageStarted reports, and the failures that WebViewClient.onReceivedError reports.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewClientPageLoadTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final BlockingQueue<String> startedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<WPEResourceError> errors = new LinkedBlockingQueue<>();
    private final List<String> errorUrls = new CopyOnWriteArrayList<>();
    private final List<String> errorMethods = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            activity.getWebView().setWebViewClient(new WebViewClient() {
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
                    errorUrls.add(request.getUrl().toString());
                    errorMethods.add(request.getMethod());
                    errors.add(error);
                }
            });
        });
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

    private String setPage(String path, String body) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBodyText(body);
        return httpServer.setResponse(path, response);
    }

    private void loadUrl(String url) {
        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().loadUrl(url));
    }

    private void evaluateJavascript(String script) {
        webViewActivityTestRule.getScenario().onActivity(
            activity -> activity.getWebView().evaluateJavascript(script, null));
    }

    private void waitForPageFinished(String url) throws InterruptedException {
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
    }

    private void loadAndWait(String url) throws InterruptedException {
        loadUrl(url);
        waitForPageFinished(url);
        startedUrls.clear();
    }

    @Test
    public void testOnPageStartedForLoadUrl() throws Exception {
        String url = setPage("/a.html", "A");
        loadUrl(url);
        assertEquals(url, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** A link the user follows starts a load that the page starts itself, which reports the link's address. */
    @Test
    public void testOnPageStartedForLink() throws Exception {
        String linkTarget = setPage("/b.html", "B");
        String url = setPage("/a.html", "<a id=link href=/b.html>B</a>");
        loadAndWait(url);

        evaluateJavascript("document.getElementById('link').click()");
        assertEquals(linkTarget, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        waitForPageFinished(linkTarget);
    }

    @Test
    public void testOnPageStartedForScriptNavigation() throws Exception {
        String target = setPage("/c.html", "C");
        String url = setPage("/a.html", "A");
        loadAndWait(url);

        evaluateJavascript("location.href = '/c.html'");
        assertEquals(target, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        waitForPageFinished(target);
    }

    /**
     * A page that moves on by itself while it is still loading, here an image from a server that never answers. Its
     * load stops before the new one starts.
     */
    @Test
    public void testOnPageStartedForScriptNavigationWhileLoading() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0)) {
            String target = setPage("/c.html", "C");
            String url =
                setPage("/a.html", "<img src='http://127.0.0.1:" + silentServer.getLocalPort() + "/a.png'>"
                                       + "<script>setTimeout(() => location.href = '/c.html', 500)</script>");
            loadUrl(url);
            assertEquals(url, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(target, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            waitForPageFinished(target);
        }
    }

    /**
     * A page that goes twice to an address that never answers. The second load stops the first one, to the same
     * address, and reports that address too, not the page it leaves.
     */
    @Test
    public void testOnPageStartedForSecondNavigationToTheSameAddress() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0)) {
            String slow = "http://127.0.0.1:" + silentServer.getLocalPort() + "/slow.html";
            loadAndWait(setPage("/a.html", "A"));

            evaluateJavascript("location.href = '" + slow + "'");
            assertEquals(slow, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Thread.sleep(1000);
            evaluateJavascript("location.href = '" + slow + "'");
            assertEquals(slow, startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testOnReceivedErrorForRefusedConnection() throws Exception {
        String url = "http://127.0.0.1:" + closedPort() + "/";
        loadUrl(url);

        WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No error for a refused connection", error);
        assertEquals(error.getDescription(), WebViewClient.ERROR_CONNECT, error.getErrorCode());
        assertEquals(url, errorUrls.get(0));
        assertEquals("GET", errorMethods.get(0));
        // The load that failed ends with its own address, before WebKit's error page loads.
        assertEquals(url, finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** The request of a form that fails to post has the POST method. */
    @Test
    public void testOnReceivedErrorForFormPost() throws Exception {
        String action = "http://127.0.0.1:" + closedPort() + "/post";
        loadAndWait(setPage("/form.html", "<form method=post action='" + action + "'><input name=a value=b></form>"));

        evaluateJavascript("document.forms[0].submit()");
        WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No error for a refused connection", error);
        assertEquals(error.getDescription(), WebViewClient.ERROR_CONNECT, error.getErrorCode());
        assertEquals(action, errorUrls.get(0));
        assertEquals("POST", errorMethods.get(0));
    }

    /** The original address is the one given to loadUrl, also after a redirect, and getUrl is where it led. */
    @Test
    public void testOriginalUrlAfterRedirect() throws Exception {
        String target = setPage("/target.html", "Target");
        HttpResponse redirect = new HttpResponse();
        redirect.setStatusCode(302);
        redirect.setStatusMessage("Found");
        redirect.addHeader("Location", "/target.html");
        String start = httpServer.setResponse("/start.html", redirect);
        loadUrl(start);
        waitForPageFinished(target);
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            assertEquals(start, activity.getWebView().getOriginalUrl());
            assertEquals(target, activity.getWebView().getUrl());
        });
    }

    /** A destroyed WebView loads nothing, runs no script and keeps the address of its last page. */
    @Test
    public void testDestroyedWebViewLoadsNothing() throws Exception {
        String url = setPage("/a.html", "A");
        String other = setPage("/b.html", "B");
        loadAndWait(url);
        AtomicBoolean scriptAnswered = new AtomicBoolean();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            WebView webView = activity.getWebView();
            webView.destroy();
            webView.loadUrl(other);
            webView.reload();
            webView.evaluateJavascript("1", result -> scriptAnswered.set(true));
            assertEquals(url, webView.getUrl());
        });
        assertNull("A destroyed WebView started a load", startedUrls.poll(1, TimeUnit.SECONDS));
        assertFalse("A destroyed WebView ran a script", scriptAnswered.get());
    }

    @Test
    public void testOnReceivedErrorForRedirectLoop() throws Exception {
        HttpResponse redirect = new HttpResponse();
        redirect.setStatusCode(302);
        redirect.setStatusMessage("Found");
        redirect.addHeader("Location", "/loop.html");
        loadUrl(httpServer.setResponse("/loop.html", redirect));

        WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No error for a redirect loop", error);
        assertEquals(error.getDescription(), WebViewClient.ERROR_REDIRECT_LOOP, error.getErrorCode());
    }

    // A port on which nothing listens, so that connections to it are refused.
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    public void testOnReceivedErrorForUnknownHost() throws Exception {
        loadUrl("http://wpeview-test.invalid/");

        WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No error for an unknown host", error);
        assertEquals(error.getDescription(), WebViewClient.ERROR_HOST_LOOKUP, error.getErrorCode());
    }

    /**
     * Pages that load, also with an HTTP error or as a media file, and loads that another load cancels, are not load
     * failures.
     */
    @Test
    public void testNoErrorForLoadedOrCancelledPages() throws Exception {
        HttpResponse notFound = new HttpResponse();
        notFound.setStatusCode(HttpResponse.HTTP_NOT_FOUND);
        notFound.setStatusMessage("Not Found");
        notFound.addHeader("Content-Type", "text/html; charset=utf-8");
        notFound.setBodyText("Not found");
        loadAndWait(httpServer.setResponse("/404.html", notFound));

        // WebKit hands a media file to its media player, and fails the first load of the page with an error of its own.
        HttpResponse media = new HttpResponse();
        media.setStatusCode(200);
        media.setStatusMessage("OK");
        media.addHeader("Content-Type", "video/mp4");
        loadAndWait(httpServer.setResponse("/v.mp4", media));

        // A server that never answers: its load is still going on when the next one cancels it.
        try (ServerSocket silentServer = new ServerSocket(0)) {
            loadUrl("http://127.0.0.1:" + silentServer.getLocalPort() + "/");
            Thread.sleep(1000);
            loadAndWait(setPage("/a.html", "A"));
        }

        assertTrue("Unexpected errors for " + errorUrls, errors.isEmpty());
    }
}
