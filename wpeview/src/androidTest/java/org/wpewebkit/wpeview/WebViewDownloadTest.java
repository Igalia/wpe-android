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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    private static class Client extends TestWebViewClient {
        final BlockingQueue<WPEDownload> downloads = new LinkedBlockingQueue<>();
        final BlockingQueue<String> finishedPages = new LinkedBlockingQueue<>();

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

    private void run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Exception {
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, script + "; true");
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

    @Test
    public void testLinkWithDownloadAttributeIsDownloaded() throws Exception {
        String file = serve("/shown.txt", "text/plain", "text");
        String page = serve("/page.html", "text/html", "<a id=link href='" + file + "' download='saved.txt'>File</a>");
        ActivityScenario<WebViewTestActivity> scenario = load(page);

        run(scenario, "link.click()");
        WPEDownload download = nextDownload();
        assertEquals("saved.txt", download.getSuggestedFileName());
        Listener listener = new Listener();
        scenario.onActivity(activity -> download.start(destination, listener));
        assertEquals("finished", listener.ends.poll(30, TimeUnit.SECONDS));
        assertEquals("text", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
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
