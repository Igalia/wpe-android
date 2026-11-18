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
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WebKitWebContext;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Gives the pages of a {@link WebContext} the location of the device from its {@link WPELocationSource}, while WebKit
 * asks for it: WebKit has one geolocation manager per context, which starts when a page that was granted the location
 * waits for it, and stops once none does.
 */
final class ContextLocation implements WebKitWebContext.GeolocationListener {
    private static final String LOG_TAG = "WPELocation";

    // Package-private: read by the listener inner class (avoids a synthetic accessor).
    final WebKitWebContext webContext;
    private final Context appContext;
    private final Executor mainExecutor;
    // The source the app set, or null for Android's location services, made when first needed.
    private @Nullable WPELocationSource appSource;
    private @Nullable WPELocationSource androidSource;
    // The source that runs, while WebKit wants locations, and whether a page asked for high accuracy.
    private @Nullable WPELocationSource running;
    private boolean wanted;
    private boolean highAccuracy;
    // Counts the starts, so that the listener of a source that was stopped does nothing.
    private int start;
    private boolean destroyed;

    ContextLocation(@NonNull WebKitWebContext webContext, @NonNull Context appContext) {
        this.webContext = webContext;
        this.appContext = appContext;
        this.mainExecutor = appContext.getMainExecutor();
        webContext.setGeolocationListener(this);
    }

    void setSource(@Nullable WPELocationSource source) {
        if (source == appSource)
            return;
        stopRunning();
        appSource = source;
        if (wanted)
            startRunning();
    }

    void destroy() {
        destroyed = true;
        stopRunning();
        webContext.setGeolocationListener(null);
    }

    @Override
    public void onGeolocationStart(boolean highAccuracy) {
        wanted = true;
        this.highAccuracy = highAccuracy;
        stopRunning();
        startRunning();
    }

    @Override
    public void onGeolocationStop() {
        wanted = false;
        stopRunning();
    }

    @Override
    public void onGeolocationHighAccuracyChanged(boolean highAccuracy) {
        if (this.highAccuracy == highAccuracy)
            return;
        this.highAccuracy = highAccuracy;
        if (running != null) {
            stopRunning();
            startRunning();
        }
    }

    private void startRunning() {
        if (destroyed)
            return;
        WPELocationSource source = appSource;
        if (source == null) {
            if (androidSource == null)
                androidSource = new AndroidLocationSource(appContext);
            source = androidSource;
        }
        running = source;
        int thisStart = ++start;
        try {
            source.start(highAccuracy, new SourceListener(thisStart));
        } catch (RuntimeException exception) {
            // The pages that wait are told, later, as for any source that has no location.
            Log.e(LOG_TAG, "The location source failed to start", exception);
            running = null;
            deliver(thisStart, () -> webContext.geolocationFailed("The location source failed to start"));
        }
    }

    private void stopRunning() {
        WPELocationSource source = running;
        running = null;
        start++;
        if (source != null)
            source.stop();
    }

    // Hands what a source gives to WebKit on the main thread, always later, also from the source's start: WebKit
    // emits its start signal while it waits for the app to take it. Package-private: called by the listener.
    void deliver(int fromStart, @NonNull Runnable task) {
        mainExecutor.execute(() -> {
            if (fromStart == start && !destroyed)
                task.run();
        });
    }

    private final class SourceListener implements WPELocationSource.Listener {
        private final int fromStart;

        SourceListener(int fromStart) { this.fromStart = fromStart; }

        @Override
        public void onLocationChanged(@NonNull Location location) {
            Objects.requireNonNull(location, "location");
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            double accuracy = location.hasAccuracy() ? location.getAccuracy() : 0;
            boolean hasAltitude = location.hasAltitude();
            double altitude = hasAltitude ? location.getAltitude() : Double.NaN;
            // An approximate location keeps the vertical accuracy of the altitude that Android took out of it.
            double altitudeAccuracy =
                hasAltitude && location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : Double.NaN;
            double heading = location.hasBearing() ? location.getBearing() : Double.NaN;
            double speed = location.hasSpeed() ? location.getSpeed() : Double.NaN;
            // WebKit takes the time in whole seconds, and the current time for 0.
            long timeSeconds = Math.max(0, location.getTime() / 1000);
            deliver(fromStart,
                    ()
                        -> webContext.updateGeolocationPosition(latitude, longitude, accuracy, altitude,
                                                                altitudeAccuracy, heading, speed, timeSeconds));
        }

        @Override
        public void onLocationUnavailable(@NonNull String message) {
            Objects.requireNonNull(message, "message");
            deliver(fromStart, () -> webContext.geolocationFailed(message));
        }
    }
}
