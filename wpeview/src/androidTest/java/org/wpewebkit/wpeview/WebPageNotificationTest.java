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
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * Notifications of pages: the requests for RESOURCE_NOTIFICATIONS, WebPageChromeClient.onShowNotification and
 * onCloseNotification, what WPENotification tells the page, and the answers new web processes start with. WebKit
 * refuses notifications in sessions that keep no data, so the pages are in a context that keeps its data. The pages
 * show what they got in their title.
 */
@RunWith(AndroidJUnit4.class)
public class WebPageNotificationTest {
    private static final long TIMEOUT_SECONDS = 30;

    // Shows the notification permission in its title as it loads, and keeps the notifications it shows in `shown`.
    private static final String PAGE = "<title>loaded</title><script>"
                                       + "var shown = [];"
                                       + "var showEvents = 0;"
                                       + "function show(title, options) {"
                                       + "  const n = new Notification(title, options);"
                                       + "  n.onshow = () => { showEvents++; document.title = 'show ' + title; };"
                                       + "  n.onclick = () => { document.title = 'click ' + title; };"
                                       + "  n.onclose = () => { document.title = 'close ' + title; };"
                                       + "  n.onerror = () => { document.title = 'error ' + title; };"
                                       + "  shown.push(n);"
                                       + "}"
                                       + "</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> scenarioRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private WebContext context;
    private WebPage page;
    // A view made with the context, for the tests of the page that a view makes.
    private WebView view;

    private static class Client extends WebPageChromeClient {
        final BlockingQueue<WPEPermissionRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<WPEPermissionStateQuery> queries = new LinkedBlockingQueue<>();
        final BlockingQueue<WPENotification> shown = new LinkedBlockingQueue<>();
        final BlockingQueue<WPENotification> closed = new LinkedBlockingQueue<>();
        final BlockingQueue<String> titles = new LinkedBlockingQueue<>();
        // Whether the client keeps the notifications, for the test, or leaves them to the default, which closes them.
        boolean takesNotifications = true;

        @Override
        public void onPermissionRequest(@NonNull WebPage page, @NonNull WPEPermissionRequest request) {
            requests.add(request);
        }

        @Override
        public boolean onQueryPermissionState(@NonNull WebPage page, @NonNull WPEPermissionStateQuery query) {
            queries.add(query);
            return true;
        }

        @Override
        public void onShowNotification(@NonNull WebPage page, @NonNull WPENotification notification) {
            if (takesNotifications)
                shown.add(notification);
            else
                super.onShowNotification(page, notification);
        }

        @Override
        public void onCloseNotification(@NonNull WebPage page, @NonNull WPENotification notification) {
            closed.add(notification);
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
            destroyPageAndContext();
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private void onMain(@NonNull Runnable task) { scenarioRule.getScenario().onActivity(activity -> task.run()); }

    private void destroyPageAndContext() {
        if (view != null && view.getInternalWebKitWebView() != null)
            view.destroy();
        view = null;
        if (page != null)
            page.destroy();
        page = null;
        if (context != null)
            context.destroy();
        context = null;
    }

    // A page in a context that keeps its data, unless it is ephemeral, shown in the view of the activity, with the
    // client. It takes the place of the page made before.
    private void makePage(@NonNull Client client, boolean ephemeral) {
        scenarioRule.getScenario().onActivity(activity -> {
            destroyPageAndContext();
            context = new WebContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), false, ephemeral);
            page = new WebPage(context);
            page.setChromeClient(client);
            activity.getWebView().setPage(page);
        });
    }

    private void makePage(@NonNull Client client) { makePage(client, false); }

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

    // Waits for a title that starts with one of the prefixes, past the others the page shows.
    private static String waitForTitle(BlockingQueue<String> titles, String... prefixes) throws InterruptedException {
        while (true) {
            String title = take(titles, "title starting with " + String.join(" or ", prefixes));
            for (String prefix : prefixes) {
                if (title.startsWith(prefix))
                    return title;
            }
        }
    }

    // Runs a script in the page, which WebKit runs as a user gesture, as Notification.requestPermission() needs.
    private void run(@NonNull String script) { onMain(() -> page.evaluateJavascript(script, null)); }

    private String loadPage(@NonNull Client client) throws InterruptedException {
        String url = setPage("/notify.html", PAGE);
        onMain(() -> page.loadUrl(url));
        waitForTitle(client.titles, "loaded");
        return url;
    }

    // Asks for permission, and has the client grant it.
    private void grant(@NonNull Client client) throws InterruptedException {
        run("Notification.requestPermission().then(p => { document.title = 'permission ' + p; })");
        WPEPermissionRequest request = take(client.requests, "permission request");
        onMain(request::grant);
        assertEquals("permission granted", waitForTitle(client.titles, "permission"));
    }

    // The chrome client of a view, for the page that the view makes.
    private static class ViewClient extends WebChromeClient {
        final BlockingQueue<WPEPermissionRequest> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<WPENotification> shown = new LinkedBlockingQueue<>();
        final BlockingQueue<WPENotification> closed = new LinkedBlockingQueue<>();
        final BlockingQueue<String> titles = new LinkedBlockingQueue<>();

        @Override
        public void onPermissionRequest(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
            requests.add(request);
        }

        @Override
        public void onShowNotification(@NonNull WebView view, @NonNull WPENotification notification) {
            shown.add(notification);
        }

        @Override
        public void onCloseNotification(@NonNull WebView view, @NonNull WPENotification notification) {
            closed.add(notification);
        }

        @Override
        public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
            titles.add(title);
        }
    }

    // The result of a script in the page, as JSON.
    private String evaluate(@NonNull String script) throws InterruptedException {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        onMain(() -> page.evaluateJavascript(script, result::add));
        return take(result, "result of " + script);
    }

    @Test
    public void requestHasTheOriginAndTheResource() throws Throwable {
        Client client = new Client();
        makePage(client);
        String url = loadPage(client);

        run("Notification.requestPermission().then(p => { document.title = 'permission ' + p; })");
        WPEPermissionRequest request = take(client.requests, "permission request");
        assertEquals(originOf(url), request.getOrigin().toString());
        assertArrayEquals(new String[] {WPEPermissionRequest.RESOURCE_NOTIFICATIONS}, request.getResources());
        onMain(request::deny);
        assertEquals("permission denied", waitForTitle(client.titles, "permission"));
    }

    @Test
    public void grantedPageShowsNotifications() throws Throwable {
        Client client = new Client();
        makePage(client);
        String url = loadPage(client);
        grant(client);
        run("document.title = 'now ' + Notification.permission");
        assertEquals("now granted", waitForTitle(client.titles, "now"));

        run("show('Hello', {body: 'Some text', tag: 'chat'})");
        WPENotification notification = take(client.shown, "notification");
        assertEquals(originOf(url), notification.getOrigin().toString());
        assertEquals("Hello", notification.getTitle());
        assertEquals("Some text", notification.getBody());
        assertEquals("chat", notification.getTag());
        assertNull(notification.getReplaced());
        assertEquals("show Hello", waitForTitle(client.titles, "show"));
    }

    // The app's clicks and closes reach the page, also from another thread, and the app does not hear of its own close.
    @Test
    public void clickAndCloseReachThePage() throws Throwable {
        Client client = new Client();
        makePage(client);
        loadPage(client);
        grant(client);
        run("show('Hello', {})");
        WPENotification notification = take(client.shown, "notification");
        assertNull(notification.getTag());

        Thread thread = new Thread(notification::click);
        thread.start();
        thread.join();
        assertEquals("click Hello", waitForTitle(client.titles, "click"));
        onMain(notification::close);
        assertEquals("close Hello", waitForTitle(client.titles, "close"));
        assertNull(client.closed.poll(1, TimeUnit.SECONDS));
        // A closed notification does nothing more.
        onMain(notification::click);
        run("document.title = 'after'");
        assertEquals("after", waitForTitle(client.titles, "after", "click"));
    }

    @Test
    public void pageClosesItsNotification() throws Throwable {
        Client client = new Client();
        makePage(client);
        loadPage(client);
        grant(client);
        run("show('Hello', {})");
        WPENotification notification = take(client.shown, "notification");

        run("shown[0].close()");
        assertSame(notification, take(client.closed, "closed notification"));
    }

    // A notification with the tag of an earlier one of the page takes its place: the app gets the earlier one with it,
    // and is not told that it closed.
    @Test
    public void notificationWithTheSameTagReplacesTheOldOne() throws Throwable {
        Client client = new Client();
        makePage(client);
        loadPage(client);
        grant(client);
        run("show('First', {tag: 'chat'})");
        WPENotification first = take(client.shown, "first notification");

        run("show('Second', {tag: 'chat'})");
        WPENotification second = take(client.shown, "second notification");
        assertEquals("Second", second.getTitle());
        assertSame(first, second.getReplaced());

        // Only one step back, so that the earlier ones are not kept.
        run("show('Third', {tag: 'chat'})");
        WPENotification third = take(client.shown, "third notification");
        assertSame(second, third.getReplaced());
        assertNull(second.getReplaced());
        assertNull(client.closed.poll(1, TimeUnit.SECONDS));
    }

    // No page would hear of the notifications of the old document.
    @Test
    public void newDocumentClosesTheNotifications() throws Throwable {
        Client client = new Client();
        makePage(client);
        loadPage(client);
        grant(client);
        run("show('Hello', {})");
        WPENotification notification = take(client.shown, "notification");

        String next = setPage("/next.html", "<title>next</title>");
        onMain(() -> page.loadUrl(next));
        assertSame(notification, take(client.closed, "closed notification"));
    }

    // The notifications stay with the app, which may keep them as a way back to the page, but reach no page.
    @Test
    public void endOfTheWebProcessLeavesTheNotificationsToTheApp() throws Throwable {
        Client client = new Client();
        makePage(client);
        loadPage(client);
        grant(client);
        run("show('Hello', {})");
        WPENotification notification = take(client.shown, "notification");

        onMain(() -> page.terminateWebProcess());
        assertNull(client.closed.poll(1, TimeUnit.SECONDS));
        onMain(() -> {
            notification.click();
            notification.close();
        });
    }

    // The page hears that its notification closed, and never that it showed.
    @Test
    public void defaultClientClosesTheNotification() throws Throwable {
        Client client = new Client();
        client.takesNotifications = false;
        makePage(client);
        loadPage(client);
        grant(client);

        run("show('Hello', {})");
        assertEquals("close Hello", waitForTitle(client.titles, "close", "show"));
    }

    @Test
    public void clientThatThrowsClosesTheNotification() throws Throwable {
        Client client = new Client() {
            @Override
            public void onShowNotification(@NonNull WebPage page, @NonNull WPENotification notification) {
                throw new IllegalStateException("Fails on purpose");
            }
        };
        makePage(client);
        loadPage(client);
        grant(client);

        // The exception goes on to the native side, which logs it.
        run("show('Hello', {})");
        assertEquals("close Hello", waitForTitle(client.titles, "close", "show"));
    }

    // A client may destroy the page as it is given a notification, and as it hears that one closed.
    @Test
    public void clientMayDestroyThePageInTheCallbacks() throws Throwable {
        BlockingQueue<Boolean> destroyed = new LinkedBlockingQueue<>();
        Client client = new Client() {
            @Override
            public void onShowNotification(@NonNull WebPage page, @NonNull WPENotification notification) {
                if ("Destroy on show".equals(notification.getTitle())) {
                    page.destroy();
                    destroyed.add(true);
                    return;
                }
                super.onShowNotification(page, notification);
            }

            @Override
            public void onCloseNotification(@NonNull WebPage page, @NonNull WPENotification notification) {
                page.destroy();
                destroyed.add(true);
            }
        };
        makePage(client);
        loadPage(client);
        grant(client);
        run("show('Destroy on close', {})");
        take(client.shown, "notification");
        run("shown[0].close()");
        take(destroyed, "page destroyed as the notification closed");

        Client second = new Client() {
            @Override
            public void onShowNotification(@NonNull WebPage page, @NonNull WPENotification notification) {
                page.destroy();
                destroyed.add(true);
            }
        };
        makePage(second);
        loadPage(second);
        grant(second);
        run("show('Destroy on show', {})");
        take(destroyed, "page destroyed as the notification showed");
    }

    // A view that lets go of the page it made tells its chrome client that the notifications closed, and the next
    // chrome client of the page is given them, with those the page showed meanwhile, of which only the newest wait.
    @Test
    public void releasedPageGivesItsNotificationsToTheNextClient() throws Throwable {
        ViewClient viewClient = new ViewClient();
        String url = setPage("/notify.html", PAGE);
        scenarioRule.getScenario().onActivity(activity -> {
            context = new WebContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), false, false);
            view = new WebView(activity, context);
            view.setWebChromeClient(viewClient);
            view.loadUrl(url);
        });
        waitForTitle(viewClient.titles, "loaded");
        onMain(()
                   -> view.evaluateJavascript(
                       "Notification.requestPermission().then(p => { document.title = 'permission ' + p; })", null));
        WPEPermissionRequest request = take(viewClient.requests, "permission request");
        onMain(request::grant);
        assertEquals("permission granted", waitForTitle(viewClient.titles, "permission"));
        onMain(() -> view.evaluateJavascript("show('Hello', {})", null));
        WPENotification shown = take(viewClient.shown, "notification");

        onMain(() -> page = view.releasePage());
        assertSame(shown, take(viewClient.closed, "closed notification"));
        int extra = WPENotification.MAX_WAITING + 1;
        for (int i = 1; i <= extra; i++)
            run("show('Waiting " + i + "', {})");
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!evaluate("showEvents").equals(String.valueOf(extra + 1)) && System.currentTimeMillis() < deadline)
            Thread.sleep(100);

        Client client = new Client();
        onMain(() -> page.setChromeClient(client));
        for (int i = extra - WPENotification.MAX_WAITING + 1; i <= extra; i++)
            assertEquals("Waiting " + i, take(client.shown, "waiting notification").getTitle());
        assertNull(client.shown.poll(1, TimeUnit.SECONDS));
        // The old object does nothing.
        onMain(shown::click);
        assertNull(client.closed.poll(1, TimeUnit.SECONDS));
    }

    // A new web process takes the answers the app set, without asking the app. A refused origin reads "default" until
    // its page asks.
    @Test
    public void startListsGiveNewWebProcessesTheirAnswers() throws Throwable {
        Client client = new Client();
        String url = setPage("/notify.html", PAGE);
        scenarioRule.getScenario().onActivity(activity -> {
            context = new WebContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), false, false);
            context.setNotificationPermissions(Collections.singletonList(Uri.parse(originOf(url))),
                                               Collections.emptyList());
            page = new WebPage(context);
            page.setChromeClient(client);
            activity.getWebView().setPage(page);
            page.loadUrl(url);
        });
        waitForTitle(client.titles, "loaded");
        run("document.title = 'now ' + Notification.permission");
        assertEquals("now granted", waitForTitle(client.titles, "now"));
        run("show('Hello', {})");
        assertEquals("Hello", take(client.shown, "notification").getTitle());
        assertNull(client.requests.poll());
    }

    @Test
    public void startListsTakeOnlyWebOrigins() throws Throwable {
        onMain(() -> {
            context = new WebContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), false, false);
            assertThrows(
                IllegalArgumentException.class,
                ()
                    -> context.setNotificationPermissions(
                        Collections.singletonList(Uri.parse("file:///sdcard/page.html")), Collections.emptyList()));
        });
    }

    @Test
    public void queryAboutNotificationsGoesToTheClient() throws Throwable {
        Client client = new Client();
        makePage(client);
        String url = loadPage(client);

        run("navigator.permissions.query({name: 'notifications'})"
            + ".then(s => { document.title = 'state ' + s.state; })");
        WPEPermissionStateQuery query = take(client.queries, "permission query");
        assertEquals(originOf(url), query.getOrigin().toString());
        assertEquals(WPEPermissionRequest.RESOURCE_NOTIFICATIONS, query.getResource());
        onMain(() -> query.answer(WPEPermissionStateQuery.STATE_GRANTED));
        assertEquals("state granted", waitForTitle(client.titles, "state"));
    }

    // WPEView shows no notifications of service workers yet, so pages find the method missing.
    @Test
    public void serviceWorkerNotificationsAreMissing() throws Throwable {
        Client client = new Client();
        makePage(client);
        loadPage(client);

        run("document.title = 'method ' + typeof ServiceWorkerRegistration.prototype.showNotification");
        assertEquals("method undefined", waitForTitle(client.titles, "method"));
    }

    // WebKit refuses notifications in a session that keeps no data, before the app hears.
    @Test
    public void sessionThatKeepsNoDataIsRefusedWithoutAsking() throws Throwable {
        Client client = new Client();
        makePage(client, true);
        loadPage(client);

        run("Notification.requestPermission().then(p => { document.title = 'permission ' + p; })");
        assertEquals("permission denied", waitForTitle(client.titles, "permission"));
        assertNull(client.requests.poll());
    }
}
