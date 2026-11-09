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
}
