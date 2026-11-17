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

import androidx.annotation.NonNull;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tracking prevention keeps the cookies of a site from its frames in the pages of other sites, while it is on. The
 * pages are served over TLS, from localhost and from 127.0.0.1, which are two sites: cookies that other sites may get
 * must be secure.
 */
@RunWith(AndroidJUnit4.class)
public class WebContextTrackingPreventionTest {
    private static final long TIMEOUT_SECONDS = 30;
    // WebKit's WEBKIT_TLS_ERRORS_POLICY_IGNORE.
    private static final int TLS_ERRORS_POLICY_IGNORE = 0;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final List<WebContext> contexts = new ArrayList<>();
    private final List<WebView> webViews = new ArrayList<>();
    // In the names of this test's cookies and pages, since other tests and earlier runs leave their cookies in the
    // store.
    private String token;
    // How many times the test loaded a frame of the other site, which each load gives addresses of its own, so that the
    // requests of one load are never taken for those of another.
    private int frameLoads;

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.startSecure();
        token = "tracking" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (WebView webView : webViews)
                webView.destroy();
            for (WebContext context : contexts)
                context.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private void onMain(Runnable runnable) {
        webViewActivityTestRule.getScenario().onActivity(activity -> runnable.run());
    }

    // A new session, whose pages take the test server's certificate. On emulators, sessions are ephemeral by default,
    // so the kind of session is given.
    private WebContext newSession(boolean ephemeral) {
        AtomicReference<WebContext> context = new AtomicReference<>();
        onMain(() -> {
            context.set(new WebContext(getApplicationContext(), false, ephemeral));
            context.get().getWebKitNetworkSession().setTLSErrorsPolicy(TLS_ERRORS_POLICY_IGNORE);
        });
        contexts.add(context.get());
        return context.get();
    }

    private WebView newWebView(WebContext context) {
        AtomicReference<WebView> webView = new AtomicReference<>();
        onMain(() -> webView.set(new WebView(context, true)));
        webViews.add(webView.get());
        return webView.get();
    }

    private void setTrackingPrevention(WebContext context, boolean enabled) {
        onMain(() -> context.setTrackingPreventionEnabled(enabled));
    }

    private boolean isTrackingPreventionEnabled(WebContext context) {
        AtomicReference<Boolean> enabled = new AtomicReference<>();
        onMain(() -> enabled.set(context.isTrackingPreventionEnabled()));
        return enabled.get();
    }

    private CookieManager.CookieAcceptPolicy cookieAcceptPolicy(WebContext context) throws InterruptedException {
        BlockingQueue<CookieManager.CookieAcceptPolicy> policy = new LinkedBlockingQueue<>();
        onMain(() -> context.getCookieManager().getCookieAcceptPolicy(policy::add));
        CookieManager.CookieAcceptPolicy value = policy.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No cookie policy", value);
        return value;
    }

    private void loadAndWait(WebView webView, String url) throws InterruptedException {
        BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();
        onMain(() -> {
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

    private String evaluate(WebView webView, String script) throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        onMain(() -> webView.evaluateJavascript(script, result::add));
        String value = result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No result for " + script, value);
        return value;
    }

    private static HttpResponse page(String body, String... cookies) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        for (String cookie : cookies)
            response.addHeader("Set-Cookie", cookie);
        response.setBodyText(body);
        return response;
    }

    // The address of the server's page on the other site.
    private static String onOtherSite(String url) { return url.replace("://localhost:", "://127.0.0.1:"); }

    // The cookie that the other site sets when the user visits it, which it may get in the pages of other sites.
    private String siteCookie() { return token + "site=1"; }

    // The cookie that the other site tries to set from its frame in the page of another site.
    private String frameCookie() { return token + "frame=1"; }

    // The user visits the other site, which sets its cookie.
    private void visitOtherSite(WebView webView) throws InterruptedException {
        String url = onOtherSite(httpServer.setResponse(
            "/" + token + "/visit",
            page("The other site", siteCookie() + "; SameSite=None; Secure; Max-Age=3600; Path=/")));
        loadAndWait(webView, url);
        assertTrue("The other site did not set its cookie",
                   evaluate(webView, "document.cookie").contains(siteCookie()));
    }

    // The cookies that the other site gets in its frame in a page of this site: those that the request for the frame
    // sends, and those that the frame sees. The frame also tries to set a cookie.
    private static final class FrameCookies {
        final String sent;
        final String seen;

        FrameCookies(String sent, String seen) {
            this.sent = sent;
            this.seen = seen;
        }
    }

    private FrameCookies loadFrameOfOtherSite(WebView webView) throws InterruptedException {
        frameLoads++;
        String framePath = "/" + token + "/frame" + frameLoads;
        String frame = onOtherSite(
            httpServer.setResponse(framePath, page("<script>parent.postMessage(document.cookie, '*');</script>",
                                                   frameCookie() + "; SameSite=None; Secure; Max-Age=3600; Path=/")));
        String top =
            httpServer.setResponse("/" + token + "/top" + frameLoads,
                                   page("<script>window.frameCookies = null;"
                                        + "addEventListener('message', event => { window.frameCookies = event.data; });"
                                        + "</script><iframe src='" + frame + "'></iframe>"));
        loadAndWait(webView, top);
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        String seen = evaluate(webView, "window.frameCookies");
        while (seen.equals("null")) {
            assertTrue("The frame did not say which cookies it sees", SystemClock.uptimeMillis() < deadline);
            Thread.sleep(100);
            seen = evaluate(webView, "window.frameCookies");
        }
        HttpRequest request = httpServer.getLastRequest(framePath);
        assertNotNull("The frame was not requested", request);
        String sent = request.getHeaders().get("Cookie");
        return new FrameCookies(sent != null ? sent : "", seen);
    }

    private void assertOtherSiteGetsItsCookie(WebView webView) throws InterruptedException {
        FrameCookies cookies = loadFrameOfOtherSite(webView);
        assertTrue("The request for the frame did not send the cookie of its site: " + cookies.sent,
                   cookies.sent.contains(siteCookie()));
        assertTrue("The frame does not see the cookie of its site: " + cookies.seen,
                   cookies.seen.contains(siteCookie()));
    }

    private void assertOtherSiteGetsNoCookies(WebView webView) throws InterruptedException {
        FrameCookies cookies = loadFrameOfOtherSite(webView);
        assertFalse("The request for the frame sent the cookie of its site: " + cookies.sent,
                    cookies.sent.contains(siteCookie()));
        assertFalse("The frame sees the cookie of its site: " + cookies.seen, cookies.seen.contains(siteCookie()));
    }

    // The frame tries to set a cookie, which the other site does not have when the user visits it next.
    private void assertFrameSetsNoCookie(WebContext context, String when) throws InterruptedException {
        WebView webView = newWebView(context);
        loadFrameOfOtherSite(webView);
        String url = onOtherSite(httpServer.setResponse("/" + token + "/check" + frameLoads, page("The other site")));
        loadAndWait(webView, url);
        assertFalse("The frame of the other site set a cookie " + when,
                    evaluate(webView, "document.cookie").contains(frameCookie()));
    }

    @Test
    public void testIsOffUnlessTurnedOn() {
        for (boolean ephemeral : new boolean[] {false, true}) {
            WebContext context = newSession(ephemeral);
            assertFalse(isTrackingPreventionEnabled(context));
            setTrackingPrevention(context, true);
            assertTrue(isTrackingPreventionEnabled(context));
            setTrackingPrevention(context, false);
            assertFalse(isTrackingPreventionEnabled(context));
        }
    }

    // Turned on and off while the page is open, which the next loads follow.
    @Test
    public void testFramesOfOtherSitesGetNoCookiesWhileOn() throws Exception {
        WebContext context = newSession(false);
        WebView webView = newWebView(context);
        visitOtherSite(webView);
        assertOtherSiteGetsItsCookie(webView);

        setTrackingPrevention(context, true);
        assertOtherSiteGetsNoCookies(webView);

        setTrackingPrevention(context, false);
        assertOtherSiteGetsItsCookie(webView);
    }

    // On before the first page of the session loads.
    @Test
    public void testFramesOfOtherSitesGetNoCookiesInAnEphemeralSession() throws Exception {
        WebContext context = newSession(true);
        setTrackingPrevention(context, true);
        WebView webView = newWebView(context);
        visitOtherSite(webView);
        assertOtherSiteGetsNoCookies(webView);

        setTrackingPrevention(context, false);
        assertOtherSiteGetsItsCookie(webView);
    }

    // Neither while it is on nor while it is off, with the default cookie policy, which comes back when it goes off.
    @Test
    public void testFramesOfOtherSitesSetNoCookies() throws Exception {
        WebContext on = newSession(true);
        setTrackingPrevention(on, true);
        assertFrameSetsNoCookie(on, "while tracking prevention is on");

        assertFrameSetsNoCookie(newSession(true), "while tracking prevention is off");

        WebContext onThenOff = newSession(true);
        setTrackingPrevention(onThenOff, true);
        setTrackingPrevention(onThenOff, false);
        assertFrameSetsNoCookie(onThenOff, "after tracking prevention was on");
    }

    @Test
    public void testCookiePolicyReadsAsAcceptAlwaysWhileOn() throws Exception {
        WebContext context = newSession(true);
        assertEquals(CookieManager.CookieAcceptPolicy.AcceptNoThirdParty, cookieAcceptPolicy(context));
        setTrackingPrevention(context, true);
        assertEquals(CookieManager.CookieAcceptPolicy.AcceptAlways, cookieAcceptPolicy(context));
        setTrackingPrevention(context, false);
        assertEquals(CookieManager.CookieAcceptPolicy.AcceptNoThirdParty, cookieAcceptPolicy(context));
    }

    @Test
    public void testIsUsedOnTheMainThreadOnly() throws Exception {
        WebContext context = newSession(true);
        assertThrows(IllegalStateException.class, () -> context.setTrackingPreventionEnabled(true));
        assertThrows(IllegalStateException.class, context::isTrackingPreventionEnabled);
    }

    @Test
    public void testThrowsOnceTheContextIsDestroyed() {
        WebContext context = newSession(true);
        AtomicReference<Throwable> setting = new AtomicReference<>();
        AtomicReference<Throwable> getting = new AtomicReference<>();
        onMain(() -> {
            context.destroy();
            setting.set(assertThrows(IllegalStateException.class, () -> context.setTrackingPreventionEnabled(true)));
            getting.set(assertThrows(IllegalStateException.class, context::isTrackingPreventionEnabled));
        });
        assertNotNull(setting.get());
        assertNotNull(getting.get());
    }
}
