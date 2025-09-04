/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
 *   Author: Adrian Perez de Castro <aperez@igalia.com>
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

package org.wpewebkit.wpe;

import androidx.annotation.NonNull;

public class WKVersions {
    public static int WEBKIT_MAJOR = 0;
    public static int WEBKIT_MINOR = 0;
    public static int WEBKIT_MICRO = 0;
    public static @NonNull String WEBKIT = "";

    public static int GSTREAMER_MAJOR = 0;
    public static int GSTREAMER_MINOR = 0;
    public static int GSTREAMER_MICRO = 0;
    public static int GSTREAMER_NANO = 0;
    public static @NonNull String GSTREAMER = "";

    public static @NonNull String versionedAssets(@NonNull String componentName) {
        return componentName + "_" + WEBKIT + "_gst_" + GSTREAMER;
    }
}
