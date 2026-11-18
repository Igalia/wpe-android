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

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
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

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * An app may destroy a WebView in any of its callbacks, as when it closes a tab whose page went wrong. WebKit may still
 * use the view once the callback returns, and so may WPEView. Each test starts what calls the app with a tap, a call of
 * the app or the end of a process, never with a script that the app runs, whose result would keep the view alive until
 * it comes. Then another WebView loads a page, which a crash after the callback would stop.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewDestroyInCallbackTest {
    private static final long TIMEOUT_SECONDS = 30;
    private static final String REALM = "WPE test";
    // A page that a tap anywhere runs the script of, once it was rendered.
    private static final String TAP_PAGE = "<html><head><meta name=viewport content='width=device-width'></head>"
                                           + "<body style='margin:0'><div id=target style='height:100vh'></div>"
                                           + "<script>var rendered = false;"
                                           + "requestAnimationFrame(() => requestAnimationFrame(() => {"
                                           + "  rendered = true; }));"
                                           + "document.getElementById('target').addEventListener('click', () => {"
                                           + "%s });</script></body></html>";
    // Pages that ask for the location of the device, and what a request for it would get, as they load.
    private static final String LOCATE_PAGE =
        "<title>Locate</title><script>navigator.geolocation.getCurrentPosition(p => {}, e => {});</script>";
    private static final String QUERY_PAGE =
        "<title>Query</title><script>navigator.permissions.query({name: 'geolocation'});</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;
    private final TestWebViewClient loadClient = new TestWebViewClient();
    // Counted down by the callback that destroyed the WebView.
    private final CountDownLatch destroyed = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        httpServer = HttpServer.start();
    }

    @After
    public void tearDown() {
        onWebView(view -> {
            if (view.getInternalWebKitWebView() != null)
                view.destroy();
        });
        if (httpServer != null)
            httpServer.shutdown();
    }

    private static HttpResponse page(int statusCode, String statusMessage, String html) {
        HttpResponse response = new HttpResponse();
        response.setStatusCode(statusCode);
        response.setStatusMessage(statusMessage);
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(html);
        return response;
    }

    private String setPage(String path, String html) { return httpServer.setResponse(path, page(200, "OK", html)); }

    // A page that asks for a user name and password, and shows its 401 page without them.
    private String setProtectedPage(String path) {
        HttpResponse response = page(401, "Unauthorized", "<title>Unauthorized</title>");
        response.addHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\"");
        return httpServer.setResponse(path, response);
    }

    private ActivityScenario<WebViewTestActivity> scenario() { return webViewActivityTestRule.getScenario(); }

    private void onWebView(Consumer<WebView> action) {
        scenario().onActivity(activity -> action.accept(activity.getWebView()));
    }

    // What the callbacks do: the app destroys the WebView, as a browser closes a tab.
    private void destroy(@NonNull WebView view) {
        view.destroy();
        destroyed.countDown();
    }

    private void loadAndWait(String url) { WebViewActivityScenarioHelper.loadUrlSync(scenario(), loadClient, url); }

    private void tapTheMiddle() throws Throwable {
        assertEquals("The page was not rendered", "true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario(), "rendered", "true"));
        onWebView(view -> {
            float x = view.getWidth() / 2f;
            float y = view.getHeight() / 2f;
            long downTime = SystemClock.uptimeMillis();
            for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                long eventTime = action == MotionEvent.ACTION_DOWN ? downTime : downTime + 50;
                MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
                view.dispatchTouchEvent(event);
                event.recycle();
            }
        });
    }

    // The app destroyed the WebView in its callback, and goes on.
    private void assertDestroyedAndTheAppGoesOn() throws Exception {
        assertTrue("The callback did not destroy the WebView", destroyed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        onWebView(view -> assertNull(view.getInternalWebKitWebView()));
        assertTheAppGoesOn();
    }

    // Another WebView loads a page.
    private void assertTheAppGoesOn() throws Exception {
        String url = setPage("/other.html", "<title>Other</title><p>Other");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<WebView> other = new AtomicReference<>();
        scenario().onActivity(activity -> {
            other.set(new WebView(activity));
            other.get().setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String finishedUrl) {
                    finished.countDown();
                }
            });
            activity.addContentView(other.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                            ViewGroup.LayoutParams.MATCH_PARENT));
            other.get().loadUrl(url);
        });
        try {
            assertTrue("Another WebView did not load its page", finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            scenario().onActivity(activity -> {
                ((ViewGroup)other.get().getParent()).removeView(other.get());
                other.get().destroy();
            });
        }
    }

    private static void killWebProcesses() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        int killed = 0;
        for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
            if (process.processName.contains(":WPEWebProcess")) {
                Process.killProcess(process.pid);
                killed++;
            }
        }
        assertNotEquals("No web process to end", 0, killed);
    }

    // A port that nothing listens on, so that a load from it fails at once.
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // Loads a page with a client that destroys the WebView in one of the callbacks of the load.
    private void loadWithClient(WebViewClient client) throws Exception {
        String url = setPage("/page.html", "<title>Page</title><p>Page");
        onWebView(view -> {
            view.setWebViewClient(client);
            view.loadUrl(url);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    @Test
    public void testDestroyInOnPageStarted() throws Exception {
        loadWithClient(new WebViewClient() {
            @Override
            public void onPageStarted(@NonNull WebView view, @NonNull String url) {
                destroy(view);
            }
        });
    }

    @Test
    public void testDestroyInDoUpdateVisitedHistory() throws Exception {
        loadWithClient(new WebViewClient() {
            @Override
            public void doUpdateVisitedHistory(@NonNull WebView view, @NonNull String url, boolean isReload) {
                destroy(view);
            }
        });
    }

    @Test
    public void testDestroyInOnPageFinished() throws Exception {
        loadWithClient(new WebViewClient() {
            @Override
            public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                destroy(view);
            }
        });
    }

    /** A response sent as an attachment, which the view saves. */
    @Test
    public void testDestroyInOnDownloadStart() throws Exception {
        HttpResponse file = page(200, "OK", "text");
        file.addHeader("Content-Disposition", "attachment");
        String url = httpServer.setResponse("/file.txt", file);
        onWebView(view -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onDownloadStart(@NonNull WebView view, @NonNull WPEDownload download) {
                    destroy(view);
                }
            });
            view.loadUrl(url);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    /** A file field that a tap opened. */
    @Test
    public void testDestroyInOnShowFileChooser() throws Throwable {
        loadAndWait(setPage("/file.html", String.format(TAP_PAGE, "const input = document.createElement('input');"
                                                                      + "input.type = 'file'; input.click();")));
        onWebView(view -> view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(@NonNull WebView view, @NonNull FileChooserParams fileChooserParams,
                                             @NonNull WPECallback<String[]> filePathCallback) {
                destroy(view);
                return true;
            }
        }));
        tapTheMiddle();
        assertDestroyedAndTheAppGoesOn();
    }

    /** A page apart from any view, which the app destroys in a callback of its own client. */
    @Test
    public void testDestroyAPageInItsClient() throws Exception {
        String url = setPage("/page.html", "<title>Page</title><p>Page");
        AtomicReference<WebContext> context = new AtomicReference<>();
        AtomicReference<WebPage> page = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        scenario().onActivity(activity -> {
            context.set(new WebContext(activity));
            page.set(new WebPage(context.get()));
            page.get().setClient(new WebPageClient() {
                @Override
                public void onPageFinished(@NonNull WebPage source, @NonNull String finishedUrl) {
                    finished.countDown();
                }

                @Override
                public void onWebProcessTerminated(@NonNull WebPage source, int reason) {
                    source.destroy();
                    destroyed.countDown();
                }
            });
            page.get().loadUrl(url);
        });
        try {
            assertTrue("The page did not load", finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario().onActivity(activity -> page.get().terminateWebProcess());
            assertTrue("The client did not destroy the page", destroyed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            scenario().onActivity(activity -> assertTrue(page.get().isDestroyed()));
            assertTheAppGoesOn();
        } finally {
            scenario().onActivity(activity -> {
                page.get().destroy();
                context.get().destroy();
            });
        }
    }

    /** A dialog that a tap opened: WebKit ends it once the callback returns. */
    @Test
    public void testDestroyInOnJsConfirm() throws Throwable {
        loadAndWait(setPage("/confirm.html", String.format(TAP_PAGE, "confirm('Close the tab?');")));
        onWebView(view -> view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                       @NonNull WPEJsResult result) {
                destroy(view);
                return true;
            }
        }));
        tapTheMiddle();
        assertDestroyedAndTheAppGoesOn();
    }

    /** A dialog that a tap opened, whose callback destroys the WebView and then fails. */
    @Test
    public void testDestroyInOnJsAlertThatThrows() throws Throwable {
        loadAndWait(setPage("/alert.html", String.format(TAP_PAGE, "alert('The tab closes');")));
        onWebView(view -> view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                     @NonNull WPEJsResult result) {
                destroy(view);
                throw new IllegalStateException("The client failed after it destroyed the WebView");
            }
        }));
        tapTheMiddle();
        assertDestroyedAndTheAppGoesOn();
    }

    /** A page that goes fullscreen when tapped. */
    @Test
    public void testDestroyInOnEnterFullscreen() throws Throwable {
        loadAndWait(
            setPage("/fullscreen.html", String.format(TAP_PAGE, "document.documentElement.requestFullscreen();")));
        onWebView(view -> view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onEnterFullscreen(@NonNull WebView view) {
                destroy(view);
            }
        }));
        tapTheMiddle();
        assertDestroyedAndTheAppGoesOn();
    }

    /** The web process that the app ends, as it may when the page uses too much memory. */
    @Test
    public void testDestroyInOnWebProcessTerminatedByTheApp() throws Exception {
        loadAndWait(setPage("/page.html", "<title>Page</title><p>Page"));
        onWebView(view -> view.setWebViewClient(new WebViewClient() {
            @Override
            public void onWebProcessTerminated(@NonNull WebView view, int reason) {
                destroy(view);
            }
        }));
        onWebView(WebView::terminateWebProcess);
        assertDestroyedAndTheAppGoesOn();
    }

    /** A web process that Android ends, after which an app is expected to destroy the WebView. */
    @Test
    public void testDestroyInOnWebProcessTerminatedByAndroid() throws Exception {
        loadAndWait(setPage("/page.html", "<title>Page</title><p>Page"));
        onWebView(view -> view.setWebViewClient(new WebViewClient() {
            @Override
            public void onWebProcessTerminated(@NonNull WebView view, int reason) {
                destroy(view);
            }
        }));
        killWebProcesses();
        assertDestroyedAndTheAppGoesOn();
    }

    /** An answer that the app gives after that does nothing. */
    @Test
    public void testDestroyInOnPermissionRequest() throws Exception {
        String url = setPage("/locate.html", LOCATE_PAGE);
        onWebView(view -> {
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
                    destroy(view);
                    request.grant();
                }
            });
            view.loadUrl(url);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    // Loads a page that asks for the location, and keeps its request unanswered, with a client that destroys the
    // WebView when the request ends.
    private void loadAPageThatAsksAndDestroyWhenItsRequestEnds() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        String url = setPage("/locate.html", LOCATE_PAGE);
        onWebView(view -> {
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onPermissionRequest(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
                    asked.countDown();
                }

                @Override
                public void onPermissionRequestCanceled(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
                    destroy(view);
                }
            });
            view.loadUrl(url);
        });
        assertTrue("The page did not ask", asked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** The request ends with the document that asked. */
    @Test
    public void testDestroyInOnPermissionRequestCanceledByTheNextPage() throws Exception {
        loadAPageThatAsksAndDestroyWhenItsRequestEnds();
        String next = setPage("/next.html", "<title>Next</title><p>Next");
        onWebView(view -> view.loadUrl(next));
        assertDestroyedAndTheAppGoesOn();
    }

    /** The request ends with the web process. */
    @Test
    public void testDestroyInOnPermissionRequestCanceledByTheEndOfTheWebProcess() throws Exception {
        loadAPageThatAsksAndDestroyWhenItsRequestEnds();
        killWebProcesses();
        assertDestroyedAndTheAppGoesOn();
    }

    /** A query that the app takes and never answers gets WebKit's answer. */
    @Test
    public void testDestroyInOnQueryPermissionState() throws Exception {
        String url = setPage("/query.html", QUERY_PAGE);
        onWebView(view -> {
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onQueryPermissionState(@NonNull WebView view, @NonNull WPEPermissionStateQuery query) {
                    destroy(view);
                    return true;
                }
            });
            view.loadUrl(url);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    /** WebKit ends a load that failed once the callback returns. */
    @Test
    public void testDestroyInOnReceivedError() throws Exception {
        String url = "http://127.0.0.1:" + closedPort() + "/";
        onWebView(view -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                            @NonNull WPEResourceError error) {
                    destroy(view);
                }
            });
            view.loadUrl(url);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    /** A page without a title has its address as its title, which the view reports as the page commits. */
    @Test
    public void testDestroyInOnReceivedTitle() throws Exception {
        loadAndWait(setPage("/titled.html", "<title>Titled</title><p>Titled"));
        String untitled = setPage("/untitled.html", "<p>No title");
        String untitledTitle = WebPage.titleFromUrl(untitled);
        onWebView(view -> {
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
                    if (title.equals(untitledTitle))
                        destroy(view);
                }
            });
            view.loadUrl(untitled);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    /** The result of a search, which the view reports as the web process answers. */
    @Test
    public void testDestroyInOnFindResultReceived() throws Exception {
        loadAndWait(setPage("/find.html", "<p>Find this, and find that"));
        onWebView(view -> view.findAllAsync("find", 0, (activeMatchOrdinal, numberOfMatches, hasMoreMatches) -> {
            if (numberOfMatches > 0)
                destroy(view);
        }));
        assertDestroyedAndTheAppGoesOn();
    }

    /** The progress of a load that the app asks for, which the view reports before the call returns. */
    @Test
    public void testDestroyInOnProgressChangedOfALoadOfTheApp() throws Exception {
        loadAndWait(setPage("/first.html", "<title>First</title>"));
        String next = setPage("/next.html", "<title>Next</title>");
        AtomicBoolean loading = new AtomicBoolean();
        AtomicBoolean destroyedWhileLoading = new AtomicBoolean();
        onWebView(view -> {
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(@NonNull WebView view, int newProgress) {
                    destroyedWhileLoading.set(loading.get());
                    destroy(view);
                }
            });
            loading.set(true);
            view.loadUrl(next);
            loading.set(false);
        });
        assertDestroyedAndTheAppGoesOn();
        assertTrue("The view did not report the progress before loadUrl returned", destroyedWhileLoading.get());
    }

    /** A request for credentials whose callback destroys the WebView and then fails. */
    @Test
    public void testDestroyInOnReceivedHttpAuthRequestThatThrows() throws Exception {
        String url = setProtectedPage("/protected.html");
        onWebView(view -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedHttpAuthRequest(@NonNull WebView view, @NonNull HttpAuthHandler handler,
                                                      @NonNull String host, @NonNull String realm) {
                    destroy(view);
                    throw new IllegalStateException("The client failed after it destroyed the WebView");
                }
            });
            view.loadUrl(url);
        });
        assertDestroyedAndTheAppGoesOn();
    }

    // A client that keeps the requests for credentials, and destroys the WebView when one is cancelled.
    private void loadProtectedPageAndKeepTheRequest() throws InterruptedException {
        String url = setProtectedPage("/protected.html");
        CountDownLatch asked = new CountDownLatch(1);
        onWebView(view -> {
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedHttpAuthRequest(@NonNull WebView view, @NonNull HttpAuthHandler handler,
                                                      @NonNull String host, @NonNull String realm) {
                    asked.countDown();
                }

                @Override
                public void onHttpAuthRequestCanceled(@NonNull WebView view, @NonNull HttpAuthHandler handler) {
                    destroy(view);
                }
            });
            view.loadUrl(url);
        });
        assertTrue("No request for credentials", asked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** WebKit cancels the request for credentials of a load that the app replaces. */
    @Test
    public void testDestroyInOnHttpAuthRequestCanceledByANewLoad() throws Exception {
        loadProtectedPageAndKeepTheRequest();
        String next = setPage("/next.html", "<title>Next</title>");
        onWebView(view -> view.loadUrl(next));
        assertDestroyedAndTheAppGoesOn();
    }

    /** The view cancels the requests for credentials of a page whose web process ended. */
    @Test
    public void testDestroyInOnHttpAuthRequestCanceledAsTheWebProcessEnds() throws Exception {
        loadProtectedPageAndKeepTheRequest();
        onWebView(WebView::terminateWebProcess);
        assertDestroyedAndTheAppGoesOn();
    }
}
