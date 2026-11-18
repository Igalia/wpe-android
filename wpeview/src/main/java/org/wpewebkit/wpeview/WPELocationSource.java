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

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

/**
 * Where the pages of a {@link WebContext} get the location of the device from, set with
 * {@link WebContext#setLocationSource}. WebKit starts the source while a page that the app granted
 * {@link WPEPermissionRequest#RESOURCE_LOCATION} waits for a position or watches the location, and stops it once none
 * does. It gives every such page of the context the same locations. A page that is not shown gets none until it is.
 *
 * <p>By default a context uses Android's location services: the fused provider where the device has one, and else the
 * GPS or the network. They need the app to hold Android's {@code ACCESS_COARSE_LOCATION} or
 * {@code ACCESS_FINE_LOCATION} permission, which WPEView does not ask for, and give approximate locations with the
 * first alone. Without either permission, or with the location turned off on the device, the pages get a
 * {@code POSITION_UNAVAILABLE} error at once, and those that watch the location get it once it is turned on.
 */
public interface WPELocationSource {
    /** Takes the locations of a source, from any thread. */
    interface Listener {
        /**
         * Gives the pages a location: its latitude, longitude and accuracy, and its altitude with its vertical
         * accuracy, bearing, speed and time where it has them. A location without an accuracy reads as accurate to 0
         * meters.
         */
        void onLocationChanged(@NonNull Location location);

        /** Tells the pages that wait for a position that there is none, with a message for their developers. */
        void onLocationUnavailable(@NonNull String message);
    }

    /**
     * Starts giving locations to {@code listener}. {@code highAccuracy} tells whether a page asked for the most
     * accurate location it can get, with {@code enableHighAccuracy}; when that changes, the source is stopped and
     * started again with the new value.
     */
    @UiThread
    void start(boolean highAccuracy, @NonNull Listener listener);

    /** Stops giving locations. The listener of the last start does nothing any more. */
    @UiThread
    void stop();
}
