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

import android.net.http.SslCertificate;

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
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.wpewebkit.wpeview.WebPageTestHelper.TIMEOUT_SECONDS;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadHtml;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadUrl;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.servePage;

/**
 * The certificate of the server of a page, which {@link WebPage#getCertificate()} gives for https pages, and the view
 * that shows the page too. The test server's certificate, for localhost, is signed by no authority, so the session
 * ignores TLS errors.
 */
@RunWith(AndroidJUnit4.class)
public class WebPageCertificateTest {
    // WebKit's WEBKIT_TLS_ERRORS_POLICY_IGNORE.
    private static final int TLS_ERRORS_POLICY_IGNORE = 0;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private HttpServer httpsServer;
    private WebContext context;
    private final List<WebPage> pages = new ArrayList<>();
    private final List<WebView> views = new ArrayList<>();

    // Also tells when a load starts, before it commits, and when the web process ends.
    private static class Client extends WebPageTestHelper.RecordingClient {
        final BlockingQueue<String> startedUrls = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> terminations = new LinkedBlockingQueue<>();

        @Override
        public void onPageStarted(@NonNull WebPage page, @NonNull String url) {
            startedUrls.add(url);
        }

        @Override
        public void onWebProcessTerminated(@NonNull WebPage page, int reason) {
            terminations.add(reason);
        }
    }

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        httpsServer = HttpServer.startSecure();
        activityRule.getScenario().onActivity(activity -> {
            context = new WebContext(activity, false, true);
            context.getWebKitNetworkSession().setTLSErrorsPolicy(TLS_ERRORS_POLICY_IGNORE);
        });
    }

    // Also after a setUp that failed halfway, so that its failure is the one reported.
    @After
    public void tearDown() {
        onMain(() -> {
            for (WebView view : views)
                view.destroy();
            for (WebPage page : pages)
                page.destroy();
            if (context != null)
                context.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
        if (httpsServer != null)
            httpsServer.shutdown();
    }

    private WebPage page(Client client) {
        WebPage page = newPage(context, client);
        pages.add(page);
        return page;
    }

    private SslCertificate certificate(WebPage page) { return onMainGet(page::getCertificate); }

    // The test server's certificate: for localhost, by itself, until 24 October 2126.
    private static void assertTestCertificate(SslCertificate certificate) {
        assertNotNull("The https page has no certificate", certificate);
        assertEquals("localhost", certificate.getIssuedTo().getCName());
        assertEquals("localhost", certificate.getIssuedBy().getCName());
        Calendar validUntil = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        validUntil.setTime(certificate.getValidNotAfterDate());
        assertEquals(2126, validUntil.get(Calendar.YEAR));
        assertEquals(Calendar.OCTOBER, validUntil.get(Calendar.MONTH));
        assertNotNull(certificate.getX509Certificate());
    }

    @Test
    public void testNoCertificateBeforeTheFirstLoad() {
        assertNull(certificate(page(new Client())));
    }

    @Test
    public void testHttpsPageHasTheCertificateOfItsServer() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        loadUrl(page, client, servePage(httpsServer, "/secure", "<p>Secure</p>"));
        assertTestCertificate(certificate(page));
    }

    @Test
    public void testHttpPageHasNone() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        loadUrl(page, client, servePage(httpServer, "/plain", "<p>Plain</p>"));
        assertNull(certificate(page));
    }

    // Content that the app gives has no server, also with an https base address, and takes the place of the https
    // page before it.
    @Test
    public void testContentOfTheAppHasNone() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        loadUrl(page, client, servePage(httpsServer, "/secure", "<p>Secure</p>"));
        assertTestCertificate(certificate(page));
        client.finishedUrls.clear();
        onMain(() -> page.loadHtml("<p>From the app</p>", "https://localhost/"));
        client.waitForFinishedLoad();
        assertNull(certificate(page));

        loadUrl(page, client, servePage(httpsServer, "/secure", "<p>Secure</p>"));
        assertTestCertificate(certificate(page));
        loadHtml(page, client, "<p>From the app</p>");
        assertNull(certificate(page));
    }

    // Content that the app shows in place of a page keeps the page's https address, but did not come from its server.
    @Test
    public void testContentInPlaceOfAPageHasNone() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        String secureUrl = servePage(httpsServer, "/secure", "<p>Secure</p>");
        loadUrl(page, client, secureUrl);
        assertTestCertificate(certificate(page));
        client.finishedUrls.clear();
        onMain(() -> page.loadAlternateHtml("<p>From the app</p>", secureUrl, null));
        client.waitForFinishedLoad(secureUrl);
        assertEquals(secureUrl, onMainGet(page::getUrl));
        assertNull(certificate(page));
    }

    // The certificate is that of the page shown: it goes once an http page commits, and comes back with Back.
    @Test
    public void testCertificateFollowsTheCommittedPage() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        String secureUrl = servePage(httpsServer, "/secure", "<p>Secure</p>");
        loadUrl(page, client, secureUrl);

        CountDownLatch released = new CountDownLatch(1);
        HttpResponse slow = new HttpResponse();
        slow.setStatusCode(200);
        slow.setStatusMessage("OK");
        slow.addHeader("Content-Type", "text/html; charset=utf-8");
        slow.setBody("<p>Plain</p>");
        slow.holdUntil(released);
        String plainUrl = httpServer.setResponse("/plain", slow);
        try {
            client.startedUrls.clear();
            client.finishedUrls.clear();
            onMain(() -> page.loadUrl(plainUrl));
            assertNotNull("The load did not start", client.startedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTestCertificate(certificate(page));
        } finally {
            released.countDown();
        }
        client.waitForFinishedLoad(plainUrl);
        assertNull(certificate(page));

        client.finishedUrls.clear();
        onMain(page::goBack);
        client.waitForFinishedLoad(secureUrl);
        assertTestCertificate(certificate(page));
    }

    // The page keeps its address when its web process ends, until it loads again, and keeps its certificate too.
    @Test
    public void testCertificateStaysAfterTheWebProcessEnds() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        String secureUrl = servePage(httpsServer, "/secure", "<p>Secure</p>");
        loadUrl(page, client, secureUrl);
        onMain(page::terminateWebProcess);
        assertNotNull("The web process did not end", client.terminations.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(secureUrl, onMainGet(page::getUrl));
        assertTestCertificate(certificate(page));

        client.finishedUrls.clear();
        onMain(page::reload);
        client.waitForFinishedLoad(secureUrl);
        assertTestCertificate(certificate(page));

        // The first page that commits after the end of the process replaces it, also when it has none.
        onMain(page::terminateWebProcess);
        assertNotNull("The web process did not end", client.terminations.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        client.finishedUrls.clear();
        String plainUrl = servePage(httpServer, "/plain", "<p>Plain</p>");
        onMain(() -> page.loadUrl(plainUrl));
        client.waitForFinishedLoad(plainUrl);
        assertNull(certificate(page));
    }

    @Test
    public void testDestroyedPageHasNone() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        loadUrl(page, client, servePage(httpsServer, "/secure", "<p>Secure</p>"));
        onMain(page::destroy);
        assertNull(certificate(page));
    }

    @Test
    public void testViewGivesTheCertificateOfItsPage() throws Exception {
        Client client = new Client();
        WebPage page = page(client);
        loadUrl(page, client, servePage(httpsServer, "/secure", "<p>Secure</p>"));
        AtomicReference<WebView> made = new AtomicReference<>();
        activityRule.getScenario().onActivity(activity -> made.set(new WebView(activity, context)));
        WebView view = made.get();
        views.add(view);
        assertNull("A view whose page loaded nothing has no certificate", onMainGet(view::getCertificate));

        onMain(() -> view.setPage(page));
        assertTestCertificate(onMainGet(view::getCertificate));

        onMain(() -> view.setPage(null));
        assertNull("A view that shows no page has no certificate", onMainGet(view::getCertificate));
    }
}
