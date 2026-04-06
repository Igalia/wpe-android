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

import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WPEToplevel is an owning JNI proxy for a native WPEToplevel object.
 */
public final class WPEToplevel {
    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    public WPEToplevel(@NonNull WPEDisplay display, @Nullable Surface surface) {
        mNativePtr = nativeInit(display.getNativePtr(), surface);
    }

    public void setPhysicalSize(int width, int height) { nativeSetPhysicalSize(mNativePtr, width, height); }

    public void getSize(@NonNull int[] out) { nativeGetSize(mNativePtr, out); }

    public void onSurfaceCreated(@NonNull Surface surface) { setSurface(surface); }

    public void onSurfaceDestroyed() { setSurface(null); }

    public void setSurface(@Nullable Surface surface) { nativeSetSurface(mNativePtr, surface); }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    private native long nativeInit(long displayPtr, Surface surface);
    private native void nativeDestroy(long nativePtr);
    private native void nativeSetPhysicalSize(long nativePtr, int width, int height);
    private native void nativeGetSize(long nativePtr, int[] out);
    private native void nativeSetSurface(long nativePtr, Surface surface);
}
