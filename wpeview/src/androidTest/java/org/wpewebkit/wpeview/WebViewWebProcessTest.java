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

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * WebViewClient.onWebProcessTerminated, and how the view goes on once the web process of its page ended: it keeps the
 * address, the title and the Back/Forward list of the page, reports no navigation, and loads pages again.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewWebProcessTest {
    private static final long TIMEOUT_SECONDS = 30;
    // Long enough for a report that should not come to arrive.
    private static final long QUIET_MILLISECONDS = 1000;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> terminations = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> titles = new LinkedBlockingQueue<>();
    // The navigations and titles that the view reports, in order.
    private final BlockingQueue<String> reports = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        onWebView(view -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }

                @Override
                public void doUpdateVisitedHistory(@NonNull WebView view, @NonNull String url, boolean isReload) {
                    reports.add("visited " + url);
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
                    reports.add("title " + title);
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

    private String setPage(String path, String html) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(html);
        return httpServer.setResponse(path, response);
    }

    private String setTitledPage(String path, String title) {
        return setPage(path, "<title>" + title + "</title><p>" + title + "</p>");
    }

    private void onWebView(Consumer<WebView> action) {
        webViewActivityTestRule.getScenario().onActivity(activity -> action.accept(activity.getWebView()));
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

    // Waits for the title, which may come after others, such as the address of a page before its title is known.
    private void waitForTitle(String expected) throws InterruptedException {
        String title;
        do {
            title = titles.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("No title " + expected, title);
        } while (!title.equals(expected));
    }

    // Loads the page and waits for its navigation and its title to be reported, in any order.
    private void loadTitledPage(String url, String title) throws InterruptedException {
        loadAndWait(url);
        boolean visited = false;
        boolean titled = false;
        while (!visited || !titled) {
            String report = reports.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The view did not report the page and its title: " + url, report);
            visited |= report.equals("visited " + url);
            titled |= report.equals("title " + title);
        }
        titles.clear();
    }

    // Ends the web processes of the app as Android ends them to free memory: the view's page runs in one of them.
    private static void killWebProcesses() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        int killed = 0;
        for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
            if (process.processName.contains(":WPEWebProcess")) {
                Process.killProcess(process.pid);
                killed++;
            }
        }
        assertNotEquals("No web process to end", 0, killed);
    }

    private void waitForTermination(int expectedReason) throws InterruptedException {
        Integer reason = terminations.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The view did not report the end of its web process", reason);
        assertEquals(expectedReason, reason.intValue());
    }

    // The view keeps its page: no navigation or title is reported, and it still has the address and the title.
    private void assertPageKept(String url, String title) throws InterruptedException {
        assertNull(reports.poll(QUIET_MILLISECONDS, TimeUnit.MILLISECONDS));
        assertEquals(url, fromWebView(WebView::getUrl));
        assertEquals(title, fromWebView(WebView::getTitle));
    }

    @Test
    public void testTerminateWebProcess() throws Exception {
        String page = setTitledPage("/page.html", "Page");

        loadTitledPage(page, "Page");
        onWebView(WebView::terminateWebProcess);
        waitForTermination(WebViewClient.WEB_PROCESS_TERMINATED_BY_API);
        assertPageKept(page, "Page");

        onWebView(WebView::reload);
        waitForPageFinished(page);
        BlockingQueue<String> results = new LinkedBlockingQueue<>();
        onWebView(view -> view.evaluateJavascript("document.body.textContent", results::add));
        assertEquals("\"Page\"", results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** A web process that Android ends is reported as a crash. The Back/Forward list stays. */
    @Test
    public void testWebProcessKilled() throws Exception {
        String first = setTitledPage("/first.html", "First");
        String second = setTitledPage("/second.html", "Second");

        loadTitledPage(first, "First");
        loadTitledPage(second, "Second");
        killWebProcesses();
        waitForTermination(WebViewClient.WEB_PROCESS_CRASHED);
        assertPageKept(second, "Second");
        assertEquals(true, fromWebView(WebView::canGoBack));

        onWebView(WebView::goBack);
        waitForPageFinished(first);
        waitForTitle("First");
        assertEquals(true, fromWebView(WebView::canGoForward));
    }

    /** The next page has its own title, also when it has none and WebKit's title stays empty. */
    @Test
    public void testLoadAfterTermination() throws Exception {
        String titled = setTitledPage("/titled.html", "Titled");
        String untitled = setPage("/untitled.html", "<p>No title</p>");

        loadTitledPage(titled, "Titled");
        onWebView(WebView::terminateWebProcess);
        waitForTermination(WebViewClient.WEB_PROCESS_TERMINATED_BY_API);
        loadAndWait(untitled);
        waitForTitle(WebView.titleFromUrl(untitled));
        assertEquals(WebView.titleFromUrl(untitled), fromWebView(WebView::getTitle));
        assertEquals(untitled, fromWebView(WebView::getUrl));
    }

    /**
     * The page loads right away in a new web process while the one just ended is still ending. Binding the service of
     * that process again would start the new one in the process that is ending, and once that ends, Android would
     * start the service again only after a delay.
     */
    @Test
    public void testLoadRightAfterTermination() throws Exception {
        String first = setTitledPage("/first.html", "First");
        String second = setTitledPage("/second.html", "Second");

        loadAndWait(first);
        // Services that Android started again in earlier tests may still run.
        Map<String, Integer> restartsBefore = WebViewActivityScenarioHelper.serviceRestarts();
        onWebView(view -> {
            view.terminateWebProcess();
            view.loadUrl(second);
        });
        waitForPageFinished(second);
        List<String> restarted = new ArrayList<>();
        WebViewActivityScenarioHelper.serviceRestarts().forEach((service, count) -> {
            if (count > restartsBefore.getOrDefault(service, 0))
                restarted.add(service);
        });
        assertEquals("Android started services of the app again", Collections.emptyList(), restarted);
    }
}
