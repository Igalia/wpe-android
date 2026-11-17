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
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpRequest;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Responses that the WebView does not show are saved where the app asks, in the network session of the page, while
 * the page stays.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewDownloadTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final Client client = new Client();
    private File destination;
    // The WebViews of the windows that pages opened.
    private final List<WebView> windows = new ArrayList<>();

    private static class Client extends TestWebViewClient {
        final BlockingQueue<WPEDownload> downloads = new LinkedBlockingQueue<>();
        final BlockingQueue<String> finishedPages = new LinkedBlockingQueue<>();
        final AtomicInteger frameNavigations = new AtomicInteger();

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WPEResourceRequest request) {
            if (!request.isForMainFrame())
                frameNavigations.incrementAndGet();
            return false;
        }

        @Override
        public void onDownloadStart(@NonNull WebView view, @NonNull WPEDownload download) {
            downloads.add(download);
        }

        @Override
        public void onPageFinished(@NonNull WebView view, @NonNull String url) {
            finishedPages.add(url);
            super.onPageFinished(view, url);
        }
    }

    /** A file sent as an attachment once the test lets each request have it, in the order they came. */
    private static class HeldFile extends HttpResponse {
        final BlockingQueue<CountDownLatch> requests = new LinkedBlockingQueue<>();

        HeldFile() {
            setStatusCode(200);
            setStatusMessage("OK");
            addHeader("Content-Type", "text/plain");
            addHeader("Content-Disposition", "attachment");
            addHeader("Cache-Control", "no-store");
            setBody("text");
        }

        @Override
        public void send(OutputStream outputStream) throws IOException {
            CountDownLatch answer = new CountDownLatch(1);
            requests.add(answer);
            try {
                answer.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.send(outputStream);
        }

        CountDownLatch nextRequest() throws InterruptedException {
            CountDownLatch answer = requests.poll(30, TimeUnit.SECONDS);
            assertNotNull("The server got no request for the file", answer);
            return answer;
        }
    }

    /** Records how a download goes: its progress, and then "finished" or "failed" with the error code. */
    private static class Listener implements WPEDownload.Listener {
        final BlockingQueue<String> ends = new LinkedBlockingQueue<>();
        volatile long receivedBytes = -1;
        volatile long totalBytes;

        @Override
        public void onProgress(@NonNull WPEDownload download, long receivedBytes, long totalBytes) {
            this.receivedBytes = receivedBytes;
            this.totalBytes = totalBytes;
        }

        @Override
        public void onFinished(@NonNull WPEDownload download) {
            ends.add("finished");
        }

        @Override
        public void onFailed(@NonNull WPEDownload download, int errorCode, @NonNull String description) {
            ends.add("failed " + errorCode);
        }
    }

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        destination = new File(cache, "download-test-" + UUID.randomUUID());
    }

    @After
    public void tearDown() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            for (WebView window : windows) {
                ((ViewGroup)window.getParent()).removeView(window);
                window.destroy();
            }
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
        destination.delete();
    }

    private String serve(String path, String contentType, String body, String... headers) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", contentType);
        for (int i = 0; i < headers.length; i += 2)
            response.addHeader(headers[i], headers[i + 1]);
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

    private String redirect(String path, String location) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(302);
        response.setStatusMessage("Found");
        response.addHeader("Location", location);
        response.setBody("");
        return httpServer.setResponse(path, response);
    }

    // A script that the app runs counts as a user gesture.
    private void run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Exception {
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, script + "; true");
    }

    // A page with a link that covers it, so that a tap in the middle of the view follows it.
    private static String pageWithLink(String href, String attributes) {
        return "<meta name=viewport content='width=device-width'><body style='margin:0'><a id=link href='" + href +
            "' " + attributes + " style='display:block;height:100vh'>Link</a><script>var rendered = false;"
            + "requestAnimationFrame(() => requestAnimationFrame(() => rendered = true));</script>";
    }

    private void tapTheMiddle(ActivityScenario<WebViewTestActivity> scenario) throws Exception {
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

    private WPEDownload nextDownload() throws InterruptedException {
        WPEDownload download = client.downloads.poll(30, TimeUnit.SECONDS);
        assertNotNull("The app was not told of the download", download);
        return download;
    }

    private static String content() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; content.length() < 200000; i++)
            content.append("Line ").append(i).append('\n');
        return content.toString();
    }

    @Test
    public void testAttachmentIsSavedWhileThePageStays() throws Exception {
        String content = content();
        String page = serve("/page.html", "text/html", "<title>Page</title><p>Page");
        String file =
            serve("/file.txt", "text/plain", content, "Content-Disposition", "attachment; filename=\"test-file.txt\"");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        assertEquals(file, download.getUrl().toString());
        assertTrue(download.hasGesture());
        assertFalse(download.isRequestedByApp());
        assertNull(download.getRequestedUrl());
        assertEquals("test-file.txt", download.getSuggestedFileName());
        assertEquals("text/plain", download.getMimeType());
        assertEquals(content.length(), download.getContentLength());

        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals(content, new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(destination.getPath() + ".wkdownload").exists());
        assertEquals(content.length(), listener.receivedBytes);
        assertEquals(content.length(), listener.totalBytes);

        // The load of the file ends with the address of the page, which stays with its title.
        assertEquals(page, client.finishedPages.poll(30, TimeUnit.SECONDS));
        scenario.onActivity(activity -> {
            assertEquals(page, activity.getWebView().getUrl());
            assertEquals("Page", activity.getWebView().getTitle());
        });
    }

    @Test
    public void testPageOfUnknownTypeIsDownloaded() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/archive.wpetest", "application/x-wpe-test", "data");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        assertEquals("archive.wpetest", download.getSuggestedFileName());
        assertEquals("application/x-wpe-test", download.getMimeType());

        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals("data", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }

    /** A frame of a type that the WebView cannot show is saved. One that loads with the page has no gesture. */
    @Test
    public void testFrameOfUnknownTypeIsDownloaded() throws Exception {
        String file = serve("/archive.wpetest", "application/x-wpe-test", "data");
        String page = serve("/page.html", "text/html", "<p>Page<iframe src='" + file + "'></iframe>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        WPEDownload download = nextDownload();
        assertEquals(file, download.getUrl().toString());
        assertEquals("archive.wpetest", download.getSuggestedFileName());
        assertFalse(download.hasGesture());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals("data", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        scenario.onActivity(activity -> assertEquals(page, activity.getWebView().getUrl()));
    }

    /** A frame shows the pages that the WebView can show, and saves the responses sent as attachments. */
    @Test
    public void testFrameShowsPagesAndSavesAttachments() throws Exception {
        String inner = serve("/inner.html", "text/html", "<p>Inner");
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String page = serve("/page.html", "text/html",
                            "<iframe id=shown src='" + inner + "'></iframe><iframe src='" + file + "'></iframe>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertEquals("\"Inner\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                      scenario, "shown.contentDocument.body.textContent", "\"Inner\""));
        assertNull(client.downloads.poll(1, TimeUnit.SECONDS));
    }

    /** A sandboxed frame saves nothing, unless its sandbox allows downloads. */
    @Test
    public void testSandboxedFrameSavesOnlyWhenAllowed() throws Exception {
        String archive = serve("/archive.wpetest", "application/x-wpe-test", "data");
        String attachment = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String allowed = serve("/allowed.wpetest", "application/x-wpe-test", "allowed");
        String page = serve("/page.html", "text/html",
                            "<iframe sandbox src='" + archive + "'></iframe><iframe sandbox src='" + attachment +
                                "'></iframe><iframe sandbox=allow-downloads src='" + allowed + "'></iframe>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(allowed, download.getUrl().toString());
        assertNull(client.downloads.poll(3, TimeUnit.SECONDS));
    }

    @Test
    public void testLinkWithDownloadAttributeIsDownloaded() throws Exception {
        String file = serve("/shown.txt", "text/plain", "text");
        String page = serve("/page.html", "text/html", "<a id=link href='" + file + "' download='saved.txt'>File</a>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "link.click()");
        WPEDownload download = nextDownload();
        assertEquals("saved.txt", download.getSuggestedFileName());
        assertTrue(download.hasGesture());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals("text", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }

    /** A tap on a link starts its download with a user gesture. */
    @Test
    public void testTapStartsADownloadWithAGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String page = serve("/page.html", "text/html", pageWithLink(file, ""));
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        tapTheMiddle(scenario);
        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertTrue(download.hasGesture());
    }

    /** Downloads that a page starts by itself as it loads have no gesture, as many as it starts. */
    @Test
    public void testDownloadsThatThePageStartsByItselfHaveNoGesture() throws Exception {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String file = serve("/file" + i + ".txt", "text/plain", "text " + i);
            links.append("<a href='").append(file).append("' download>File ").append(i).append("</a>");
        }
        String page = serve("/page.html", "text/html",
                            links + "<script>addEventListener('load', () => "
                                + "document.querySelectorAll('a').forEach(link => link.click()));</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        for (int i = 0; i < 3; i++) {
            WPEDownload download = nextDownload();
            scenario.onActivity(activity -> download.cancel());
            assertFalse(download.getUrl().toString(), download.hasGesture());
        }
    }

    /** A page that goes to a file by itself, as it loads, starts its download without a gesture. */
    @Test
    public void testPageThatGoesToAFileByItselfStartsADownloadWithoutAGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String page = serve("/page.html", "text/html",
                            "<script>addEventListener('load', () => location.href = '" + file + "');</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertFalse(download.hasGesture());
    }

    /** The redirects of the server keep the gesture of the navigation they follow, or the lack of one. */
    @Test
    public void testRedirectsKeepTheGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String redirect = redirect("/redirect", file);
        String page = serve("/page.html", "text/html", "<p>Page");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + redirect + "'");
        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertTrue(download.hasGesture());

        String byItself = serve("/by-itself.html", "text/html",
                                "<script>addEventListener('load', () => location.href = '" + redirect + "');</script>");
        run(scenario, "location.href = '" + byItself + "'");
        WPEDownload withoutGesture = nextDownload();
        scenario.onActivity(activity -> withoutGesture.cancel());
        assertEquals(file, withoutGesture.getUrl().toString());
        assertFalse(withoutGesture.hasGesture());
    }

    /** A link in a frame keeps its gesture through the redirects of the server. */
    @Test
    public void testRedirectsInAFrameKeepTheGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String redirect = redirect("/redirect", file);
        String inner = serve("/inner.html", "text/html", "<a id=link href='" + redirect + "'>File</a>");
        String page = serve("/page.html", "text/html", "<iframe id=frame src='" + inner + "'></iframe>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                 scenario, "!!frame.contentDocument.getElementById('link')", "true"));

        run(scenario, "frame.contentDocument.getElementById('link').click()");
        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertTrue(download.hasGesture());
    }

    // A frame of the page goes to a file by itself, and then the user taps a link to the same file in another frame.
    // The server answers the frame that it names first, and then the other one.
    private void checkFramesThatGoToTheSameFile(boolean pageAnsweredFirst) throws Exception {
        HeldFile heldFile = new HeldFile();
        String file = httpServer.setResponse("/file.txt", heldFile);
        String inner = serve("/inner.html", "text/html", "<a id=link href='" + file + "'>File</a>");
        String page = serve("/page.html", "text/html",
                            "<iframe id=frame src='" + inner + "'></iframe><iframe id=hidden></iframe><script>"
                                + "addEventListener('load', () => setTimeout(() => hidden.src = '" + file + "'));"
                                + "</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        CountDownLatch pageRequest = heldFile.nextRequest();
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                 scenario, "!!frame.contentDocument.getElementById('link')", "true"));
        run(scenario, "frame.contentDocument.getElementById('link').click()");
        CountDownLatch tapRequest = heldFile.nextRequest();

        for (CountDownLatch request :
             pageAnsweredFirst ? List.of(pageRequest, tapRequest) : List.of(tapRequest, pageRequest)) {
            request.countDown();
            WPEDownload download = nextDownload();
            scenario.onActivity(activity -> download.cancel());
            assertEquals(file, download.getUrl().toString());
            assertFalse(request == pageRequest ? "The page's download" : "The tap's download", download.hasGesture());
        }
    }

    /**
     * A page cannot pass its own download off as one that the user started, by going to the address of a link that the
     * user taps: neither download has a gesture, whichever the server answers first.
     */
    @Test
    public void testPageThatGoesToTheAddressOfATapFirstHasNoGesture() throws Exception {
        checkFramesThatGoToTheSameFile(true);
    }

    @Test
    public void testTapToTheAddressThatThePageGoesToHasNoGesture() throws Exception {
        checkFramesThatGoToTheSameFile(false);
    }

    /** Once the page's own download of an address came, a tap on a link to it starts a download with a gesture. */
    @Test
    public void testTapAfterThePageDownloadedTheSameFileHasAGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String page = serve("/page.html", "text/html",
                            "<a id=link href='" + file + "' download>File</a><script>"
                                + "addEventListener('load', () => link.click());</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        WPEDownload byPage = nextDownload();
        scenario.onActivity(activity -> byPage.cancel());
        assertFalse(byPage.hasGesture());

        run(scenario, "link.click()");
        WPEDownload byUser = nextDownload();
        scenario.onActivity(activity -> byUser.cancel());
        assertTrue(byUser.hasGesture());
    }

    /** A page that goes to more addresses than are counted gives no download a gesture, until the next page. */
    @Test
    public void testPageThatGoesToManyAddressesHasNoGestureUntilTheNextPage() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        // With a download attribute, so that the download ends no load of the page.
        String link = "<a id=link href='" + file + "' download>File</a>";
        // A page has 1000 frames at most, so a hundred frames go to eleven addresses each, one after the other.
        String page = serve("/page.html", "text/html",
                            link + "<script>for (let i = 0; i < 100; i++) {"
                                + "const frame = document.createElement('iframe'); let n = 0;"
                                + "frame.onload = () => { if (++n < 11) frame.src = `data:text/html,${i}-${n}`; };"
                                + "frame.src = `data:text/html,${i}-0`; document.body.append(frame); }</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        long end = SystemClock.uptimeMillis() + 30000;
        while (client.frameNavigations.get() < 1100 && SystemClock.uptimeMillis() < end)
            Thread.sleep(50);
        assertEquals(1100, client.frameNavigations.get());
        run(scenario, "link.click()");
        WPEDownload onThatPage = nextDownload();
        scenario.onActivity(activity -> onThatPage.cancel());
        assertFalse(onThatPage.hasGesture());

        scenario = load(serve("/next.html", "text/html", link));
        run(scenario, "link.click()");
        WPEDownload onTheNextPage = nextDownload();
        scenario.onActivity(activity -> onTheNextPage.cancel());
        assertTrue(onTheNextPage.hasGesture());
    }

    /** The loads that the app asks for count as user gestures, also through the redirects of the server. */
    @Test
    public void testLoadsOfTheAppStartDownloadsWithAGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String redirect = redirect("/redirect", file);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();

        for (String url : new String[] {file, redirect}) {
            scenario.onActivity(activity -> {
                activity.getWebView().setWebViewClient(client);
                activity.getWebView().loadUrl(url);
            });
            WPEDownload download = nextDownload();
            scenario.onActivity(activity -> download.cancel());
            assertEquals(file, download.getUrl().toString());
            assertTrue(url, download.hasGesture());
        }
    }

    /**
     * The app may ask for a load before WebKit decided on the one it asked for before, as for the blank page that a new
     * view loads first. WebKit then loads both, one after the other, and both are the app's.
     */
    @Test
    public void testLoadAfterAnotherLoadOfTheAppStartsADownloadWithAGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> {
            activity.getWebView().setWebViewClient(client);
            activity.getWebView().loadUrl("about:blank");
            activity.getWebView().loadUrl(file);
        });
        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertTrue(download.hasGesture());
    }

    /** A load of the app that was stopped is not the app's when the page goes to its address by itself. */
    @Test
    public void testPageThatGoesToTheAddressOfAStoppedLoadOfTheAppHasNoGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        // It goes once the test lets it, from a timer that it started by itself.
        String page = serve("/page.html", "text/html",
                            "<script>var go = false; setInterval(() => { if (go) { go = false; location.href = '" +
                                file + "'; } }, 50);</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        scenario.onActivity(activity -> {
            activity.getWebView().loadUrl(file);
            activity.getWebView().stopLoading();
        });

        run(scenario, "go = true");
        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> download.cancel());
        assertEquals(file, download.getUrl().toString());
        assertFalse(download.hasGesture());
    }

    /** Two loads of the app to one address are both the app's, and neither is left for the page to go there. */
    @Test
    public void testPageThatGoesToTheAddressOfTwoLoadsOfTheAppHasNoGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String page = serve("/page.html", "text/html",
                            "<script>var go = false; setInterval(() => { if (go) { go = false; location.href = '" +
                                file + "'; } }, 50);</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        scenario.onActivity(activity -> {
            activity.getWebView().loadUrl(file);
            activity.getWebView().loadUrl(file);
        });
        // WebKit may download the file once for both loads, or once for each.
        WPEDownload download = nextDownload();
        do {
            WPEDownload byApp = download;
            scenario.onActivity(activity -> byApp.cancel());
            assertTrue(byApp.hasGesture());
        } while ((download = client.downloads.poll(2, TimeUnit.SECONDS)) != null);

        run(scenario, "go = true");
        WPEDownload byPage = nextDownload();
        scenario.onActivity(activity -> byPage.cancel());
        assertEquals(file, byPage.getUrl().toString());
        assertFalse(byPage.hasGesture());
    }

    /** A link to a new window, or a script that opens one, gives the download of the window its gesture. */
    @Test
    public void testNewWindowStartsADownloadWithAGesture() throws Exception {
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        String page = serve("/page.html", "text/html", "<a id=link href='" + file + "' target=_blank>File</a>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public @Nullable WebView onCreateWindow(@NonNull WebView view, boolean isUserGesture) {
                WebView window = new WebView(activity, view);
                windows.add(window);
                window.setWebViewClient(client);
                activity.addContentView(window, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                           ViewGroup.LayoutParams.MATCH_PARENT));
                return window;
            }
        }));

        for (String script : new String[] {"link.click()", "window.open('" + file + "')"}) {
            run(scenario, script);
            WPEDownload download = nextDownload();
            scenario.onActivity(activity -> download.cancel());
            assertEquals(file, download.getUrl().toString());
            assertTrue(script, download.hasGesture());
        }
        scenario.onActivity(activity -> assertEquals(2, windows.size()));
    }

    @Test
    public void testSuggestedNameIsAPlainName() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition",
                            "attachment; filename=\"../../.hidden/file name.txt\"");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        String name = download.getSuggestedFileName();
        scenario.onActivity(activity -> download.cancel());
        assertFalse(name, name.isEmpty());
        assertFalse(name, name.contains("/"));
        assertFalse(name, name.startsWith("."));
    }

    @Test
    public void testUndecidedDownloadIsCancelledWithTheWebView() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        // The app starts the download once the WebView is gone, which cancelled it.
        Listener listener = new Listener();
        scenario.onActivity(activity -> {
            activity.getWebView().destroy();
            download.start(destination, listener);
        });
        assertEquals("failed " + WPEDownload.ERROR_CANCELLED, listener.ends.poll(30, TimeUnit.SECONDS));
        assertFalse(destination.exists());
    }

    @Test
    public void testDownloadKeepsTheCookiesOfThePage() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page", "Set-Cookie", "session=signed-in; Path=/");
        String file = serve("/private.txt", "text/plain", "private", "Content-Disposition", "attachment");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));

        HttpRequest request = httpServer.getLastRequest("/private.txt");
        assertNotNull(request);
        assertEquals("session=signed-in", request.getHeaders().get("Cookie"));
    }

    @Test
    public void testDownloadThatCannotBeWrittenFails() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        Listener listener = new Listener();
        File unwritable = new File(destination, "no-such-directory/file.txt");
        scenario.onActivity(activity -> download.start(unwritable, listener));
        assertEquals("failed " + WPEDownload.ERROR_DESTINATION, listener.ends.poll(30, TimeUnit.SECONDS));
        assertFalse(unwritable.exists());
    }

    @Test
    public void testCancelledDownloadIsNotSaved() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "location.href = '" + file + "'");
        WPEDownload download = nextDownload();
        scenario.onActivity(activity -> {
            download.cancel();
            // A cancelled download cannot be started.
            try {
                download.start(destination, new Listener());
                throw new AssertionError("A cancelled download started");
            } catch (IllegalStateException expected) {
                // Expected.
            }
        });
        // The page stays, and another load goes on as usual.
        assertEquals(page, client.finishedPages.poll(30, TimeUnit.SECONDS));
        String next = serve("/next.html", "text/html", "<p>Next");
        run(scenario, "location.href = '" + next + "'");
        assertEquals(next, client.finishedPages.poll(30, TimeUnit.SECONDS));
        assertFalse(destination.exists());
        assertNull(client.downloads.poll());
    }

    @Test
    public void testDownloadsAreCancelledByDefault() throws Exception {
        TestWebViewClient defaultClient = new TestWebViewClient();
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/file.txt", "text/plain", "text", "Content-Disposition", "attachment");
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        WebViewActivityScenarioHelper.loadUrlSync(scenario, defaultClient, page);

        defaultClient.getOnPageFinishedHelper().reset();
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, defaultClient,
                                                                         "location.href = '" + file + "'; true");
        defaultClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
        scenario.onActivity(activity -> assertEquals(page, activity.getWebView().getUrl()));
    }

    /**
     * The app downloads an address in the network session of the page, with its cookies and its user agent, as when
     * the user saves a link of the page. The page stays.
     */
    @Test
    public void testAppDownloadsAnAddressInTheSessionOfThePage() throws Exception {
        String content = content();
        String page =
            serve("/page.html", "text/html", "<title>Page</title><p>Page", "Set-Cookie", "session=signed-in; Path=/");
        String file =
            serve("/private.txt", "text/plain", content, "Content-Disposition", "attachment; filename=\"saved.txt\"");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(file));
        WPEDownload download = nextDownload();
        assertTrue(download.isRequestedByApp());
        assertTrue(download.hasGesture());
        assertEquals(file, download.getUrl().toString());
        assertEquals(file, download.getRequestedUrl().toString());
        assertEquals("saved.txt", download.getSuggestedFileName());
        assertEquals("text/plain", download.getMimeType());
        assertEquals(content.length(), download.getContentLength());

        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals(content, new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));

        HttpRequest request = httpServer.getLastRequest("/private.txt");
        assertNotNull(request);
        assertEquals("session=signed-in", request.getHeaders().get("Cookie"));
        String userAgent =
            WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, "navigator.userAgent");
        assertEquals(userAgent, "\"" + request.getHeaders().get("User-Agent") + "\"");
        assertNull(client.finishedPages.poll(1, TimeUnit.SECONDS));
        scenario.onActivity(activity -> {
            assertEquals(page, activity.getWebView().getUrl());
            assertEquals("Page", activity.getWebView().getTitle());
        });
    }

    /** The address of a page that shows a file downloads that file. */
    @Test
    public void testAppDownloadsTheFileThatThePageShows() throws Exception {
        String content = content();
        String file = serve("/shown.txt", "text/plain", content);
        ActivityScenario<WebViewTestActivity> scenario = load(file);

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(activity.getWebView().getUrl()));
        WPEDownload download = nextDownload();
        assertTrue(download.isRequestedByApp());
        assertEquals(file, download.getUrl().toString());
        assertEquals("shown.txt", download.getSuggestedFileName());
        assertEquals("text/plain", download.getMimeType());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals(content, new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        scenario.onActivity(activity -> assertEquals(file, activity.getWebView().getUrl()));
    }

    /** A PDF that the page shows in the built in viewer keeps its address, which downloads the PDF itself. */
    @Test
    public void testAppDownloadsThePdfThatThePageShows() throws Exception {
        String pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                     + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                     + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
                     + "trailer<</Root 1 0 R>>\n%%EOF\n";
        String file = serve("/document.pdf", "application/pdf", pdf);
        ActivityScenario<WebViewTestActivity> scenario = load(file);
        assertNull("The PDF was not shown", client.downloads.poll(1, TimeUnit.SECONDS));

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(activity.getWebView().getUrl()));
        WPEDownload download = nextDownload();
        assertEquals(file, download.getUrl().toString());
        assertEquals("application/pdf", download.getMimeType());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals(pdf, new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * A download that the app asks for follows the redirects of the server, and has the address it ends at, besides the
     * address that the app asked for.
     */
    @Test
    public void testAppDownloadFollowsTheRedirectsOfTheServer() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        String file = serve("/file.txt", "text/plain", "text");
        String redirect = redirect("/redirect", file);
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(redirect));
        WPEDownload download = nextDownload();
        assertTrue(download.isRequestedByApp());
        assertEquals(file, download.getUrl().toString());
        assertEquals(redirect, download.getRequestedUrl().toString());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals("text", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * A download that the app asks for and that fails before the server answers comes all the same, as one that
     * failed: without a type or a size, and with the last part of the address it asked for as its name, which is a
     * plain name also when the address hides slashes in it.
     */
    @Test
    public void testAppDownloadThatFailsBeforeTheServerAnswersComesFailed() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        String refused = "http://127.0.0.1:" + WebPageTestHelper.closedPort();

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(refused + "/file.txt"));
        WPEDownload download = nextDownload();
        assertTrue(download.isRequestedByApp());
        assertTrue(download.hasGesture());
        assertEquals(refused + "/file.txt", download.getUrl().toString());
        assertEquals(refused + "/file.txt", download.getRequestedUrl().toString());
        assertEquals("file.txt", download.getSuggestedFileName());
        assertEquals("", download.getMimeType());
        assertEquals(-1, download.getContentLength());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("failed " + WPEDownload.ERROR_NETWORK, listener.ends.poll(30, TimeUnit.SECONDS));
        assertFalse(destination.exists());
        assertNull(client.downloads.poll(1, TimeUnit.SECONDS));

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(refused + "/..%2F..%2F.hidden%2Fname.txt"));
        WPEDownload hidden = nextDownload();
        scenario.onActivity(activity -> hidden.cancel());
        assertEquals("name.txt", hidden.getSuggestedFileName());
    }

    /** An address that WebKit cannot load comes at once as failed, and only once. */
    @Test
    public void testAppDownloadOfAnAddressThatCannotBeLoadedComesFailed() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        String broken = "http://[broken/file.txt";

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(broken));
        WPEDownload download = nextDownload();
        assertEquals(broken, download.getRequestedUrl().toString());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        String end = listener.ends.poll(30, TimeUnit.SECONDS);
        assertNotNull(end);
        assertTrue(end, end.startsWith("failed "));
        assertFalse(destination.exists());
        assertNull(client.downloads.poll(1, TimeUnit.SECONDS));
    }

    /**
     * Only http, https, data and blob addresses download. Any other comes at once as failed, such as the address of a
     * file of the app, which the network process could read, also when written in the other ways that WebKit reads.
     */
    @Test
    public void testAppDownloadOfAnotherAddressComesFailed() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        File secret = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),
                               "download-test-" + UUID.randomUUID());
        Files.write(secret.toPath(), "Secret".getBytes(StandardCharsets.UTF_8));
        String path = secret.getAbsolutePath();
        try {
            for (String url : new String[] {"file://" + path, "FILE://" + path, " file://" + path, "fi\nle://" + path,
                                            "about:blank", "javascript:void(0)"}) {
                scenario.onActivity(activity -> {
                    activity.getWebView().downloadUrl(url);
                    // It comes later, as any download, so that the app can first note what it asked for.
                    assertNull(url, client.downloads.peek());
                });
                WPEDownload download = nextDownload();
                assertTrue(url, download.isRequestedByApp());
                assertEquals(url, download.getRequestedUrl().toString());
                Listener listener = new Listener();
                scenario.onActivity(activity -> download.start(destination, listener));
                assertEquals(url, "failed " + WPEDownload.ERROR_UNKNOWN, listener.ends.poll(30, TimeUnit.SECONDS));
                assertFalse(url, destination.exists());
            }
            assertNull(client.downloads.poll(1, TimeUnit.SECONDS));
        } finally {
            secret.delete();
        }
    }

    /** The data and blob addresses of the pages download as web addresses do, with their schemes in either case. */
    @Test
    public void testAppDownloadsDataAndBlobAddresses() throws Exception {
        // The page keeps its blob, and reads it once, so that the network process has it when the app downloads it.
        String page =
            serve("/page.html", "text/html",
                  "<p>Page<script>var blob = new Blob(['Blob text'], {type: 'text/plain'});"
                      + "var blobUrl = URL.createObjectURL(blob); var blobRead = false;"
                      + "fetch(blobUrl).then(response => response.text()).then(() => blobRead = true);</script>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);
        assertEquals("The page could not read its blob", "true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "blobRead", "true"));
        String blob = WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, "blobUrl");
        // The result of the script is a JSON string.
        blob = blob.substring(1, blob.length() - 1);

        String[][] addresses = {{"data:text/plain,Data%20text", "Data text"},
                                {"DATA:text/plain,Upper%20case", "Upper case"},
                                {blob, "Blob text"}};
        for (String[] address : addresses) {
            scenario.onActivity(activity -> activity.getWebView().downloadUrl(address[0]));
            WPEDownload download = nextDownload();
            assertEquals(address[0], download.getRequestedUrl().toString());
            Listener listener = new Listener();
            scenario.onActivity(activity -> download.start(destination, listener));
            // How it ended first, which tells why it failed.
            assertEquals(address[0], "finished", listener.ends.poll(30, TimeUnit.SECONDS));
            assertEquals(address[0], "text/plain", download.getMimeType());
            assertEquals(address[1], new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
            destination.delete();
        }
    }

    /** A response that never ends its headers, and tells when the client closes the connection. */
    static class EndlessHeaders extends HttpResponse {
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void send(OutputStream outputStream) {
            requested.countDown();
            PrintStream stream = new PrintStream(outputStream);
            stream.print("HTTP/1.1 200 OK\r\n");
            long deadline = SystemClock.uptimeMillis() + 30_000;
            while (SystemClock.uptimeMillis() < deadline) {
                stream.print("X-Wait: 1\r\n");
                // A write fails once the client has closed the connection.
                if (stream.checkError()) {
                    closed.countDown();
                    return;
                }
                SystemClock.sleep(100);
            }
        }
    }

    /**
     * A download that the app asks for ends with its WebView when the WebView is destroyed before the server answers:
     * the app is not told of it, and the request ends.
     */
    @Test
    public void testAppDownloadIsCancelledWhenTheWebViewIsDestroyedFirst() throws Exception {
        String page = serve("/page.html", "text/html", "<p>Page");
        EndlessHeaders endless = new EndlessHeaders();
        String file = httpServer.setResponse("/endless.txt", endless);
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        scenario.onActivity(activity -> activity.getWebView().downloadUrl(file));
        assertTrue("The server got no request", endless.requested.await(30, TimeUnit.SECONDS));
        scenario.onActivity(activity -> activity.getWebView().destroy());
        assertTrue("The request went on", endless.closed.await(15, TimeUnit.SECONDS));
        assertNull(client.downloads.poll(1, TimeUnit.SECONDS));
    }
}
