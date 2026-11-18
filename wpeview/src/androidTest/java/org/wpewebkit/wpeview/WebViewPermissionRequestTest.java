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

import android.net.Uri;

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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Pages that ask for permission, here for the location of the device: WebChromeClient.onPermissionRequest and
 * onPermissionRequestCanceled, the answers of WPEPermissionRequest, and the Permissions API query. The pages show what
 * they got in their title.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewPermissionRequestTest {
    private static final long TIMEOUT_SECONDS = 30;

    // Asks for the location when it loads, and shows the answer in its title: "error 1" when refused.
    private static final String LOCATE_PAGE =
        "<title>asking</title><script>navigator.geolocation.getCurrentPosition("
        + "p => { document.title = 'position ' + p.coords.latitude + ' ' + p.coords.longitude; },"
        + "e => { document.title = 'error ' + e.code; });</script>";
    // Shows in its title what navigator.permissions.query() says of the location.
    private static final String QUERY_PAGE =
        "<title>querying</title><script>navigator.permissions.query({name: 'geolocation'})"
        + ".then(s => { document.title = 'state ' + s.state; }, e => { document.title = 'query failed'; });</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> scenarioRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;

    private static class Client extends WebChromeClient {
        final BlockingQueue<WPEPermissionRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<WPEPermissionRequest> cancelled = new LinkedBlockingQueue<>();
        final BlockingQueue<WPEPermissionStateQuery> queries = new LinkedBlockingQueue<>();
        final BlockingQueue<String> titles = new LinkedBlockingQueue<>();
        // Whether the client keeps the requests and queries, for the test to answer, or leaves them to the default.
        private final boolean takes;

        Client(boolean takes) { this.takes = takes; }

        @Override
        public void onPermissionRequest(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
            if (takes)
                requests.add(request);
            else
                super.onPermissionRequest(view, request);
        }

        @Override
        public void onPermissionRequestCanceled(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
            cancelled.add(request);
        }

        @Override
        public boolean onQueryPermissionState(@NonNull WebView view, @NonNull WPEPermissionStateQuery query) {
            if (!takes)
                return super.onQueryPermissionState(view, query);
            queries.add(query);
            return true;
        }

        @Override
        public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
            titles.add(title);
        }
    }

    // The same, as the client of a page.
    private static class PageClient extends WebPageChromeClient {
        final BlockingQueue<WPEPermissionRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<String> titles = new LinkedBlockingQueue<>();

        @Override
        public void onPermissionRequest(@NonNull WebPage page, @NonNull WPEPermissionRequest request) {
            requests.add(request);
        }

        @Override
        public void onReceivedTitle(@NonNull WebPage page, @NonNull String title) {
            titles.add(title);
        }
    }

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
    }

    @After
    public void tearDown() {
        scenarioRule.getScenario().onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private void onMain(@NonNull Runnable task) { scenarioRule.getScenario().onActivity(activity -> task.run()); }

    private WebView getWebView() {
        WebView[] view = new WebView[1];
        scenarioRule.getScenario().onActivity(activity -> view[0] = activity.getWebView());
        return view[0];
    }

    private Client setClient(boolean takes) {
        Client client = new Client(takes);
        onMain(() -> getWebView().setWebChromeClient(client));
        return client;
    }

    private String setPage(String path, String body) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(body);
        return httpServer.setResponse(path, response);
    }

    private static String originOf(String url) {
        Uri uri = Uri.parse(url);
        return uri.getScheme() + "://" + uri.getEncodedAuthority();
    }

    private static <T> T take(BlockingQueue<T> queue, String what) throws InterruptedException {
        T item = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No " + what, item);
        return item;
    }

    // Waits for a title that starts with one of the prefixes, past those the page shows while it waits.
    private static String waitForTitle(BlockingQueue<String> titles, String... prefixes) throws InterruptedException {
        while (true) {
            String title = take(titles, "title starting with " + String.join(" or ", prefixes));
            for (String prefix : prefixes) {
                if (title.startsWith(prefix))
                    return title;
            }
        }
    }

    @Test
    public void requestHasTheOriginAndTheResource() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        WPEPermissionRequest request = take(client.requests, "permission request");
        assertEquals(originOf(url), request.getOrigin().toString());
        assertArrayEquals(new String[] {WPEPermissionRequest.RESOURCE_LOCATION}, request.getResources());

        onMain(request::deny);
        assertEquals("error 1", waitForTitle(client.titles, "error", "position"));
        assertNull(client.cancelled.poll());
    }

    @Test
    public void defaultClientDenies() throws Throwable {
        Client client = setClient(false);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        assertEquals("error 1", waitForTitle(client.titles, "error", "position"));
    }

    @Test
    public void grantLeavingOutAResourceDenies() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        WPEPermissionRequest request = take(client.requests, "permission request");
        onMain(() -> request.grant(new String[0]));
        assertEquals("error 1", waitForTitle(client.titles, "error", "position"));
    }

    // Only the first answer counts, and it may come from another thread.
    @Test
    public void grantsFromAnotherThread() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        WPEPermissionRequest request = take(client.requests, "permission request");
        Thread thread = new Thread(() -> {
            request.grant(new String[] {WPEPermissionRequest.RESOURCE_LOCATION});
            request.deny();
        });
        thread.start();
        thread.join();
        // Granted, the page waits for a position, which no source gives here.
        String title = waitForTitle(client.titles, "error", "position");
        assertEquals("error 2", title);
    }

    @Test
    public void navigationCancelsTheRequest() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/locate.html", LOCATE_PAGE);
        String next = setPage("/next.html", "<title>next</title>");
        onMain(() -> getWebView().loadUrl(url));
        WPEPermissionRequest request = take(client.requests, "permission request");

        onMain(() -> getWebView().loadUrl(next));
        assertSame(request, take(client.cancelled, "cancelled request"));
        waitForTitle(client.titles, "next");
        // A late answer does nothing.
        onMain(request::grant);
        assertNull(client.requests.poll(1, TimeUnit.SECONDS));
    }

    @Test
    public void endOfTheWebProcessCancelsTheRequest() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onMain(() -> getWebView().loadUrl(url));
        WPEPermissionRequest request = take(client.requests, "permission request");

        onMain(() -> getWebView().terminateWebProcess());
        assertSame(request, take(client.cancelled, "cancelled request"));
    }

    // A page whose address is not http or https has an origin the app could not show.
    @Test
    public void pageWithoutAWebAddressIsDeniedWithoutAsking() throws Throwable {
        Client client = setClient(true);
        onMain(() -> getWebView().loadHtml(LOCATE_PAGE, null));

        assertEquals("error 1", waitForTitle(client.titles, "error", "position"));
        assertNull(client.requests.poll());
    }

    // The view lets go of the page it made: its client hears that the request is gone, and the request waits for the
    // next client that the app sets, rather than being refused for the rest of the document.
    @Test
    public void releasedPageAsksItsNextClient() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onMain(() -> getWebView().loadUrl(url));
        WPEPermissionRequest request = take(client.requests, "permission request");

        WebPage[] page = new WebPage[1];
        onMain(() -> page[0] = getWebView().releasePage());
        assertNotNull(page[0]);
        assertSame(request, take(client.cancelled, "cancelled request"));

        PageClient pageClient = new PageClient();
        onMain(() -> {
            page[0].setChromeClient(pageClient);
            getWebView().setPage(page[0]);
        });
        WPEPermissionRequest again = take(pageClient.requests, "permission request asked again");
        assertEquals(originOf(url), again.getOrigin().toString());
        // The old request does nothing.
        onMain(request::grant);
        onMain(again::deny);
        assertEquals("error 1", waitForTitle(pageClient.titles, "error", "position"));
        onMain(() -> page[0].destroy());
    }

    @Test
    public void queryGetsTheAnswerOfTheClient() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/query.html", QUERY_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        WPEPermissionStateQuery query = take(client.queries, "permission query");
        assertEquals(originOf(url), query.getOrigin().toString());
        assertEquals(WPEPermissionRequest.RESOURCE_LOCATION, query.getResource());
        new Thread(() -> query.answer(WPEPermissionStateQuery.STATE_GRANTED)).start();
        assertEquals("state granted", waitForTitle(client.titles, "state", "query failed"));
    }

    @Test
    public void queryNotTakenSaysPrompt() throws Throwable {
        Client client = setClient(false);
        String url = setPage("/query.html", QUERY_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        assertEquals("state prompt", waitForTitle(client.titles, "state", "query failed"));
    }

    // WebKit does not let a page tell a refused site from a new one before it asks.
    @Test
    public void deniedReadsAsPromptBeforeThePageAsks() throws Throwable {
        Client client = setClient(true);
        String url = setPage("/query.html", QUERY_PAGE);
        onMain(() -> getWebView().loadUrl(url));

        WPEPermissionStateQuery query = take(client.queries, "permission query");
        onMain(() -> query.answer(WPEPermissionStateQuery.STATE_DENIED));
        assertEquals("state prompt", waitForTitle(client.titles, "state", "query failed"));
    }
}
