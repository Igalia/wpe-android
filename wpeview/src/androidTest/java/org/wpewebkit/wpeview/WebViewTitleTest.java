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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The titles that WebChromeClient.onReceivedTitle and WebView.getTitle report. A page without a title has its address
 * as its title, as with android.webkit.WebView.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewTitleTest {
    private static final long TIMEOUT_SECONDS = 30;
    // Stands for a null title in the queue, which takes no null.
    private static final String NULL_TITLE = "(null title)";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final BlockingQueue<String> titles = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            activity.getWebView().setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }
            });
            activity.getWebView().setWebChromeClient(new WebChromeClient() {
                @Override
                public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
                    titles.add(title != null ? title : NULL_TITLE);
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

    private String setPage(String path, String html) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(html);
        return httpServer.setResponse(path, response);
    }

    private void loadAndWait(String url) throws InterruptedException {
        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().loadUrl(url));
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
    }

    private void evaluateJavascript(String script) {
        webViewActivityTestRule.getScenario().onActivity(
            activity -> activity.getWebView().evaluateJavascript(script, null));
    }

    private String getTitle() {
        AtomicReference<String> title = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> title.set(activity.getWebView().getTitle()));
        return title.get();
    }

    // Waits for the title, which may come after others, such as the address of a page before its title is known.
    private void waitForTitle(String expected) throws InterruptedException {
        String title;
        do {
            title = titles.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("No title " + expected, title);
            assertNotEquals("A null title", NULL_TITLE, title);
        } while (!title.equals(expected));
        assertEquals(expected, getTitle());
    }

    private static String withoutScheme(String url) { return url.substring("http://".length()); }

    @Test
    public void testPageWithoutTitle() throws Exception {
        assertEquals("Title before any load", "", getTitle());
        String url = setPage("/untitled.html", "<p>No title</p>");
        loadAndWait(url);
        waitForTitle(withoutScheme(url));
    }

    /** Each page reports its title, also after a page without one, and pages without one report their address. */
    @Test
    public void testPagesWithAndWithoutTitle() throws Exception {
        String titled = setPage("/titled.html", "<title>Titled</title>");
        String untitled = setPage("/untitled.html", "<p>No title</p>");
        String otherUntitled = setPage("/other.html", "<p>No title either</p>");

        loadAndWait(titled);
        waitForTitle("Titled");
        loadAndWait(untitled);
        waitForTitle(withoutScheme(untitled));
        loadAndWait(otherUntitled);
        waitForTitle(withoutScheme(otherUntitled));
        evaluateJavascript("location.hash = 'part'");
        waitForTitle(withoutScheme(otherUntitled) + "#part");
        evaluateJavascript("document.title = 'Set by script'");
        waitForTitle("Set by script");
        evaluateJavascript("document.title = ''");
        waitForTitle(withoutScheme(otherUntitled) + "#part");
    }

    @Test
    public void testTitleFromUrl() {
        assertEquals("localhost:8080/a.html", WebView.titleFromUrl("http://localhost:8080/a.html"));
        assertEquals("google.com/robots.txt", WebView.titleFromUrl("https://www.google.com/robots.txt"));
        assertEquals("example.com/?q=www.", WebView.titleFromUrl("https://example.com/?q=www."));
        assertEquals("example.com/a", WebView.titleFromUrl("https://user:secret@www.example.com/a"));
        assertEquals("example.com", WebView.titleFromUrl("http://user@example.com/"));
        assertEquals("example.com/?to=a@b", WebView.titleFromUrl("https://example.com/?to=a@b"));
        assertEquals("localhost:8080", WebView.titleFromUrl("http://localhost:8080/"));
        assertEquals("example.com/a/", WebView.titleFromUrl("https://example.com/a/"));
        assertEquals("localhost:8080/a b.html", WebView.titleFromUrl("http://localhost:8080/a%20b.html"));
        assertEquals("about:blank", WebView.titleFromUrl("about:blank"));
        assertEquals("data:text/html,www.", WebView.titleFromUrl("data:text/html,www."));
    }
}
