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

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.TIMEOUT_SECONDS;
import static org.wpewebkit.wpeview.WebPageTestHelper.evaluate;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadUrl;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.servePage;

/**
 * The icons of pages: those a page declares, or the favicon.ico of its site, which {@link WebPage#getFavicon} gives in
 * the size that fits, and those that the icon database of a {@link WebContext} keeps for the addresses of pages. The
 * sessions are made with the kind they are, as emulators make ephemeral ones by default.
 */
@RunWith(AndroidJUnit4.class)
public class WebPageFaviconTest {
    private static final int RED = Color.rgb(200, 30, 10);
    private static final int GREEN = Color.rgb(20, 180, 40);
    private static final int BLUE = Color.rgb(10, 40, 220);
    // How long a page that commits waits for the icon kept for its address, which the icon database gives in moments.
    private static final long STORED_ICON_WAIT_MS = 3000;
    // How long a test waits for a change of icon that must not come.
    private static final long NO_CHANGE_WAIT_MS = 1000;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    // The first part of the paths of this test's pages and icons, since sessions that keep their data keep the icons of
    // earlier runs, whose server may have had the same port.
    private final String base = "/" + UUID.randomUUID().toString().replace("-", "");
    private final List<WebContext> contexts = new ArrayList<>();
    private final List<WebPage> pages = new ArrayList<>();

    // What the pages report, in order, for the tests that look at the order: "icon" or "no icon", and "visit".
    private final List<String> events = new CopyOnWriteArrayList<>();

    // Records each change of the icon of a page, with the icon the page then had at 32 pixels, or none.
    private class IconClient extends WebPageChromeClient {
        final BlockingQueue<AtomicReference<Bitmap>> icons = new LinkedBlockingQueue<>();

        @Override
        public void onFaviconChanged(@NonNull WebPage page) {
            Bitmap icon = page.getFavicon(32);
            events.add(icon != null ? "icon" : "no icon");
            icons.add(new AtomicReference<>(icon));
        }

        // The next icon the page reports, which may be none.
        @Nullable
        Bitmap next() throws InterruptedException {
            AtomicReference<Bitmap> icon = icons.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The icon of the page did not change", icon);
            return icon.get();
        }

        // Waits for an icon, past the changes that leave the page without one.
        @NonNull
        Bitmap nextIcon() throws InterruptedException {
            Bitmap icon;
            do {
                icon = next();
            } while (icon == null);
            return icon;
        }
    }

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (WebPage page : pages)
                page.destroy();
            // The sessions that keep their data share the app's icon database, which the next tests should not find
            // these pages in. One clear empties it, and clearing it from several sessions at once made WebKit abort on
            // a phone (see WebKitNetworkSession.cpp).
            boolean cleared = false;
            for (WebContext context : contexts) {
                if (!cleared && !context.isEphemeralSession()) {
                    context.clearFavicons();
                    cleared = true;
                }
                context.destroy();
            }
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private WebContext newContext(boolean ephemeral) {
        WebContext context = onMainGet(() -> new WebContext(getApplicationContext(), false, ephemeral));
        contexts.add(context);
        return context;
    }

    private WebPage newIconPage(WebContext context, WebPageTestHelper.RecordingClient client, IconClient iconClient) {
        WebPage page = newPage(context, client);
        pages.add(page);
        onMain(() -> page.setChromeClient(iconClient));
        return page;
    }

    // A square PNG image of one color.
    private static byte[] png(int size, int color) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        return bytes.toByteArray();
    }

    private static HttpResponse imageResponse(byte[] image) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "image/png");
        response.setBodyBytes(image);
        return response;
    }

    // Serves the image at the path, after base, and returns its address.
    private String serveImage(String path, byte[] image) {
        return httpServer.setResponse(base + path, imageResponse(image));
    }

    // A page, at the path after base, that declares the icons at the given paths after base.
    private String servePageWithIcons(String path, String... iconPaths) {
        StringBuilder links = new StringBuilder();
        for (String iconPath : iconPaths)
            links.append("<link rel='icon' href='").append(base).append(iconPath).append("'>");
        return servePage(httpServer, base + path,
                         "<html><head><title>Icons</title>" + links + "</head><body></body></html>");
    }

    private static void assertIcon(Bitmap icon, int size, int color) {
        assertNotNull("No icon", icon);
        assertEquals("Width", size, icon.getWidth());
        assertEquals("Height", size, icon.getHeight());
        assertEquals("Color", Integer.toHexString(color), Integer.toHexString(icon.getPixel(size / 2, size / 2)));
    }

    // Serves a page that declares the red icon and shows a picture, whose response the test may hold.
    private String servePageWithPicture(String path) {
        return servePage(httpServer, base + path,
                         "<html><head><link rel='icon' href='" + base + "/red.png'></head><body><img src='" + base +
                             "/picture.png'></body></html>");
    }

    // Serves the picture of the pages, which the browser loads again for each page, rather than from its cache. Unless
    // released is null, its response waits for it.
    private void servePicture(@Nullable CountDownLatch released) {
        HttpResponse picture = imageResponse(png(8, BLUE));
        picture.addHeader("Cache-Control", "no-store");
        if (released != null)
            picture.holdUntil(released);
        httpServer.setResponse(base + "/picture.png", picture);
    }

    // A page client that also keeps the addresses of the pages that committed.
    private class CommitClient extends WebPageTestHelper.RecordingClient {
        final BlockingQueue<String> committedUrls = new LinkedBlockingQueue<>();

        @Override
        public void doUpdateVisitedHistory(@NonNull WebPage page, @NonNull String url, boolean isReload) {
            events.add("visit");
            committedUrls.add(url);
        }
    }

    // Loads the page in a new page of the context while its picture is held, so that it is not parsed and loads none of
    // its icons, and returns the icon it gets once it commits: the one that the context keeps for its address, or null
    // if none comes in a while.
    private Bitmap iconBeforeParsing(WebContext context, String url) throws InterruptedException {
        CountDownLatch released = new CountDownLatch(1);
        servePicture(released);
        CommitClient client = new CommitClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(context, client, iconClient);
        Bitmap stored = null;
        try {
            onMain(() -> page.loadUrl(url));
            assertEquals("The page did not commit", url, client.committedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            long deadline = SystemClock.uptimeMillis() + STORED_ICON_WAIT_MS;
            while (stored == null && SystemClock.uptimeMillis() < deadline) {
                AtomicReference<Bitmap> icon = iconClient.icons.poll(50, TimeUnit.MILLISECONDS);
                if (icon != null)
                    stored = icon.get();
            }
        } finally {
            released.countDown();
        }
        // Then the page loads its own icon, which the context keeps, before the test goes on.
        client.waitForFinishedLoad();
        iconClient.nextIcon();
        return stored;
    }

    /** The page gets the icon it declares, with its colors as they are. */
    @Test
    public void testPageGetsTheIconItDeclares() throws Exception {
        serveImage("/red.png", png(32, RED));
        String url = servePageWithIcons("/page.html", "/red.png");
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(newContext(true), client, iconClient);

        loadUrl(page, client, url);
        assertIcon(iconClient.nextIcon(), 32, RED);
        assertIcon(onMainGet(() -> page.getFavicon(32)), 32, RED);
    }

    /**
     * Of the sizes a page has, the smallest that is at least as big as asked for is scaled down to fit, or else the
     * biggest is given as it is.
     */
    @Test
    public void testIconFitsTheSizeAskedFor() throws Exception {
        serveImage("/small.png", png(16, RED));
        serveImage("/medium.png", png(64, GREEN));
        serveImage("/large.png", png(128, BLUE));
        String url = servePageWithIcons("/page.html", "/medium.png", "/large.png", "/small.png");
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(newContext(true), client, iconClient);

        loadUrl(page, client, url);
        iconClient.nextIcon();
        assertIcon(onMainGet(() -> page.getFavicon(16)), 16, RED);
        assertIcon(onMainGet(() -> page.getFavicon(48)), 48, GREEN);
        assertIcon(onMainGet(() -> page.getFavicon(64)), 64, GREEN);
        assertIcon(onMainGet(() -> page.getFavicon(100)), 100, BLUE);
        assertIcon(onMainGet(() -> page.getFavicon(300)), 128, BLUE);
    }

    /** A page that declares no icon gets the favicon.ico of its site. */
    @Test
    public void testPageWithoutIconsGetsTheFaviconOfItsSite() throws Exception {
        httpServer.setResponse("/favicon.ico", imageResponse(png(32, GREEN)));
        String url = servePage(httpServer, base + "/page.html", "<html><head><title>No icons</title></head></html>");
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(newContext(true), client, iconClient);

        loadUrl(page, client, url);
        assertIcon(iconClient.nextIcon(), 32, GREEN);
    }

    /**
     * A new page has none of the icons of the page before it, whether it loads none, as about:blank, or its own, and a
     * navigation within the page keeps its icon.
     */
    @Test
    public void testNewPageHasNoneOfTheIconsOfTheOldOne() throws Exception {
        serveImage("/red.png", png(32, RED));
        serveImage("/blue.png", png(32, BLUE));
        String red = servePageWithIcons("/red.html", "/red.png");
        String blue = servePageWithIcons("/blue.html", "/blue.png");
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(newContext(true), client, iconClient);

        loadUrl(page, client, red);
        assertIcon(iconClient.nextIcon(), 32, RED);
        evaluate(page, "history.pushState({}, '', 'moved.html')");
        assertNoChange(iconClient);
        loadUrl(page, client, blue);
        assertNull("The icon of the old page", iconClient.next());
        assertIcon(iconClient.nextIcon(), 32, BLUE);

        loadUrl(page, client, "about:blank");
        assertNull("The icon of the old page", iconClient.next());
        assertNull(onMainGet(() -> page.getFavicon(32)));
        assertNoChange(iconClient);
    }

    private static void assertNoChange(IconClient iconClient) throws InterruptedException {
        AtomicReference<Bitmap> icon = iconClient.icons.poll(NO_CHANGE_WAIT_MS, TimeUnit.MILLISECONDS);
        assertNull("The icon changed", icon);
    }

    /**
     * A page that declares another icon, as a site whose icon file gets a new name, gets it, although WebKit keeps the
     * old one for its address too.
     */
    @Test
    public void testNewIconOfAPageTakesThePlaceOfTheOldOne() throws Exception {
        serveImage("/red.png", png(32, RED));
        serveImage("/blue.png", png(32, BLUE));
        String url = servePageWithIcons("/page.html", "/red.png");
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(newContext(true), client, iconClient);
        loadUrl(page, client, url);
        assertIcon(iconClient.nextIcon(), 32, RED);

        servePageWithIcons("/page.html", "/blue.png");
        onMain(page::reload);
        client.waitForFinishedLoad();
        // The page may keep the red icon until its own come.
        Bitmap icon = iconClient.nextIcon();
        while (icon.getPixel(16, 16) != BLUE)
            icon = iconClient.nextIcon();
        assertIcon(icon, 32, BLUE);
    }

    /**
     * A page that the user goes back to has its icon as soon as it commits, before it loads its own, also when the
     * context keeps no icons for it any more. The page has no icon when it is reported, so that the app can tell which
     * page has which icon.
     */
    @Test
    public void testGoingBackShowsTheIconAtOnce() throws Exception {
        serveImage("/red.png", png(32, RED));
        serveImage("/blue.png", png(32, BLUE));
        servePicture(null);
        String red = servePageWithPicture("/red.html");
        String blue = servePageWithIcons("/blue.html", "/blue.png");
        WebContext context = newContext(false);
        CommitClient client = new CommitClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(context, client, iconClient);
        loadUrl(page, client, red);
        assertIcon(iconClient.nextIcon(), 32, RED);
        loadUrl(page, client, blue);
        assertIcon(iconClient.nextIcon(), 32, BLUE);
        onMain(context::clearFavicons);

        // The red page cannot load its icon while its picture is held, if it loads again rather than coming back from
        // the back/forward cache.
        CountDownLatch released = new CountDownLatch(1);
        servePicture(released);
        try {
            client.committedUrls.clear();
            iconClient.icons.clear();
            events.clear();
            onMain(page::goBack);
            assertEquals("The page did not commit", red, client.committedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertIcon(iconClient.nextIcon(), 32, RED);
            assertEquals(Arrays.asList("no icon", "visit", "icon"), events.subList(0, 3));
        } finally {
            released.countDown();
        }
    }

    /**
     * A page that a session that keeps its data loaded before has its icon as soon as it commits, before it is parsed
     * and loads its own, until the session clears its icons. Other pages of the site have none.
     */
    @Test
    public void testSessionKeepsTheIconsOfPages() throws Exception {
        serveImage("/red.png", png(32, RED));
        servePicture(null);
        String url = servePageWithPicture("/page.html");
        WebContext context = newContext(false);
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(context, client, iconClient);
        loadUrl(page, client, url);
        assertIcon(iconClient.nextIcon(), 32, RED);

        assertIcon(iconBeforeParsing(context, url), 32, RED);
        assertNull("Another page of the site", iconBeforeParsing(context, servePageWithPicture("/other.html")));

        onMain(context::clearFavicons);
        assertNull("A cleared icon", iconBeforeParsing(context, url));
        assertIcon(onMainGet(() -> page.getFavicon(32)), 32, RED);
    }

    /**
     * An ephemeral session keeps its icons in memory, apart from other sessions, and never on disk, where the sessions
     * that keep their data find the icons they kept.
     */
    @Test
    public void testEphemeralSessionKeepsItsIcons() throws Exception {
        serveImage("/red.png", png(32, RED));
        servePicture(null);
        String url = servePageWithPicture("/ephemeral.html");
        WebContext ephemeral = newContext(true);
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(ephemeral, client, iconClient);
        loadUrl(page, client, url);
        assertIcon(iconClient.nextIcon(), 32, RED);
        assertIcon(iconBeforeParsing(ephemeral, url), 32, RED);

        WebContext keeping = newContext(false);
        String kept = servePageWithPicture("/kept.html");
        servePicture(null);
        WebPageTestHelper.RecordingClient keptClient = new WebPageTestHelper.RecordingClient();
        IconClient keptIcons = new IconClient();
        loadUrl(newIconPage(keeping, keptClient, keptIcons), keptClient, kept);
        keptIcons.nextIcon();
        // A session that keeps its data reads the icon database when it starts.
        WebContext later = newContext(false);
        assertIcon(iconBeforeParsing(later, kept), 32, RED);
        assertNull("In a session that keeps its data", iconBeforeParsing(later, url));
        assertNull("In another ephemeral session", iconBeforeParsing(newContext(true), url));
    }

    /** A WebView tells its chrome client of the icon of its page, and gives it, as the page does. */
    @Test
    public void testWebViewGivesTheIconOfItsPage() throws Exception {
        serveImage("/blue.png", png(32, BLUE));
        String url = servePageWithIcons("/page.html", "/blue.png");
        BlockingQueue<AtomicReference<Bitmap>> icons = new LinkedBlockingQueue<>();
        TestWebViewClient client = new TestWebViewClient();
        activityRule.getScenario().onActivity(activity -> {
            activity.getWebView().setWebViewClient(client);
            activity.getWebView().setWebChromeClient(new WebChromeClient() {
                @Override
                public void onFaviconChanged(@NonNull WebView view) {
                    icons.add(new AtomicReference<>(view.getFavicon(64)));
                }
            });
        });
        WebViewActivityScenarioHelper.loadUrlSync(activityRule.getScenario(), client, url);
        Bitmap icon;
        do {
            AtomicReference<Bitmap> next = icons.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The icon of the page did not change", next);
            icon = next.get();
        } while (icon == null);
        assertIcon(icon, 32, BLUE);
    }

    /** A destroyed page has no icon, and sizes that are not positive are refused. */
    @Test
    public void testIconOfDestroyedPageAndBadSizes() throws Exception {
        serveImage("/red.png", png(32, RED));
        String url = servePageWithIcons("/page.html", "/red.png");
        WebContext context = newContext(true);
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        IconClient iconClient = new IconClient();
        WebPage page = newIconPage(context, client, iconClient);
        loadUrl(page, client, url);
        iconClient.nextIcon();

        assertThrows(IllegalArgumentException.class, () -> onMain(() -> page.getFavicon(0)));
        assertThrows(IllegalStateException.class, () -> page.getFavicon(32));
        onMain(page::destroy);
        assertNull(onMainGet(() -> page.getFavicon(32)));
    }
}
