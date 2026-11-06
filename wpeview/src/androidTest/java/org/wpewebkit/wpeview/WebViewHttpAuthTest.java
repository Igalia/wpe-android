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
import org.wpewebkit.wpe.httpserver.HttpRequest;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pages that ask for a user name and password with HTTP authentication: WebViewClient.onReceivedHttpAuthRequest and
 * its handler. Each test has a server of its own, on a port of its own, so that the credentials a test gives are not
 * sent again in another one.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewHttpAuthTest {
    private static final long TIMEOUT_SECONDS = 30;
    private static final String REALM = "WPE test";
    private static final String UNAUTHORIZED_TITLE = "Unauthorized page";
    private static final String SIGNED_IN_TITLE = "Signed in page";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    // The loads that start and finish, in order.
    private final BlockingQueue<String> loadEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> httpErrors = new LinkedBlockingQueue<>();

    // A request for credentials, as the client got it.
    private static class AuthRequest {
        final WebViewClient.HttpAuthHandler handler;
        final String host;
        final String realm;

        AuthRequest(WebViewClient.HttpAuthHandler handler, String host, String realm) {
            this.handler = handler;
            this.host = host;
            this.realm = realm;
        }
    }

    // A client that takes the requests for credentials, which the test answers, or one that leaves them to the
    // default WebViewClient.
    private class Client extends WebViewClient {
        final BlockingQueue<AuthRequest> authRequests = new LinkedBlockingQueue<>();
        final BlockingQueue<HttpAuthHandler> cancelledAuthRequests = new LinkedBlockingQueue<>();
        private final boolean takesAuthRequests;

        Client(boolean takesAuthRequests) { this.takesAuthRequests = takesAuthRequests; }

        @Override
        public void onPageStarted(@NonNull WebView view, @NonNull String url) {
            loadEvents.add("started " + url);
        }

        @Override
        public void onPageFinished(@NonNull WebView view, @NonNull String url) {
            loadEvents.add("finished " + url);
        }

        @Override
        public void onReceivedHttpError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                        @NonNull WPEResourceResponse errorResponse) {
            httpErrors.add(errorResponse.getStatusCode());
        }

        @Override
        public void onReceivedHttpAuthRequest(@NonNull WebView view, @NonNull HttpAuthHandler handler,
                                              @NonNull String host, @NonNull String realm) {
            if (takesAuthRequests)
                authRequests.add(new AuthRequest(handler, host, realm));
            else
                super.onReceivedHttpAuthRequest(view, handler, host, realm);
        }

        @Override
        public void onHttpAuthRequestCanceled(@NonNull WebView view, @NonNull HttpAuthHandler handler) {
            cancelledAuthRequests.add(handler);
        }
    }

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
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

    private Client setClient(boolean takesAuthRequests) {
        Client client = new Client(takesAuthRequests);
        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().setWebViewClient(client));
        return client;
    }

    private static HttpResponse page(int statusCode, String statusMessage, String title) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setStatusMessage(statusMessage);
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody("<title>" + title + "</title><p>" + title);
        return response;
    }

    // A page that answers 401 until the test gives it another answer.
    private String setProtectedPage(String path) {
        HttpResponse response = page(401, "Unauthorized", UNAUTHORIZED_TITLE);
        response.addHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\"");
        return httpServer.setResponse(path, response);
    }

    private void setSignedInPage(String path) { httpServer.setResponse(path, page(200, "OK", SIGNED_IN_TITLE)); }

    private void loadUrl(String url) {
        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().loadUrl(url));
    }

    private AuthRequest waitForAuthRequest(Client client) throws InterruptedException {
        AuthRequest request = client.authRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No request for credentials", request);
        return request;
    }

    private void waitForLoadEvent(String expected) throws InterruptedException {
        String event;
        do {
            event = loadEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("No " + expected, event);
        } while (!event.equals(expected));
    }

    private void waitForPageFinished(String url) throws InterruptedException { waitForLoadEvent("finished " + url); }

    // The title of the page that is shown, which the view may not have reported yet when the load finishes.
    private String getTitle() throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        webViewActivityTestRule.getScenario().onActivity(
            activity -> activity.getWebView().evaluateJavascript("document.title", result::add));
        String title = result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The page gave no title", title);
        return title.replaceAll("^\"|\"$", "");
    }

    // An answer that is dropped sends nothing. One that went through would reach the server within this time.
    private void assertNoCredentialsSentLater(String path) throws InterruptedException {
        Thread.sleep(2000);
        assertNull(getAuthorization(path));
    }

    private String getAuthorization(String path) {
        HttpRequest request = httpServer.getLastRequest(path);
        assertNotNull("The server got no request for " + path, request);
        return request.getHeaders().get("Authorization");
    }

    @Test
    public void signsInWithTheCredentials() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        assertEquals(Uri.parse(url).getHost(), request.host);
        assertEquals(Uri.parse(url).getPort(), request.handler.getPort());
        assertEquals(REALM, request.realm);
        assertEquals("http", request.handler.getScheme());
        assertFalse(request.handler.isProxy());
        assertFalse(request.handler.isRetry());

        setSignedInPage("/protected.html");
        webViewActivityTestRule.getScenario().onActivity(activity -> request.handler.proceed("user", "passwd"));
        waitForPageFinished(url);
        assertEquals(SIGNED_IN_TITLE, getTitle());
        assertEquals("Basic dXNlcjpwYXNzd2Q=", getAuthorization("/protected.html"));
        assertTrue(client.authRequests.isEmpty());
        assertTrue(client.cancelledAuthRequests.isEmpty());
        assertTrue(httpErrors.isEmpty());
    }

    @Test
    public void asksAgainWhenTheCredentialsAreNotAccepted() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest first = waitForAuthRequest(client);
        assertFalse(first.handler.isRetry());
        webViewActivityTestRule.getScenario().onActivity(activity -> first.handler.proceed("user", "wrong"));

        AuthRequest second = waitForAuthRequest(client);
        assertTrue(second.handler.isRetry());
        assertEquals(REALM, second.realm);
        assertEquals("Basic dXNlcjp3cm9uZw==", getAuthorization("/protected.html"));

        setSignedInPage("/protected.html");
        webViewActivityTestRule.getScenario().onActivity(activity -> second.handler.proceed("user", "passwd"));
        waitForPageFinished(url);
        assertEquals(SIGNED_IN_TITLE, getTitle());
    }

    @Test
    public void cancelShowsTheUnauthorizedPage() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        webViewActivityTestRule.getScenario().onActivity(activity -> request.handler.cancel());
        waitForPageFinished(url);
        assertEquals(UNAUTHORIZED_TITLE, getTitle());
        assertEquals(Integer.valueOf(401), httpErrors.poll());
        assertNull(getAuthorization("/protected.html"));
        assertTrue(client.authRequests.isEmpty());
        assertTrue(client.cancelledAuthRequests.isEmpty());
    }

    // A page that asks for credentials used to load forever when the app did not answer.
    @Test
    public void theDefaultClientShowsTheUnauthorizedPage() throws Throwable {
        setClient(false);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        waitForPageFinished(url);
        assertEquals(UNAUTHORIZED_TITLE, getTitle());
        assertNull(getAuthorization("/protected.html"));
    }

    // Only the first answer counts.
    @Test
    public void aSecondAnswerIsDropped() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            request.handler.cancel();
            request.handler.proceed("user", "passwd");
        });
        waitForPageFinished(url);
        assertEquals(UNAUTHORIZED_TITLE, getTitle());
        assertNull(getAuthorization("/protected.html"));
    }

    @Test
    public void aSecondProceedIsDropped() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        setSignedInPage("/protected.html");
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            request.handler.proceed("user", "passwd");
            request.handler.proceed("user", "wrong");
        });
        waitForPageFinished(url);
        assertEquals(SIGNED_IN_TITLE, getTitle());
        assertEquals("Basic dXNlcjpwYXNzd2Q=", getAuthorization("/protected.html"));
        assertTrue(client.authRequests.isEmpty());
    }

    // An app may look the credentials up on a thread of its own and answer from there.
    @Test
    public void anAnswerFromAnotherThreadIsSent() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        setSignedInPage("/protected.html");
        Thread thread = new Thread(() -> request.handler.proceed("user", "passwd"));
        thread.start();
        thread.join();
        waitForPageFinished(url);
        assertEquals(SIGNED_IN_TITLE, getTitle());
        assertEquals("Basic dXNlcjpwYXNzd2Q=", getAuthorization("/protected.html"));
    }

    @Test
    public void aCancelFromAnotherThreadShowsTheUnauthorizedPage() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        Thread thread = new Thread(request.handler::cancel);
        thread.start();
        thread.join();
        waitForPageFinished(url);
        assertEquals(UNAUTHORIZED_TITLE, getTitle());
        assertNull(getAuthorization("/protected.html"));
    }

    // The credentials are kept for the session, and sent again to the pages of the same place on the server without
    // asking the app.
    @Test
    public void theCredentialsAreSentAgainWithoutAsking() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);

        AuthRequest request = waitForAuthRequest(client);
        setSignedInPage("/protected.html");
        webViewActivityTestRule.getScenario().onActivity(activity -> request.handler.proceed("user", "passwd"));
        waitForPageFinished(url);

        String otherUrl = httpServer.setResponse("/other.html", page(200, "OK", "Other page"));
        loadUrl(otherUrl);
        waitForPageFinished(otherUrl);
        assertEquals("Other page", getTitle());
        assertEquals("Basic dXNlcjpwYXNzd2Q=", getAuthorization("/other.html"));
        assertTrue(client.authRequests.isEmpty());
    }

    // Loading another page cancels the request, which the client is told, and the answer that comes after it is
    // dropped.
    @Test
    public void anAnswerAfterThePageWentElsewhereIsDropped() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);
        AuthRequest request = waitForAuthRequest(client);

        String otherUrl = httpServer.setResponse("/other.html", page(200, "OK", "Other page"));
        loadUrl(otherUrl);
        // The load that is cut short also ends with the new address, before the new load starts.
        waitForLoadEvent("started " + otherUrl);
        waitForPageFinished(otherUrl);
        assertEquals("Other page", getTitle());
        assertSame(request.handler, client.cancelledAuthRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        setSignedInPage("/protected.html");
        webViewActivityTestRule.getScenario().onActivity(activity -> request.handler.proceed("user", "passwd"));
        assertNoCredentialsSentLater("/protected.html");
        assertTrue(loadEvents.isEmpty());
        assertEquals("Other page", getTitle());
    }

    @Test
    public void stoppingTheLoadCancelsTheRequest() throws Throwable {
        Client client = setClient(true);
        loadUrl(setProtectedPage("/protected.html"));
        AuthRequest request = waitForAuthRequest(client);

        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().stopLoading());
        assertSame(request.handler, client.cancelledAuthRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        webViewActivityTestRule.getScenario().onActivity(activity -> request.handler.proceed("user", "passwd"));
        assertNoCredentialsSentLater("/protected.html");
    }

    // The load ends with the web process of the page, and so does its request. Loading the page again asks again.
    @Test
    public void theWebProcessEndingCancelsTheRequest() throws Throwable {
        Client client = setClient(true);
        String url = setProtectedPage("/protected.html");
        loadUrl(url);
        AuthRequest request = waitForAuthRequest(client);

        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().terminateWebProcess());
        assertSame(request.handler, client.cancelledAuthRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        webViewActivityTestRule.getScenario().onActivity(activity -> request.handler.proceed("user", "passwd"));
        assertNoCredentialsSentLater("/protected.html");

        loadUrl(url);
        AuthRequest again = waitForAuthRequest(client);
        assertFalse(again.handler.isRetry());
        setSignedInPage("/protected.html");
        webViewActivityTestRule.getScenario().onActivity(activity -> again.handler.proceed("user", "passwd"));
        waitForPageFinished(url);
        assertEquals(SIGNED_IN_TITLE, getTitle());
    }

    // Once the view is destroyed, an answer does nothing.
    @Test
    public void anAnswerAfterTheViewIsDestroyedIsDropped() throws Throwable {
        Client client = setClient(true);
        loadUrl(setProtectedPage("/protected.html"));
        AuthRequest request = waitForAuthRequest(client);

        webViewActivityTestRule.getScenario().onActivity(activity -> {
            activity.getWebView().destroy();
            request.handler.proceed("user", "passwd");
            request.handler.cancel();
        });
        assertNoCredentialsSentLater("/protected.html");
    }
}
