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

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.LocalNetworkAccess;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.evaluate;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadHtml;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.snapshot;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitForResult;

/**
 * A page apart from any view: it loads, runs, reports to its own clients and takes snapshots with no view, at the size
 * of the last view that showed a page, and a view shows it with {@link WebView#setPage}. The app owns the pages it
 * makes, and the view the page it made.
 */
@RunWith(AndroidJUnit4.class)
public class WebPageTest {
    private static final String SIZE = "innerWidth + 'x' + innerHeight + '@' + devicePixelRatio";
    private static final String VISIBILITY_STATE = "document.visibilityState";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private WebContext context;
    private final List<WebPage> pages = new ArrayList<>();

    @Before
    public void setUp() {
        activityRule.getScenario().onActivity(activity -> context = new WebContext(activity, false, true));
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (WebPage page : pages)
                page.destroy();
            pages.clear();
            context.destroy();
        });
    }

    private WebPage loadedPage(WebPageTestHelper.RecordingClient client, String html) throws InterruptedException {
        WebPage page = newPage(context, client);
        pages.add(page);
        loadHtml(page, client, html);
        return page;
    }

    private WebViewTestActivity activity() {
        WebViewTestActivity[] activity = new WebViewTestActivity[1];
        activityRule.getScenario().onActivity(current -> activity[0] = current);
        return activity[0];
    }

    private WebView activityWebView() { return activity().getWebView(); }

    // Loads a page in the WebView of the activity, so that it is the page that a view showed last, and returns its
    // size.
    private String showPageInActivity() throws Exception {
        TestWebViewClient client = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), client, "<html><body></body></html>");
        WebViewActivityScenarioHelper.waitForJavaScriptResult(activityRule.getScenario(), VISIBILITY_STATE,
                                                              "\"visible\"");
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(activityRule.getScenario(), client,
                                                                                SIZE);
    }

    /**
     * A page that no view showed lays out at the size of the page that a view showed last, at the scale of the screen,
     * not at 0 by 0, and it is hidden and out of any window.
     */
    @Test
    public void testPageWithoutViewHasTheSizeOfTheLastShownPage() throws Exception {
        String shownSize = showPageInActivity();
        assertFalse(shownSize, shownSize.startsWith("\"0x"));

        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, "<html><body></body></html>");
        assertEquals(shownSize, evaluate(page, SIZE));
        assertEquals("\"hidden\"", evaluate(page, VISIBILITY_STATE));
        assertEquals("false", evaluate(page, "document.hasFocus()"));
        assertNull(page.getView());
    }

    /** A page that no view showed takes snapshots of all three kinds, at its size. */
    @Test
    public void testPageWithoutViewTakesSnapshots() throws Exception {
        showPageInActivity();
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(
            client, "<html style='background:#ff0000'><body style='margin:0'><div style='height:300vh'></div></body>"
                        + "</html>");
        String[] size = evaluate(page, SIZE).replace("\"", "").split("[x@]");
        float scale = Float.parseFloat(size[2]);
        int width = Math.round(Integer.parseInt(size[0]) * scale);
        int height = Math.round(Integer.parseInt(size[1]) * scale);

        Bitmap visible = snapshot(page::captureSnapshot);
        assertNotNull("No snapshot of the visible part", visible);
        assertTrue(visible.getWidth() + "x" + visible.getHeight(),
                   Math.abs(visible.getWidth() - width) <= 2 && Math.abs(visible.getHeight() - height) <= 2);
        assertEquals(Color.RED, visible.getPixel(visible.getWidth() / 2, visible.getHeight() / 2) | 0xff000000);

        Bitmap document = snapshot(callback -> page.captureSnapshot(WebPage.SNAPSHOT_REGION_FULL_DOCUMENT, callback));
        assertNotNull("No snapshot of the document", document);
        assertTrue("The document is not three screens high: " + document.getHeight(),
                   document.getHeight() >= 3 * height - 6);

        Bitmap thumbnail =
            snapshot(callback -> page.captureSnapshot(WebPage.SNAPSHOT_REGION_VISIBLE, 100, 100, callback));
        assertNotNull("No thumbnail", thumbnail);
        assertTrue(thumbnail.getWidth() + "x" + thumbnail.getHeight(),
                   thumbnail.getWidth() <= 100 && thumbnail.getHeight() <= 100);
    }

    /**
     * A page shown in a view takes its size, its visibility and its focus, and keeps that size once it leaves it,
     * hidden again.
     */
    @Test
    public void testPageTakesTheSizeOfItsViewAndKeepsIt() throws Exception {
        showPageInActivity();
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, "<html><body></body></html>");
        String sizeWithoutView = evaluate(page, SIZE);

        // A view a quarter as large as the window.
        WebView[] view = new WebView[1];
        activityRule.getScenario().onActivity(activity -> {
            ViewGroup root = (ViewGroup)activity.getWebView().getParent();
            view[0] = new WebView(activity, context);
            root.removeAllViews();
            FrameLayout frame = new FrameLayout(activity);
            frame.addView(view[0], new FrameLayout.LayoutParams(root.getWidth() / 2, root.getHeight() / 2));
            root.addView(frame);
            view[0].requestFocus();
            view[0].setPage(page);
        });
        try {
            assertEquals("\"visible\"", waitForResult(page, VISIBILITY_STATE, "\"visible\""));
            assertEquals("true", waitForResult(page, "document.hasFocus()", "true"));
            String sizeInView = evaluate(page, SIZE);
            assertFalse(sizeInView + " " + sizeWithoutView, sizeInView.equals(sizeWithoutView));
            assertSame(view[0], page.getView());
            assertSame(page, view[0].getPage());

            onMain(() -> view[0].setPage(null));
            assertEquals("\"hidden\"", waitForResult(page, VISIBILITY_STATE, "\"hidden\""));
            assertEquals("false", waitForResult(page, "document.hasFocus()", "false"));
            assertEquals(sizeInView, evaluate(page, SIZE));
            assertNull(page.getView());
        } finally {
            onMain(() -> view[0].destroy());
        }
    }

    /** A page reports its loads, title, progress and history to its own clients, with or without a view. */
    @Test
    public void testPageReportsToItsClients() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient() {
            @Override
            public void onPageStarted(@NonNull WebPage page, @NonNull String url) {
                events.add("started");
            }

            @Override
            public void doUpdateVisitedHistory(@NonNull WebPage page, @NonNull String url, boolean isReload) {
                events.add("history");
            }
        };
        WebPage page = newPage(context, client);
        pages.add(page);
        onMain(() -> page.setChromeClient(new WebPageChromeClient() {
            @Override
            public void onReceivedTitle(@NonNull WebPage source, @NonNull String title) {
                events.add("title:" + title);
            }

            @Override
            public void onProgressChanged(@NonNull WebPage source, int newProgress) {
                if (newProgress == 100)
                    events.add("progress");
            }
        }));

        HttpServer server = HttpServer.start();
        try {
            WebPageTestHelper.loadUrl(
                page, client, WebPageTestHelper.servePage(server, "/first", "<title>First</title><p>First</p>"));
            WebPageTestHelper.loadUrl(
                page, client, WebPageTestHelper.servePage(server, "/second", "<title>Second</title><p>Second</p>"));
        } finally {
            server.shutdown();
        }
        // The title may come after the load finished.
        List<String> received = new ArrayList<>();
        for (int i = 0; i < 100 && !received.contains("title:Second"); i++) {
            String event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null)
                received.add(event);
        }
        events.drainTo(received);
        assertTrue(received.toString(), received.contains("started"));
        assertTrue(received.toString(), received.contains("history"));
        assertTrue(received.toString(), received.contains("title:First"));
        assertTrue(received.toString(), received.contains("title:Second"));
        assertTrue(received.toString(), received.contains("progress"));
        assertEquals("Second", onMainGet(page::getTitle));
        assertTrue(onMainGet(page::canGoBack));
    }

    private static class RecordingViewClient extends WebViewClient {
        final BlockingQueue<String> finishedLoads = new LinkedBlockingQueue<>();

        @Override
        public void onPageFinished(@NonNull WebView view, @NonNull String url) {
            finishedLoads.add(url);
        }
    }

    /** The view gives the events of a page that the app gave it to the page's clients, not to its own. */
    @Test
    public void testViewClientsDoNotGetTheEventsOfAnAppPage() throws Exception {
        RecordingViewClient viewClient = new RecordingViewClient();
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setWebViewClient(viewClient));
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        WebView view = activityWebView();
        onMain(() -> view.setPage(page));
        loadHtml(page, client, "<html><body></body></html>");
        assertTrue("The view's client got the load of the app's page", viewClient.finishedLoads.isEmpty());
    }

    /**
     * A page with no view gets its load errors, also the one of a connection that Android blocked for lack of the
     * local network permission, which needs no activity.
     */
    @Test
    public void testErrorsOfAPageWithoutView() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        String refused = "http://127.0.0.1:" + WebPageTestHelper.closedPort() + "/";
        onMain(() -> page.loadUrl(refused));
        WPEResourceError error = client.waitForError();
        assertEquals(error.getDescription(), WebViewClient.ERROR_CONNECT, error.getErrorCode());

        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("The app has the local network permission",
                   appContext.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                       PackageManager.PERMISSION_GRANTED);
        // Only this test uses this address: LocalNetworkAccess remembers it for a while. A page of its own, as the
        // first one may still be loading the error page of its failed load.
        String host = "127.0.0.4";
        LocalNetworkAccess.getInstance().onBlocked(host);
        WebPageTestHelper.RecordingClient blockedClient = new WebPageTestHelper.RecordingClient();
        WebPage blockedPage = newPage(context, blockedClient);
        pages.add(blockedPage);
        String blocked = "http://" + host + ":" + WebPageTestHelper.closedPort() + "/";
        onMain(() -> blockedPage.loadUrl(blocked));
        error = blockedClient.waitForError();
        assertEquals(error.getDescription(), WebViewClient.ERROR_LOCAL_NETWORK_ACCESS_DENIED, error.getErrorCode());
    }

    /** Showing another page destroys the page that the view made, since nothing else can reach it. */
    @Test
    public void testSetPageDestroysThePageTheViewMade() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        WebView view = activityWebView();
        WebPage ownPage = onMainGet(view::getPage);
        assertNotNull(ownPage);
        assertSame(view, onMainGet(ownPage::getView));
        assertNotNull(onMainGet(ownPage::saveState));

        onMain(() -> view.setPage(page));
        assertNull("The page the view made is not destroyed", onMainGet(ownPage::saveState));
        assertNull(onMainGet(ownPage::getView));
        assertSame(page, onMainGet(view::getPage));
        loadHtml(page, client, "<html><body></body></html>");
    }

    /** Destroying a view takes the app's page out of it, and the page goes on. */
    @Test
    public void testDestroyingTheViewKeepsTheAppPage() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, "<html><body></body></html>");
        WebView view = activityWebView();
        onMain(() -> view.setPage(page));
        onMain(view::destroy);
        assertNull(onMainGet(page::getView));
        assertNull(onMainGet(view::getPage));
        assertEquals("2", evaluate(page, "1 + 1"));
        assertThrows(IllegalStateException.class, () -> onMain(() -> view.setPage(page)));
    }

    /** Destroying a page takes it out of its view, which then shows no page and can show another. */
    @Test
    public void testDestroyingThePageTakesItOutOfItsView() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, "<html><body></body></html>");
        WebView view = activityWebView();
        onMain(() -> view.setPage(page));
        onMain(page::destroy);
        assertNull(onMainGet(view::getPage));
        assertNull(onMainGet(page::getView));
        assertNull(onMainGet(page::saveState));

        WebPage other = loadedPage(client, "<html><body></body></html>");
        onMain(() -> view.setPage(other));
        assertEquals("\"visible\"", waitForResult(other, VISIBILITY_STATE, "\"visible\""));
    }

    /** A page is in one view at most, and a destroyed page in none. */
    @Test
    public void testSetPageRefusesPagesItCannotShow() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        WebView view = activityWebView();
        WebView[] other = new WebView[1];
        activityRule.getScenario().onActivity(activity -> {
            other[0] = new WebView(activity, context);
            other[0].setPage(page);
        });
        try {
            assertThrows(IllegalStateException.class, () -> onMain(() -> view.setPage(page)));
            assertSame(other[0], onMainGet(page::getView));
            onMain(page::destroy);
            assertThrows(IllegalStateException.class, () -> onMain(() -> view.setPage(page)));
        } finally {
            onMain(() -> other[0].destroy());
        }
    }

    /** The background color belongs to the page, and a view paints its window with the color of the page it shows. */
    @Test
    public void testViewTakesTheBackgroundColorOfItsPage() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        WebView view = activityWebView();
        onMain(() -> {
            page.setBackgroundColor(Color.RED);
            view.setPage(page);
        });
        assertEquals(Color.RED, onMainGet(() -> ((ColorDrawable)view.getBackground()).getColor()).intValue());
        onMain(() -> view.setBackgroundColor(Color.GREEN));
        assertEquals(Color.GREEN, onMainGet(page::getBackgroundColor).intValue());
        assertThrows(IllegalArgumentException.class, () -> onMain(() -> page.setBackgroundColor(0x80ff0000)));
    }

    // Destroys a page, a view that showed it and their context in the given order.
    private void destroyInOrder(String order) {
        WebViewTestActivity activity = activity();
        onMain(() -> {
            WebContext ownContext =
                new WebContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), false, true);
            WebPage page = new WebPage(ownContext);
            WebView[] view = new WebView[] {new WebView(activity, ownContext)};
            WebPage viewPage = view[0].getPage();
            view[0].setPage(page);
            for (char step : order.toCharArray()) {
                if (step == 'p')
                    page.destroy();
                else if (step == 'v')
                    view[0].destroy();
                else
                    ownContext.destroy();
            }
            assertNull(order, page.saveState());
            assertNull(order, page.getView());
            assertNull(order, view[0].getPage());
            assertNull(order, viewPage.saveState());
        });
    }

    /** Pages, views and contexts can be destroyed in any order, and a context destroys its pages first. */
    @Test
    public void testEveryOrderOfDestroying() {
        for (String order : new String[] {"pvc", "pcv", "vpc", "vcp", "cpv", "cvp"})
            destroyInOrder(order);
    }

    /** A page cannot be made with a destroyed context. */
    @Test
    public void testPageOfADestroyedContext() {
        onMain(() -> {
            WebContext ownContext =
                new WebContext(InstrumentationRegistry.getInstrumentation().getTargetContext(), false, true);
            ownContext.destroy();
            assertThrows(IllegalStateException.class, () -> new WebPage(ownContext));
        });
    }

    /**
     * A page opens a window through the request that its chrome client gets: the window opens if the client made its
     * page, which takes the size of its opener and its clients before WebKit loads into it. The request makes no page
     * once the call returned.
     */
    @Test
    public void testPageOpensAWindow() throws Exception {
        showPageInActivity();
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage opener = loadedPage(client, "<html><head><title>Opener</title></head></html>");
        WebPageTestHelper.RecordingClient windowClient = new WebPageTestHelper.RecordingClient();
        List<WebPage> windows = new ArrayList<>();
        List<WebPageChromeClient.WindowRequest> requests = new ArrayList<>();
        onMain(() -> opener.setChromeClient(new WebPageChromeClient() {
            @Override
            public void onCreateWindow(@NonNull WebPage source, @NonNull WindowRequest request) {
                requests.add(request);
                WebPage window = request.createPage();
                window.setClient(windowClient);
                windows.add(window);
                pages.add(window);
                assertThrows(IllegalStateException.class, request::createPage);
            }
        }));
        assertEquals("true", evaluate(opener, "opened = window.open('about:blank#window'); opened !== null"));
        assertEquals(1, windows.size());
        WebPage window = windows.get(0);
        assertTrue(requests.get(0).isUserGesture());
        windowClient.waitForFinishedLoad();
        assertEquals("true", evaluate(window, "window.opener !== null"));
        assertEquals("\"Opener\"", evaluate(window, "window.opener.document.title"));
        assertEquals(evaluate(opener, SIZE), evaluate(window, SIZE));
        assertThrows(IllegalStateException.class, () -> onMain(() -> requests.get(0).createPage()));
    }

    /** A chrome client that makes no page opens no window, and window.open() returns null. */
    @Test
    public void testPageOpensNoWindowByDefault() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage opener = loadedPage(client, "<html><body></body></html>");
        assertEquals("true", evaluate(opener, "window.open('about:blank') === null"));
    }

    /**
     * A view that shows no page: its methods that do something do nothing, those that return a value return what a
     * new blank page would, and those that return an object of a page or a context throw.
     */
    @Test
    public void testViewWithoutPage() throws Exception {
        WebViewTestActivity activity = activity();
        WebView view = onMainGet(() -> WebView.createEmpty(activity));
        try {
            onMain(() -> {
                assertNull(view.getPage());
                assertEquals("about:blank", view.getUrl());
                assertEquals("", view.getTitle());
                assertNull(view.getOriginalUrl());
                assertEquals(0, view.getProgress());
                assertFalse(view.canGoBack());
                assertNull(view.saveState());
                assertFalse(view.restoreState(new byte[] {1}));
                view.loadUrl("about:blank");
                view.evaluateJavascript("1",
                                        result -> { throw new AssertionError("A view with no page ran a script"); });
                assertThrows(IllegalStateException.class, view::getSettings);
                assertThrows(IllegalStateException.class, view::getCookieManager);
            });
            assertNull(snapshot(callback -> view.captureSnapshot(callback::onResult)));

            WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
            WebPage page = loadedPage(client, "<html><body></body></html>");
            onMain(() -> {
                view.setPage(page);
                assertSame(page.getSettings(), view.getSettings());
                assertSame(page.getWebContext().getCookieManager(), view.getCookieManager());
            });
        } finally {
            onMain(view::destroy);
        }
    }

    /**
     * The page that a view made, destroyed on its own or with its context, stays the view's page, as when the view is
     * destroyed, so that the methods of the view give its last values.
     */
    @Test
    public void testViewKeepsItsOwnDestroyedPage() throws Exception {
        TestWebViewClient client = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), client,
                                                   "<html><head><title>Kept</title></head></html>");
        WebView view = activityWebView();
        WebPage ownPage = onMainGet(view::getPage);
        WebPageTestHelper.waitFor("The title did not come", () -> "Kept".equals(view.getTitle()));
        onMain(() -> {
            ownPage.destroy();
            assertSame(ownPage, view.getPage());
            assertTrue(ownPage.isDestroyed());
            assertEquals("Kept", view.getTitle());
            assertNotNull(view.getSettings());
            assertNull(view.saveState());
        });
    }

    /**
     * releasePage() hands the page that the view made to the app, with the context that the view made: the page goes
     * on with clients that do nothing, the requests that the view's clients held are cancelled, the adapters that the
     * app may have kept no longer reach the view, and the context ends with the page.
     */
    @Test
    public void testReleasePageHandsTheOwnPageOver() throws Exception {
        WebViewTestActivity activity = activity();
        RecordingViewClient viewClient = new RecordingViewClient();
        List<WPEJsResult> heldDialogs = new ArrayList<>();
        WebView view = onMainGet(() -> {
            WebView made = new WebView(activity);
            made.setWebViewClient(viewClient);
            made.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsConfirm(@NonNull WebView source, String url, String message,
                                           @NonNull WPEJsResult result) {
                    heldDialogs.add(result);
                    return true;
                }
            });
            made.loadHtml("<html><body></body></html>", null);
            return made;
        });
        try {
            viewClient.finishedLoads.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS);
            WebPage page = onMainGet(view::getPage);
            WebPageClient adapter = onMainGet(page::getClient);
            BlockingQueue<String> confirmed = new LinkedBlockingQueue<>();
            onMain(() -> page.evaluateJavascript("confirm('Held')", confirmed::add));
            WebPageTestHelper.waitFor("The view's chrome client did not get the dialog", () -> !heldDialogs.isEmpty());

            WebPage released = onMainGet(view::releasePage);
            assertSame(page, released);
            pages.add(page);
            assertEquals("false", confirmed.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS));
            onMain(() -> {
                assertNull(view.getPage());
                assertNull(page.getView());
                assertFalse(page.getClient() == adapter);
                assertThrows(IllegalStateException.class, view::getCookieManager);
            });
            WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
            onMain(() -> page.setClient(client));
            loadHtml(page, client, "<html><body></body></html>");

            // An adapter that the app set back passes nothing to the view's client any more.
            viewClient.finishedLoads.clear();
            onMain(() -> {
                page.setClient(adapter);
                page.loadHtml("<html><body>Again</body></html>", null);
            });
            assertEquals("\"Again\"", waitForResult(page, "document.body.textContent", "\"Again\""));
            Thread.sleep(500);
            assertTrue("The view's client got a load of the released page", viewClient.finishedLoads.isEmpty());

            WebContext ownContext = onMainGet(page::getWebContext);
            onMain(page::destroy);
            assertTrue("The context that the view made did not end with its page", onMainGet(ownContext::isDestroyed));
        } finally {
            onMain(view::destroy);
        }
    }

    /**
     * releasePage() tells the view's clients that the requests it cancels for them are gone, as when the page ends
     * them, so that they close what they show for them: a request for HTTP authentication, and a color field.
     */
    @Test
    public void testReleasePageTellsTheViewClientsOfTheRequestsItCancels() throws Exception {
        checkViewClientsHearOfTheRequestsOfTheOwnPage(view -> pages.add(view.releasePage()), true);
    }

    /** Showing another page tells the view's clients that the requests of the page it made are gone, as it goes. */
    @Test
    public void testShowingAnotherPageTellsTheViewClientsOfTheRequestsOfTheOwnPage() throws Exception {
        checkViewClientsHearOfTheRequestsOfTheOwnPage(view -> {
            WebPage other = new WebPage(context);
            pages.add(other);
            view.setPage(other);
        }, true);
    }

    /** Destroying the page that the view made tells the view's clients that its requests are gone. */
    @Test
    public void testDestroyingTheOwnPageTellsTheViewClientsOfItsRequests() throws Exception {
        checkViewClientsHearOfTheRequestsOfTheOwnPage(view -> view.getPage().destroy(), true);
    }

    /** Destroying the context of the page that the view made tells the view's clients that its requests are gone. */
    @Test
    public void testDestroyingTheContextTellsTheViewClientsOfTheRequestsOfTheOwnPage() throws Exception {
        checkViewClientsHearOfTheRequestsOfTheOwnPage(view -> view.getPage().getWebContext().destroy(), true);
    }

    /** The clients of a view that is destroyed do not hear of the requests of its page. */
    @Test
    public void testDestroyingTheViewDoesNotTellItsClientsOfTheRequestsOfItsPage() throws Exception {
        checkViewClientsHearOfTheRequestsOfTheOwnPage(WebView::destroy, false);
    }

    // The view lets go of the page it made with letGo while the page has a request of the view's client for HTTP
    // authentication, then while it has one of the view's chrome client for a color field. The clients hear that each
    // is gone if told. Each view has a context of its own, which letGo may destroy.
    private void checkViewClientsHearOfTheRequestsOfTheOwnPage(Consumer<WebView> letGo, boolean told) throws Exception {
        WebViewTestActivity activity = activity();
        BlockingQueue<WebViewClient.HttpAuthHandler> authRequests = new LinkedBlockingQueue<>();
        BlockingQueue<WebViewClient.HttpAuthHandler> canceledAuthRequests = new LinkedBlockingQueue<>();
        AtomicInteger colorRequests = new AtomicInteger();
        AtomicInteger hiddenColorRequests = new AtomicInteger();
        WebViewClient viewClient = new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(@NonNull WebView view, @NonNull HttpAuthHandler handler,
                                                  @NonNull String host, @NonNull String realm) {
                authRequests.add(handler);
            }

            @Override
            public void onHttpAuthRequestCanceled(@NonNull WebView view, @NonNull HttpAuthHandler handler) {
                canceledAuthRequests.add(handler);
            }
        };
        WebChromeClient chromeClient = new WebChromeClient() {
            @Override
            public boolean onShowColorChooser(@NonNull WebView view, @NonNull ColorChooserParams params,
                                              @NonNull ColorChooserCallback callback) {
                colorRequests.incrementAndGet();
                return true;
            }

            @Override
            public void onHideColorChooser(@NonNull WebView view) {
                hiddenColorRequests.incrementAndGet();
            }
        };
        HttpServer server = HttpServer.start();
        try {
            HttpResponse unauthorized = new HttpResponse();
            unauthorized.setStatusCode(401);
            unauthorized.setStatusMessage("Unauthorized");
            unauthorized.addHeader("WWW-Authenticate", "Basic realm=\"Test\"");
            unauthorized.addHeader("Content-Type", "text/html; charset=utf-8");
            unauthorized.setBody("<p>Unauthorized");
            String protectedUrl = server.setResponse("/protected.html", unauthorized);
            WebContext authContext = onMainGet(() -> new WebContext(activity, false, true));
            WebView authView = onMainGet(() -> {
                WebView made = new WebView(activity, authContext);
                made.setWebViewClient(viewClient);
                made.loadUrl(protectedUrl);
                return made;
            });
            try {
                WebViewClient.HttpAuthHandler handler =
                    authRequests.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull("The view's client got no request for HTTP authentication", handler);
                onMain(() -> letGo.accept(authView));
                if (told)
                    assertSame(handler, canceledAuthRequests.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS));
                else
                    assertNull(canceledAuthRequests.poll(1, TimeUnit.SECONDS));
            } finally {
                onMain(() -> {
                    authView.destroy();
                    authContext.destroy();
                });
            }
        } finally {
            server.shutdown();
        }

        WebContext colorContext = onMainGet(() -> new WebContext(activity, false, true));
        WebView colorView = onMainGet(() -> {
            WebView made = new WebView(activity, colorContext);
            made.setWebChromeClient(chromeClient);
            return made;
        });
        try {
            WebPage page = onMainGet(colorView::getPage);
            WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
            onMain(() -> page.setClient(client));
            loadHtml(page, client, "<input id=color type=color>");
            // A script that the app runs counts as a user gesture, which a color field needs to open.
            evaluate(page, "document.getElementById('color').click()");
            WebPageTestHelper.waitFor("The view's chrome client got no color request", () -> colorRequests.get() == 1);
            onMain(() -> letGo.accept(colorView));
            assertEquals(told ? 1 : 0, hiddenColorRequests.get());
        } finally {
            onMain(() -> {
                colorView.destroy();
                colorContext.destroy();
            });
        }
    }

    /**
     * The view's clients may destroy the page that the view made, and show another, as they hear that its requests are
     * gone because it is destroyed: they still hear of the others.
     */
    @Test
    public void testViewClientsMayDestroyTheOwnPageAsTheyHearOfItsRequests() throws Exception {
        WebPage other = onMainGet(() -> new WebPage(context));
        pages.add(other);
        WebPage[] ownPage = new WebPage[1];
        checkViewClientsMayActAsTheyHearOfRequests(view -> {
            ownPage[0] = view.getPage();
            view.getPage().destroy();
        }, view -> view.setPage(other), true);
        assertTrue(onMainGet(() -> ownPage[0].isDestroyed()));
    }

    /** The clients of a view that they destroy as they hear that the requests of its page are gone hear no more. */
    @Test
    public void testViewClientsHearNoMoreOnceTheyDestroyTheView() throws Exception {
        WebPage other = onMainGet(() -> new WebPage(context));
        pages.add(other);
        checkViewClientsMayActAsTheyHearOfRequests(view -> view.setPage(other), WebView::destroy, false);
        assertNull(onMainGet(other::getView));
    }

    // The page that a view made has a color field open for the view's chrome client and a request for HTTP
    // authentication of the view's client, of a frame. The view lets go of it with letGo, and the chrome client does
    // onHide as it hears that the color request is gone. The client then hears that the HTTP request is gone if told.
    private void checkViewClientsMayActAsTheyHearOfRequests(Consumer<WebView> letGo, Consumer<WebView> onHide,
                                                            boolean told) throws Exception {
        WebViewTestActivity activity = activity();
        BlockingQueue<WebViewClient.HttpAuthHandler> authRequests = new LinkedBlockingQueue<>();
        BlockingQueue<WebViewClient.HttpAuthHandler> canceledAuthRequests = new LinkedBlockingQueue<>();
        BlockingQueue<String> finishedLoads = new LinkedBlockingQueue<>();
        AtomicInteger colorRequests = new AtomicInteger();
        AtomicInteger hiddenColorRequests = new AtomicInteger();
        HttpServer server = HttpServer.start();
        WebContext viewContext = onMainGet(() -> new WebContext(activity, false, true));
        WebView[] view = new WebView[1];
        try {
            HttpResponse unauthorized = new HttpResponse();
            unauthorized.setStatusCode(401);
            unauthorized.setStatusMessage("Unauthorized");
            unauthorized.addHeader("WWW-Authenticate", "Basic realm=\"Test\"");
            unauthorized.addHeader("Content-Type", "text/html; charset=utf-8");
            unauthorized.setBody("<p>Unauthorized");
            String protectedUrl = server.setResponse("/protected.html", unauthorized);
            HttpResponse field = new HttpResponse();
            field.setStatusCode(200);
            field.setStatusMessage("OK");
            field.addHeader("Content-Type", "text/html; charset=utf-8");
            field.setBody("<input id=color type=color>");
            String fieldUrl = server.setResponse("/field.html", field);
            view[0] = onMainGet(() -> {
                WebView made = new WebView(activity, viewContext);
                made.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WebView source, @NonNull String url) {
                        finishedLoads.add(url);
                    }

                    @Override
                    public void onReceivedHttpAuthRequest(@NonNull WebView source, @NonNull HttpAuthHandler handler,
                                                          @NonNull String host, @NonNull String realm) {
                        authRequests.add(handler);
                    }

                    @Override
                    public void onHttpAuthRequestCanceled(@NonNull WebView source, @NonNull HttpAuthHandler handler) {
                        canceledAuthRequests.add(handler);
                    }
                });
                made.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onShowColorChooser(@NonNull WebView source, @NonNull ColorChooserParams params,
                                                      @NonNull ColorChooserCallback callback) {
                        colorRequests.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void onHideColorChooser(@NonNull WebView source) {
                        hiddenColorRequests.incrementAndGet();
                        onHide.accept(source);
                    }
                });
                return made;
            });
            WebPage page = onMainGet(() -> view[0].getPage());
            onMain(() -> view[0].loadUrl(fieldUrl));
            assertNotNull("The page did not load",
                          finishedLoads.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS));
            // A script that the app runs counts as a user gesture, which a color field needs to open.
            evaluate(page, "document.getElementById('color').click()");
            WebPageTestHelper.waitFor("The view's chrome client got no color request", () -> colorRequests.get() == 1);
            evaluate(page, "var frame = document.createElement('iframe'); frame.src = '" + protectedUrl +
                               "'; document.body.appendChild(frame); 'added'");
            WebViewClient.HttpAuthHandler handler =
                authRequests.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The view's client got no request for HTTP authentication", handler);

            onMain(() -> letGo.accept(view[0]));
            assertEquals(1, hiddenColorRequests.get());
            if (told)
                assertSame(handler, canceledAuthRequests.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS));
            else
                assertNull(canceledAuthRequests.poll(1, TimeUnit.SECONDS));
        } finally {
            server.shutdown();
            onMain(() -> {
                if (view[0] != null)
                    view[0].destroy();
                viewContext.destroy();
            });
        }
    }

    /** A client that the app set on the page that the view made stays when the view releases the page. */
    @Test
    public void testReleasePageKeepsTheClientsOfTheApp() throws Exception {
        WebView view = activityWebView();
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = onMainGet(() -> {
            WebPage ownPage = view.getPage();
            ownPage.setClient(client);
            return view.releasePage();
        });
        pages.add(page);
        assertSame(client, onMainGet(page::getClient));
        loadHtml(page, client, "<html><body></body></html>");
    }

    /**
     * Pages that the app makes with the context that a WebView made for itself keep it: the context ends with the last
     * page made with it, not with the page of the view, whether the view shows another page or is destroyed. The pages
     * load nothing, so that the context can be destroyed.
     */
    @Test
    public void testPagesKeepTheContextThatAViewMade() {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        onMain(() -> {
            WebView shows = new WebView(targetContext);
            WebView destroyed = new WebView(targetContext);
            WebContext showsContext = shows.getPage().getWebContext();
            WebContext destroyedContext = destroyed.getPage().getWebContext();
            WebPage shown = new WebPage(showsContext);
            WebPage kept = new WebPage(destroyedContext);
            try {
                shows.setPage(shown);
                assertFalse(shown.isDestroyed());
                assertFalse(showsContext.isDestroyed());
                shows.destroy();
                assertFalse(shown.isDestroyed());
                shown.destroy();
                assertTrue("The context did not end with its last page", showsContext.isDestroyed());

                destroyed.destroy();
                assertFalse(kept.isDestroyed());
                assertFalse(destroyedContext.isDestroyed());
                kept.destroy();
                assertTrue("The context did not end with its last page", destroyedContext.isDestroyed());
            } finally {
                shows.destroy();
                destroyed.destroy();
                shown.destroy();
                kept.destroy();
            }
        });
    }

    /** A headless view keeps the page it made for its whole life. */
    @Test
    public void testHeadlessViewKeepsItsPage() {
        onMain(() -> {
            WebView view = new WebView(context, true);
            try {
                assertNotNull(view.getPage());
                assertThrows(IllegalStateException.class, () -> view.setPage(null));
                assertThrows(IllegalStateException.class, view::releasePage);
            } finally {
                view.destroy();
            }
        });
    }

    /** The methods of a page that reach WebKit run on the main thread only. */
    @Test
    public void testPageIsUsedOnTheMainThread() {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        assertThrows(IllegalStateException.class, () -> page.loadUrl("about:blank"));
        assertThrows(IllegalStateException.class, () -> page.evaluateJavascript("1", null));
        assertThrows(IllegalStateException.class, () -> new WebPage(context));
        assertFalse(page.isDestroyed());
        assertSame(context, page.getWebContext());
    }

    // A chrome client that takes confirm() and keeps its answer in the list, made apart so that the test keeps no
    // reference to it.
    private static WeakReference<WebPageChromeClient> holdDialogs(WebPage page, List<WPEJsResult> held) {
        WebPageChromeClient client = new WebPageChromeClient() {
            @Override
            public boolean onJsConfirm(@NonNull WebPage source, String url, String message,
                                       @NonNull WPEJsResult result) {
                held.add(result);
                return true;
            }
        };
        onMain(() -> page.setChromeClient(client));
        return new WeakReference<>(client);
    }

    /** A destroyed page lets go of its clients, also while the app still holds one of its requests. */
    @Test
    public void testDestroyedPageLetsGoOfItsClients() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, "<html><body></body></html>");
        List<WPEJsResult> held = new ArrayList<>();
        WeakReference<WebPageChromeClient> chromeClient = holdDialogs(page, held);
        onMain(() -> page.evaluateJavascript("confirm('Held')", null));
        WebPageTestHelper.waitFor("The chrome client did not get the dialog", () -> !held.isEmpty());

        onMain(page::destroy);
        for (int i = 0; i < 50 && chromeClient.get() != null; i++) {
            Runtime.getRuntime().gc();
            System.runFinalization();
            Thread.sleep(100);
        }
        assertNull("The destroyed page kept its chrome client", chromeClient.get());
        assertEquals(1, held.size());
    }
}
