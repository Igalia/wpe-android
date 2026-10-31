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
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

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

import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Android 17's local network permission: without it, a page on the local network fails at once with
 * ERROR_LOCAL_NETWORK_ACCESS_DENIED, and it loads once the permission is granted.
 *
 * <p>testLocalNetworkPermission needs Android 17, a test app that targets it (build with -PtestTargetSdk=37), the
 * permission not granted (a new install), and a page on the local network, passed as an instrumentation argument, for
 * example {@code -e localNetworkUrl http://192.168.1.10:8000/}. Skipped otherwise. It grants the permission, so run it
 * again only after reinstalling. The other tests run on any Android version.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewLocalNetworkTest {
    private static final String LOGTAG = "WebViewLocalNetworkTest";
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int ANDROID_17 = 37;
    // Without the fix the load hangs until the kernel's connect timeout, about two minutes.
    private static final long ERROR_TIMEOUT_SECONDS = 20;
    private static final long TIMEOUT_SECONDS = 30;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private final BlockingQueue<WPEResourceError> errors = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> finishedUrls = new LinkedBlockingQueue<>();

    @Before
    public void setUp() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            activity.getWebView().setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    finishedUrls.add(url);
                }

                @Override
                public void onReceivedError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                            @NonNull WPEResourceError error) {
                    errors.add(error);
                }
            });
        });
    }

    @After
    public void tearDown() {
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
    }

    @Test
    public void testLocalNetworkPermission() throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("localNetworkUrl");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("No -e localNetworkUrl", url != null);
        assumeTrue("Needs Android 17", Build.VERSION.SDK_INT >= ANDROID_17);
        assumeTrue("Needs a test app that targets Android 17",
                   context.getApplicationInfo().targetSdkVersion >= ANDROID_17);
        assumeTrue("The app already has the permission", !hasPermission());

        // Without the permission: the load fails at once with its own error.
        long start = SystemClock.elapsedRealtime();
        loadUrl(url);
        WPEResourceError error = errors.poll(ERROR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The load did not fail", error);
        Log.i(LOGTAG, "Failed after " + (SystemClock.elapsedRealtime() - start) + " ms: " + error.getDescription());
        assertEquals(error.getDescription(), WebViewClient.ERROR_LOCAL_NETWORK_ACCESS_DENIED, error.getErrorCode());
        // The load that failed ends, then WebKit's error page loads.
        waitForFinishedPages(2);

        // Granted, as the person would in Android's dialog: the reload loads the page. Android no longer blocks the
        // connection, or the load would fail again.
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),
                                                                                              LOCAL_NETWORK_PERMISSION);
        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().reload());
        waitForFinishedPages(1);
        assertNull("The page failed with the permission", errors.poll());
    }

    /**
     * A page whose connection Android blocked gets ERROR_LOCAL_NETWORK_ACCESS_DENIED, with a description of its own.
     * The network process's report is made up here, for an address that refuses connections, so that the load fails
     * as it does when the network process ends a blocked connection.
     */
    @Test
    public void testErrorForABlockedHost() throws Exception {
        assumeTrue("The app already has the permission", !hasPermission());
        // Only this test uses this address: LocalNetworkAccess remembers it for a while.
        String host = "127.0.0.2";
        String url = "http://" + host + ":" + closedPort() + "/";
        LocalNetworkAccess.getInstance().onBlocked(host);
        loadUrl(url);

        WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The load did not fail", error);
        assertEquals(error.getDescription(), WebViewClient.ERROR_LOCAL_NETWORK_ACCESS_DENIED, error.getErrorCode());
        assertTrue(error.getDescription(), error.getDescription().contains(Uri.parse(url).getHost()));
    }

    /** A failure that a blocked connection does not cause, here a host name that does not resolve, keeps its code. */
    @Test
    public void testOtherErrorForABlockedHost() throws Exception {
        assumeTrue("The app already has the permission", !hasPermission());
        String host = "wpeview-blocked.invalid";
        LocalNetworkAccess.getInstance().onBlocked(host);
        loadUrl("http://" + host + "/");

        WPEResourceError error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The load did not fail", error);
        assertEquals(error.getDescription(), WebViewClient.ERROR_HOST_LOOKUP, error.getErrorCode());
    }

    private static boolean hasPermission() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    // A port on which nothing listens, so that connections to it are refused.
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void loadUrl(String url) {
        webViewActivityTestRule.getScenario().onActivity(activity -> activity.getWebView().loadUrl(url));
    }

    // The address that the test is given may be written in another way than WebKit reports it, so loads are counted.
    private void waitForFinishedPages(int count) throws InterruptedException {
        for (int i = 0; i < count; i++)
            assertNotNull("The page did not finish loading", finishedUrls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
}
