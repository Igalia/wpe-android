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
import org.wpewebkit.wpe.WebKitWebsiteDataManager;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.TIMEOUT_SECONDS;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;

/**
 * Clearing website data clears the data of the given types, of every site or of one, and keeps the rest. The pages come
 * from localhost and from 127.0.0.1, which are two sites, and load a script that the cache keeps for an hour: the
 * server changes it after the first loads, so a page that gets the new one did not take it from the cache.
 */
@RunWith(AndroidJUnit4.class)
public class WebContextWebsiteDataTest {
    // Two sites.
    private static final String SITE = "localhost";
    private static final String OTHER_SITE = "127.0.0.1";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final List<WebContext> contexts = new ArrayList<>();
    private final List<WebView> webViews = new ArrayList<>();
    // In the addresses and the names of the cookies of this test, since other tests and earlier runs leave their data
    // in the store.
    private String token;
    // Each page has an address of its own, so that no load counts as a load of the page shown.
    private int pageLoads;

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
        token = "data" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    // A new session. On emulators, sessions are ephemeral by default, so the kind of session is given.
    private WebContext newSession(boolean ephemeral) {
        WebContext context = onMainGet(() -> new WebContext(getApplicationContext(), false, ephemeral));
        contexts.add(context);
        return context;
    }

    private WebView newWebView(WebContext context) {
        WebView webView = onMainGet(() -> new WebView(context, true));
        webViews.add(webView);
        return webView;
    }

    // Runs the call on the main thread, and waits for the result that it gives its callback.
    private static <T> T await(String what, Consumer<Consumer<T>> call) throws InterruptedException {
        BlockingQueue<T> result = new LinkedBlockingQueue<>();
        onMain(() -> call.accept(result::add));
        T value = result.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No result from " + what, value);
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

    private static String evaluate(WebView webView, String script) throws InterruptedException {
        return await(script, done -> webView.evaluateJavascript(script, done::accept));
    }

    private static HttpResponse response(String contentType, String body) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", contentType);
        response.setBody(body);
        return response;
    }

    // The script that the pages load, which the cache keeps for an hour, and which tells its version.
    private void serveScript(int version) {
        HttpResponse response = response("text/javascript", "var version = " + version + ";");
        response.addHeader("Cache-Control", "max-age=3600");
        httpServer.setResponse("/" + token + "/script.js", response);
    }

    private static String onHost(String url, String host) { return url.replace("://localhost:", "://" + host + ":"); }

    private String cookie() { return token + "=1"; }

    // A page of the server on the host, which loads the script. A page that stores sets the cookie and an item of the
    // local storage.
    private String page(String host, boolean stores) {
        HttpResponse response = response(
            "text/html; charset=utf-8",
            "<html><body><script src=\"/" + token + "/script.js\"></script>" +
                (stores ? "<script>localStorage.setItem('" + token + "', '1');</script>" : "") + "</body></html>");
        if (stores)
            response.addHeader("Set-Cookie", cookie() + "; Max-Age=3600; Path=/");
        return onHost(httpServer.setResponse("/" + token + "/page" + (++pageLoads) + ".html", response), host);
    }

    // The version of the script that a new page of the host gets.
    private String scriptVersion(WebView webView, String host) throws InterruptedException {
        loadAndWait(webView, page(host, false));
        return evaluate(webView, "version");
    }

    private boolean hasCookie(WebView webView) throws InterruptedException {
        return evaluate(webView, "document.cookie").contains(cookie());
    }

    private boolean hasLocalStorage(WebView webView) throws InterruptedException {
        return evaluate(webView, "localStorage.getItem('" + token + "')").equals("\"1\"");
    }

    private static boolean clear(WebContext context, Set<WebsiteDataType> types) throws InterruptedException {
        return await("clearWebsiteData", done -> context.clearWebsiteData(types, done::accept));
    }

    // Empties the memory caches of the web processes only, so that the pages of a session that keeps its data then get
    // the script from the disk cache.
    private static void clearMemoryCache(WebContext context) throws InterruptedException {
        WebKitWebsiteDataManager manager = context.getWebKitNetworkSession().getWebsiteDataManager();
        EnumSet<WebKitWebsiteDataManager.WebsiteDataType> memoryCache =
            EnumSet.of(WebKitWebsiteDataManager.WebsiteDataType.MemoryCache);
        Boolean cleared = await("clearing the memory cache", done -> manager.clear(memoryCache, done::accept));
        assertTrue("The memory cache was not cleared", cleared);
    }

    private static boolean clearForSite(WebContext context, String url, Set<WebsiteDataType> types)
        throws InterruptedException {
        return await("clearWebsiteDataForSite", done -> context.clearWebsiteDataForSite(url, types, done::accept));
    }

    @Test
    public void clearingTheCacheLoadsResourcesAgainAndKeepsCookies() throws InterruptedException {
        for (boolean ephemeral : new boolean[] {true, false}) {
            WebContext context = newSession(ephemeral);
            WebView webView = newWebView(context);
            serveScript(1);
            loadAndWait(webView, page(SITE, true));
            assertEquals("1", evaluate(webView, "version"));
            serveScript(2);
            assertEquals("The script did not come from the cache", "1", scriptVersion(webView, SITE));
            if (!ephemeral) {
                clearMemoryCache(context);
                assertEquals("The script did not come from the disk cache", "1", scriptVersion(webView, SITE));
            }

            assertTrue("The cache was not cleared", clear(context, EnumSet.of(WebsiteDataType.Cache)));
            assertEquals("The script still came from the cache", "2", scriptVersion(webView, SITE));
            assertTrue("Clearing the cache removed the cookie", hasCookie(webView));
            assertTrue("Clearing the cache removed the local storage", hasLocalStorage(webView));

            assertTrue("The cookies were not cleared", clear(context, EnumSet.of(WebsiteDataType.Cookies)));
            loadAndWait(webView, page(SITE, false));
            assertFalse("The cookie is still there", hasCookie(webView));
            assertTrue("Clearing the cookies removed the local storage", hasLocalStorage(webView));
        }
    }

    @Test
    public void clearingTheDataOfASiteKeepsThoseOfOthers() throws InterruptedException {
        for (boolean ephemeral : new boolean[] {true, false}) {
            WebContext context = newSession(ephemeral);
            // A view for each site, so that each keeps the web process of its site, and the memory cache of that
            // process, whatever process a page of another site would take.
            WebView siteView = newWebView(context);
            WebView otherView = newWebView(context);
            serveScript(1);
            loadAndWait(siteView, page(SITE, true));
            loadAndWait(otherView, page(OTHER_SITE, true));
            serveScript(2);
            assertEquals("The script did not come from the cache", "1", scriptVersion(siteView, SITE));
            assertEquals("The script did not come from the cache on the other site", "1",
                         scriptVersion(otherView, OTHER_SITE));
            if (!ephemeral) {
                clearMemoryCache(context);
                assertEquals("The script did not come from the disk cache", "1", scriptVersion(siteView, SITE));
                assertEquals("The script did not come from the disk cache on the other site", "1",
                             scriptVersion(otherView, OTHER_SITE));
            }

            String url = page(SITE, false);
            assertTrue("The data of the site were not cleared",
                       clearForSite(context, url, EnumSet.allOf(WebsiteDataType.class)));

            loadAndWait(siteView, url);
            assertEquals("The script still came from the cache", "2", evaluate(siteView, "version"));
            assertFalse("The cookie is still there", hasCookie(siteView));
            assertFalse("The local storage is still there", hasLocalStorage(siteView));

            assertEquals("The other site lost its cache", "1", scriptVersion(otherView, OTHER_SITE));
            assertTrue("The other site lost its cookie", hasCookie(otherView));
            assertTrue("The other site lost its local storage", hasLocalStorage(otherView));
        }
    }

    @Test
    public void clearingTheDataOfASiteClearsThoseOfAllItsHosts() throws InterruptedException {
        WebContext context = newSession(true);
        WebView webView = newWebView(context);
        serveScript(1);
        // Hosts below localhost, which the device takes as itself. WebKit files the data of a host under its last two
        // labels, as for a domain whose public suffix it does not know, so the first two are one site.
        String[] hosts = {"a." + token + ".localhost", "b." + token + ".localhost", "c.other" + token + ".localhost"};
        for (String host : hosts) {
            loadAndWait(webView, page(host, true));
            assertTrue("The page did not set the cookie on " + host, hasCookie(webView));
        }

        String url = page(hosts[0], false);
        assertTrue("The cookies of the site were not cleared",
                   clearForSite(context, url, EnumSet.of(WebsiteDataType.Cookies)));
        for (int i = 0; i < hosts.length; i++) {
            loadAndWait(webView, page(hosts[i], false));
            assertEquals("The cookie of " + hosts[i], i == 2, hasCookie(webView));
        }
    }

    @Test
    public void clearingTheDataOfASiteClearsOnlyTheTypesGiven() throws InterruptedException {
        WebContext context = newSession(true);
        WebView webView = newWebView(context);
        serveScript(1);
        loadAndWait(webView, page(SITE, true));
        serveScript(2);

        String url = page(SITE, false);
        assertTrue("The cookies of the site were not cleared",
                   clearForSite(context, url, EnumSet.of(WebsiteDataType.Cookies)));
        loadAndWait(webView, url);
        assertFalse("The cookie is still there", hasCookie(webView));
        assertTrue("Clearing the cookies removed the local storage", hasLocalStorage(webView));
        assertEquals("Clearing the cookies emptied the cache", "1", evaluate(webView, "version"));
    }

    @Test
    public void clearingTheDataOfASiteWithoutDataSucceeds() throws InterruptedException {
        WebContext context = newSession(true);
        assertTrue(clearForSite(context, "https://" + token + ".example/", EnumSet.allOf(WebsiteDataType.class)));
        assertTrue(clearForSite(context, "https://" + token + ".example/", EnumSet.noneOf(WebsiteDataType.class)));
    }

    @Test
    public void clearingTheDataOfAHostWithoutASiteGivesFalse() throws InterruptedException {
        WebContext context = newSession(true);
        Set<WebsiteDataType> all = EnumSet.allOf(WebsiteDataType.class);
        for (String url : new String[] {"http://intranet/", "http://[::1]:8080/", "https://co.uk/"})
            assertFalse(url, clearForSite(context, url, all));
    }

    @Test
    public void theSiteOfAnAddressIsWhereWebKitFilesItsData() {
        assertEquals("example.com", WebContext.getWebsiteDataSite("https://www.example.com/page"));
        assertEquals("example.co.uk", WebContext.getWebsiteDataSite("http://mail.example.co.uk:8080/"));
        assertEquals("example.com", WebContext.getWebsiteDataSite("https://WWW.Example.COM/"));
        assertEquals("xn--mnchen-3ya.example", WebContext.getWebsiteDataSite("https://www.münchen.example/"));
        assertEquals("localhost", WebContext.getWebsiteDataSite("http://localhost:8080/"));
        assertEquals("127.0.0.1", WebContext.getWebsiteDataSite("http://127.0.0.1/"));
        assertEquals("other.localhost", WebContext.getWebsiteDataSite("http://a.other.localhost/"));
        for (String url : new String[] {"http://intranet/", "http://[::1]:8080/", "https://co.uk/", "about:blank",
                                        "file:///sdcard/page.html"})
            assertEquals(url, "", WebContext.getWebsiteDataSite(url));
    }

    @Test
    public void callbacksComeOnlyOnceTheCallReturned() throws InterruptedException {
        WebContext context = newSession(true);
        String url = page(SITE, false);
        Set<WebsiteDataType> none = EnumSet.noneOf(WebsiteDataType.class);
        Set<WebsiteDataType> cache = EnumSet.of(WebsiteDataType.Cache);
        List<Consumer<WPECallback<Boolean>>> calls = new ArrayList<>();
        calls.add(callback -> context.clearWebsiteData(none, callback));
        calls.add(callback -> context.clearWebsiteData(cache, callback));
        calls.add(callback -> context.clearWebsiteDataForSite(url, none, callback));
        calls.add(callback -> context.clearWebsiteDataForSite(url, cache, callback));
        calls.add(callback -> context.clearWebsiteDataForSite("http://intranet/", cache, callback));
        for (Consumer<WPECallback<Boolean>> call : calls) {
            AtomicBoolean returned = new AtomicBoolean();
            BlockingQueue<Boolean> returnedFirst = new LinkedBlockingQueue<>();
            onMain(() -> {
                call.accept(result -> returnedFirst.add(returned.get()));
                returned.set(true);
            });
            Boolean value = returnedFirst.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("No callback", value);
            assertTrue("The callback came before the call returned", value);
        }
    }

    @Test
    public void noCallbackComesOnceTheContextIsDestroyed() throws InterruptedException {
        WebContext context = newSession(true);
        String url = page(SITE, false);
        Set<WebsiteDataType> cache = EnumSet.of(WebsiteDataType.Cache);
        BlockingQueue<Boolean> results = new LinkedBlockingQueue<>();
        onMain(() -> {
            context.clearWebsiteData(cache, results::add);
            context.clearWebsiteDataForSite(url, cache, results::add);
            context.clearWebsiteDataForSite("http://intranet/", cache, results::add);
            context.destroy();
        });
        assertNull("A callback came after the context was destroyed", results.poll(3, TimeUnit.SECONDS));
    }

    @Test
    public void clearingTheDataOfAnAddressWithoutASiteThrows() {
        WebContext context = newSession(true);
        Set<WebsiteDataType> all = EnumSet.allOf(WebsiteDataType.class);
        for (String url : new String[] {"about:blank", "file:///sdcard/page.html", "data:text/html,page", "example"}) {
            Runnable clear = () -> context.clearWebsiteDataForSite(url, all, null);
            onMain(() -> assertThrows(url, IllegalArgumentException.class, clear::run));
        }
    }
}
