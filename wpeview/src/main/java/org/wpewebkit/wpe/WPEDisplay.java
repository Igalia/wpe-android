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

package org.wpewebkit.wpe;

import androidx.annotation.NonNull;

/**
 * WPEDisplay is an owning JNI proxy for a native WPEDisplay object.
 */
public final class WPEDisplay {
    private long mNativePtr = 0;
    private WPEScreen screen;
    public long getNativePtr() { return mNativePtr; }

    public WPEDisplay() { mNativePtr = nativeInit(); }

    public @NonNull WPEScreen getScreen() {
        if (screen == null) {
            screen = new WPEScreen(nativeGetScreen(mNativePtr));
        }
        return screen;
    }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
        if (screen != null) {
            screen.invalidate();
            screen = null;
        }
    }

    private native long nativeInit();
    private native void nativeDestroy(long nativePtr);
    private native long nativeGetScreen(long nativePtr);
}
