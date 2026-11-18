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

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

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

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Where granted pages get the location of the device from: the WPELocationSource of their WebContext, and Android's
 * location services by default. The pages show what they got in their title.
 */
@RunWith(AndroidJUnit4.class)
public class WebContextLocationTest {
    private static final long TIMEOUT_SECONDS = 30;

    private static final String LOCATE_PAGE =
        "<title>asking</title><script>navigator.geolocation.getCurrentPosition("
        + "p => { document.title = 'position ' + p.coords.latitude + ' ' + p.coords.longitude + ' '"
        + "+ p.coords.accuracy; }, e => { document.title = 'error ' + e.code; });</script>";
    private static final String LOCATE_ACCURATELY_PAGE =
        "<title>asking</title><script>navigator.geolocation.getCurrentPosition("
        + "p => { document.title = 'position ' + p.coords.latitude + ' ' + p.coords.longitude + ' '"
        + "+ p.coords.accuracy; }, e => { document.title = 'error ' + e.code; }, {enableHighAccuracy: true});</script>";
    private static final String ALTITUDE_PAGE =
        "<title>asking</title><script>navigator.geolocation.getCurrentPosition("
        + "p => { document.title = 'position ' + p.coords.altitude + ' ' + p.coords.altitudeAccuracy; },"
        + "e => { document.title = 'error ' + e.code; });</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> scenarioRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private HttpServer httpServer;

    // Grants every request, and keeps the titles of the page.
    private static class Client extends WebChromeClient {
        final BlockingQueue<String> titles = new LinkedBlockingQueue<>();

        @Override
        public void onPermissionRequest(@NonNull WebView view, @NonNull WPEPermissionRequest request) {
            request.grant();
        }

        @Override
        public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {
            titles.add(title);
        }
    }

    // Gives the location it was set when it starts, or that there is none.
    private static class TestSource implements WPELocationSource {
        final BlockingQueue<Boolean> starts = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> stops = new LinkedBlockingQueue<>();
        private final Location location;

        TestSource(Location location) { this.location = location; }

        @Override
        public void start(boolean highAccuracy, @NonNull Listener listener) {
            starts.add(highAccuracy);
            if (location != null)
                listener.onLocationChanged(location);
            else
                listener.onLocationUnavailable("No location in this test");
        }

        @Override
        public void stop() {
            stops.add(true);
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

    private Client loadWithSource(String body, WPELocationSource source) {
        Client client = new Client();
        HttpResponse response = new HttpResponse();
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setBody(body);
        String url = httpServer.setResponse("/locate.html", response);
        onMain(() -> {
            WebView view = getWebView();
            view.setWebChromeClient(client);
            if (source != null)
                view.getPage().getWebContext().setLocationSource(source);
            view.loadUrl(url);
        });
        return client;
    }

    private static Location location(String provider, double latitude, double longitude, float accuracy) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(accuracy);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }

    private static String waitForAnswer(BlockingQueue<String> titles) throws InterruptedException {
        while (true) {
            String title = titles.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("The page got no answer", title);
            if (title.startsWith("position") || title.startsWith("error"))
                return title;
        }
    }

    @Test
    public void grantedPageGetsTheLocationOfTheSource() throws Throwable {
        TestSource source = new TestSource(location("test", 42.88, -8.54, 25));
        Client client = loadWithSource(LOCATE_PAGE, source);

        assertEquals("position 42.88 -8.54 25", waitForAnswer(client.titles));
        assertEquals(Boolean.FALSE, source.starts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // The page needed one position, so WebKit stops the source.
        assertNotNull("The source did not stop", source.stops.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void highAccuracyStartsTheSourceForIt() throws Throwable {
        TestSource source = new TestSource(location("test", 1.5, 2.5, 3));
        Client client = loadWithSource(LOCATE_ACCURATELY_PAGE, source);

        assertEquals("position 1.5 2.5 3", waitForAnswer(client.titles));
        assertEquals(Boolean.TRUE, source.starts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void pageGetsTheAltitudeWithItsAccuracy() throws Throwable {
        Location location = location("test", 1, 2, 3);
        location.setAltitude(40);
        location.setVerticalAccuracyMeters(5);
        Client client = loadWithSource(ALTITUDE_PAGE, new TestSource(location));

        assertEquals("position 40 5", waitForAnswer(client.titles));
    }

    // As Android gives an approximate location.
    @Test
    public void verticalAccuracyWithoutAltitudeIsLeftOut() throws Throwable {
        Location location = location("test", 1, 2, 3);
        location.setVerticalAccuracyMeters(5);
        Client client = loadWithSource(ALTITUDE_PAGE, new TestSource(location));

        assertEquals("position null null", waitForAnswer(client.titles));
    }

    @Test
    public void noLocationIsPositionUnavailable() throws Throwable {
        TestSource source = new TestSource(null);
        Client client = loadWithSource(LOCATE_PAGE, source);

        assertEquals("error 2", waitForAnswer(client.titles));
    }

    // A page that watches the location gets it from the source set while it watches, which takes the place of the
    // one that ran.
    @Test
    public void newSourceTakesThePlaceOfTheOneThatRuns() throws Throwable {
        TestSource first = new TestSource(location("test", 1, 2, 3));
        Client client = loadWithSource("<title>asking</title><script>navigator.geolocation.watchPosition("
                                           + "p => { document.title = 'position ' + p.coords.latitude + ' '"
                                           + "+ p.coords.longitude + ' ' + p.coords.accuracy; },"
                                           + "e => { document.title = 'error ' + e.code; });</script>",
                                       first);
        assertEquals("position 1 2 3", waitForAnswer(client.titles));

        TestSource second = new TestSource(location("test", 4, 5, 6));
        onMain(() -> getWebView().getPage().getWebContext().setLocationSource(second));
        assertNotNull("The first source did not stop", first.stops.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, second.starts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("position 4 5 6", waitForAnswer(client.titles));
    }

    private static void shell(UiAutomation automation, String command) throws IOException {
        try (ParcelFileDescriptor output = automation.executeShellCommand(command);
             FileInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) {
                // Waits until the command is done.
            }
        }
    }

    // Android's own source, the default, with the fused provider replaced by a test one, which the test app may set
    // once it is the device's mock location app.
    @Test
    public void defaultSourceGivesTheLocationOfAndroid() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        String packageName = context.getPackageName();
        shell(automation, "pm grant " + packageName + " " + Manifest.permission.ACCESS_FINE_LOCATION);
        shell(automation, "pm grant " + packageName + " " + Manifest.permission.ACCESS_COARSE_LOCATION);
        shell(automation, "appops set " + packageName + " android:mock_location allow");
        shell(automation, "cmd location set-location-enabled true");
        LocationManager manager = context.getSystemService(LocationManager.class);
        String provider = LocationManager.FUSED_PROVIDER;
        manager.addTestProvider(provider, false, false, false, false, true, true, true,
                                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
        try {
            manager.setTestProviderEnabled(provider, true);
            assertTrue(manager.isProviderEnabled(provider));
            Location location = location(provider, 43.37, -8.4, 12);
            manager.setTestProviderLocation(provider, location);

            Client client = loadWithSource(LOCATE_PAGE, null);
            // The provider gives its location to new requests once they are made, so it is set again until the page
            // has one.
            String title = null;
            for (int attempt = 0; attempt < TIMEOUT_SECONDS && title == null; attempt++) {
                title = client.titles.poll(1, TimeUnit.SECONDS);
                if (title != null && !title.startsWith("position") && !title.startsWith("error"))
                    title = null;
                if (title == null)
                    manager.setTestProviderLocation(provider, location(provider, 43.37, -8.4, 12));
            }
            assertNotNull("The page got no answer", title);
            assertEquals("position 43.37 -8.4 12", title);
        } finally {
            manager.removeTestProvider(provider);
            shell(automation, "appops set " + packageName + " android:mock_location deny");
        }
    }

    // With the location turned off on the device, the default source has nothing to give.
    @Test
    public void defaultSourceWithTheLocationOffIsPositionUnavailable() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        // With the permission, which the test needs to reach the location being off.
        shell(automation, "pm grant " + context.getPackageName() + " " + Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager manager = context.getSystemService(LocationManager.class);
        boolean wasEnabled = manager.isLocationEnabled();
        shell(automation, "cmd location set-location-enabled false");
        try {
            assertFalse(manager.isLocationEnabled());
            Client client = loadWithSource(LOCATE_PAGE, null);
            assertEquals("error 2", waitForAnswer(client.titles));
        } finally {
            if (wasEnabled)
                shell(automation, "cmd location set-location-enabled true");
        }
    }
}
