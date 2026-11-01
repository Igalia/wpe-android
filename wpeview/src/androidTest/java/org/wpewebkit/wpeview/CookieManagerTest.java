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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cookies are stored for the next session, as in a new process, unless the session is ephemeral, and removing all
 * cookies empties the store. A new WebContext starts a new session.
 */
@RunWith(AndroidJUnit4.class)
public class CookieManagerTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final List<WebContext> contexts = new ArrayList<>();
    private final List<WebView> webViews = new ArrayList<>();
    private String cookie;

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        // Other tests and earlier runs leave their cookies in the store.
        cookie = "test" + UUID.randomUUID().toString().replace("-", "") + "=1";
    }

    @After
    public void tearDown() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            for (WebView webView : webViews)
                webView.destroy();
            for (WebContext context : contexts)
                context.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    // A new session. On emulators, sessions are ephemeral by default, so the kind of session is given.
    private WebContext newSession(boolean ephemeral) {
        AtomicReference<WebContext> context = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(
            activity -> context.set(new WebContext(activity.getApplicationContext(), false, ephemeral)));
        contexts.add(context.get());
        return context.get();
    }

    private WebView newWebView(WebContext context) {
        AtomicReference<WebView> webView = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> webView.set(new WebView(context, true)));
        webViews.add(webView.get());
        return webView.get();
    }

    private void loadAndWait(WebView webView, String url) throws InterruptedException {
        BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }
            });
            webView.loadUrl(url);
        });
        String finished;
        do {
            finished = finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page did not finish loading: " + url, finished);
        } while (!finished.equals(url));
    }

    // Loads a page that sets the cookie, which lasts an hour, so that it is stored.
    private void setCookie(WebView webView) throws InterruptedException {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.addHeader("Set-Cookie", cookie + "; Max-Age=3600; Path=/");
        response.setBodyText("Sets a cookie");
        loadAndWait(webView, httpServer.setResponse("/set.html", response));
    }

    // Whether a page of the server has the cookie.
    private boolean hasCookie(WebView webView) throws InterruptedException {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBodyText("Reads the cookies");
        loadAndWait(webView, httpServer.setResponse("/read.html", response));

        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        webViewActivityTestRule.getScenario().onActivity(
            activity -> webView.evaluateJavascript("document.cookie", result::add));
        String cookies = result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No result for document.cookie", cookies);
        return cookies.contains(cookie);
    }

    @Test
    public void testCookiesAreKept() throws Exception {
        WebView first = newWebView(newSession(false));
        setCookie(first);
        assertTrue("The page did not set the cookie", hasCookie(first));

        assertTrue("The next session does not have the cookie", hasCookie(newWebView(newSession(false))));
    }

    @Test
    public void testRemoveAllCookiesEmptiesTheStore() throws Exception {
        WebContext session = newSession(false);
        WebView first = newWebView(session);
        setCookie(first);
        assertTrue("The page did not set the cookie", hasCookie(first));

        BlockingQueue<Boolean> removed = new LinkedBlockingQueue<>();
        webViewActivityTestRule.getScenario().onActivity(
            activity -> session.getCookieManager().removeAllCookies(removed::add));
        assertEquals(Boolean.TRUE, removed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse("The session still has the cookie", hasCookie(first));

        assertFalse("The next session has the removed cookie", hasCookie(newWebView(newSession(false))));
    }

    @Test
    public void testEphemeralSessionKeepsNoCookies() throws Exception {
        WebView ephemeral = newWebView(newSession(true));
        setCookie(ephemeral);
        assertTrue("The page did not set the cookie", hasCookie(ephemeral));

        assertFalse("The cookie of an ephemeral session was stored", hasCookie(newWebView(newSession(false))));
    }
}
