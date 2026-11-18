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
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The location source of a {@link WebContext} that the app gave none: Android's location services, as
 * {@link WPELocationSource} tells.
 */
final class AndroidLocationSource implements WPELocationSource {
    // How often a page that watches gets a new location, with and without high accuracy, as the provider allows.
    private static final long HIGH_ACCURACY_INTERVAL_MILLIS = 1000;
    private static final long INTERVAL_MILLIS = 5000;

    private final Context context;
    private @Nullable LocationManager locationManager;
    private @Nullable LocationListener locationListener;

    AndroidLocationSource(@NonNull Context context) { this.context = context; }

    // The app holds the permission, which is checked first: WPEView does not ask for it, and does not declare it. A
    // provider that is turned off is still asked for updates, which come once it is turned on: the pages that watch the
    // location then get it, as WebKit does not start the source again for them.
    @SuppressLint("MissingPermission")
    @Override
    public void start(boolean highAccuracy, @NonNull Listener listener) {
        stop();
        LocationManager manager = context.getSystemService(LocationManager.class);
        boolean fine =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                         PackageManager.PERMISSION_GRANTED;
        if (manager == null || (!fine && !coarse)) {
            listener.onLocationUnavailable("The app may not use the location of the device");
            return;
        }
        String provider = provider(manager, highAccuracy && fine);
        if (provider == null) {
            listener.onLocationUnavailable("The device has no location provider");
            return;
        }
        LocationRequest request =
            new LocationRequest.Builder(highAccuracy ? HIGH_ACCURACY_INTERVAL_MILLIS : INTERVAL_MILLIS)
                .setQuality(highAccuracy ? LocationRequest.QUALITY_HIGH_ACCURACY
                                         : LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                .build();
        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                listener.onLocationChanged(location);
            }

            @Override
            public void onProviderDisabled(@NonNull String disabledProvider) {
                listener.onLocationUnavailable("The location is turned off on the device");
            }
        };
        try {
            manager.requestLocationUpdates(provider, request, context.getMainExecutor(), locationListener);
        } catch (SecurityException | IllegalArgumentException exception) {
            listener.onLocationUnavailable("The location of the device cannot be read: " + exception.getMessage());
            return;
        }
        this.locationManager = manager;
        this.locationListener = locationListener;
        if (!manager.isLocationEnabled() || !manager.isProviderEnabled(provider))
            listener.onLocationUnavailable("The location is turned off on the device");
    }

    @Override
    public void stop() {
        if (locationManager != null && locationListener != null)
            locationManager.removeUpdates(locationListener);
        locationManager = null;
        locationListener = null;
    }

    // The fused provider combines the others where the device has it. Otherwise the GPS for high accuracy, which needs
    // the fine location permission, and else the network, which answers faster and indoors. One that is turned on goes
    // first; with none, the one that would be chosen once the location is turned on.
    @Nullable
    private static String provider(@NonNull LocationManager manager, boolean preciseAndAccurate) {
        String[] order = preciseAndAccurate
                             ? new String[] {LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER,
                                             LocationManager.NETWORK_PROVIDER}
                             : new String[] {LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
                                             LocationManager.GPS_PROVIDER};
        for (String provider : order) {
            if (manager.hasProvider(provider) && manager.isProviderEnabled(provider))
                return provider;
        }
        for (String provider : order) {
            if (manager.hasProvider(provider))
                return provider;
        }
        return null;
    }
}
