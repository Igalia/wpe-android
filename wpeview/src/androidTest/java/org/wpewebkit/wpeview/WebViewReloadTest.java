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
import androidx.annotation.Nullable;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpRequest;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.TIMEOUT_SECONDS;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.servePage;

/**
 * A plain reload asks the server whether the copy of a resource that the cache holds changed, while a reload that
 * bypasses the cache asks for a new copy of the page and of each resource. The page loads a script whose copy the cache
 * keeps for an hour, with a version tag that the server would compare.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewReloadTest {
    private static final String VERSION_TAG = "\"1\"";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private WebContext context;
    private WebView webView;
    private String pagePath;
    private String scriptPath;
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        String token = "/reload" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        pagePath = token + "/page.html";
        scriptPath = token + "/script.js";
        onMain(() -> {
            context = new WebContext(getApplicationContext());
            webView = new WebView(context, true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }
            });
        });
    }

    @After
    public void tearDown() {
        onMain(() -> {
            if (webView != null)
                webView.destroy();
            if (context != null)
                context.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private void waitForPage(String url) throws InterruptedException {
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
    }

    private String loadPage() throws InterruptedException {
        HttpResponse script = new HttpResponse();
        script.setStatusCode(200);
        script.setStatusMessage("OK");
        script.addHeader("Content-Type", "text/javascript");
        script.addHeader("Cache-Control", "max-age=3600");
        script.addHeader("ETag", VERSION_TAG);
        script.setBody("var loaded = true;");
        httpServer.setResponse(scriptPath, script);

        String url =
            servePage(httpServer, pagePath, "<html><body><script src=\"" + scriptPath + "\"></script></body></html>");

        onMain(() -> webView.loadUrl(url));
        waitForPage(url);
        return url;
    }

    @Nullable
    private static String header(@NonNull HttpRequest request, @NonNull String name) {
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            if (header.getKey().equalsIgnoreCase(name))
                return header.getValue();
        }
        return null;
    }

    // The request for the script that the reload made.
    private HttpRequest reloadAndGetScriptRequest(String url, boolean bypassingCache) throws InterruptedException {
        HttpRequest before = httpServer.getLastRequest(scriptPath);
        onMain(() -> {
            if (bypassingCache)
                webView.reloadBypassingCache();
            else
                webView.reload();
        });
        waitForPage(url);
        HttpRequest request = httpServer.getLastRequest(scriptPath);
        assertNotNull("The page did not load the script", request);
        assertNotSame("The reload did not ask the server for the script", before, request);
        return request;
    }

    @Test
    public void reloadAsksWhetherTheCachedCopyChanged() throws InterruptedException {
        String url = loadPage();
        HttpRequest request = reloadAndGetScriptRequest(url, false);
        assertEquals("The reload did not ask whether the copy changed", VERSION_TAG, header(request, "If-None-Match"));
    }

    // Asserts that the request asked for a new copy, and not whether the cached one changed.
    private static void assertAsksForANewCopy(String what, HttpRequest request) {
        assertNull("The reload asked whether the copy of the " + what + " changed", header(request, "If-None-Match"));
        String cacheControl = header(request, "Cache-Control");
        assertTrue("The reload did not ask for a new copy of the " + what + ": " + cacheControl,
                   cacheControl != null && cacheControl.contains("no-cache"));
    }

    @Test
    public void reloadBypassingCacheAsksForNewCopies() throws InterruptedException {
        String url = loadPage();
        assertAsksForANewCopy("script", reloadAndGetScriptRequest(url, true));
        HttpRequest pageRequest = httpServer.getLastRequest(pagePath);
        assertNotNull(pageRequest);
        assertAsksForANewCopy("page", pageRequest);

        // A plain reload then finds the new copy in the cache.
        HttpRequest request = reloadAndGetScriptRequest(url, false);
        assertEquals("The new copy did not replace the old one in the cache", VERSION_TAG,
                     header(request, "If-None-Match"));
    }
}
