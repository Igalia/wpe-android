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

/**
 * WPEToplevel is an owning JNI proxy for a native WPEToplevel instance.
 * Its lifetime is independent from the Java WPEDisplay wrapper, but the wrapped native display must outlive
 * operations performed through this object.
 */
public final class WPEToplevel {
    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    public WPEToplevel(@NonNull WPEDisplay display, @androidx.annotation.Nullable Surface surface) {
        nativePtr = nativeInit(display.getNativePtr(), surface);
    }

    public void onSurfaceCreated(@NonNull Surface surface) {
        if (nativePtr != 0) {
            nativeSetSurface(nativePtr, surface);
        }
    }

    public void onSurfaceDestroyed() {
        if (nativePtr != 0) {
            nativeSetSurface(nativePtr, null);
        }
    }

    public void destroy() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    public void setPhysicalSize(int width, int height) {
        if (nativePtr != 0) {
            nativeSetPhysicalSize(nativePtr, width, height);
        }
    }

    public int getWidth() { return nativePtr != 0 ? nativeGetWidth(nativePtr) : 0; }

    public int getHeight() { return nativePtr != 0 ? nativeGetHeight(nativePtr) : 0; }

    private native long nativeInit(long displayPtr, @androidx.annotation.Nullable Surface surface);
    private native void nativeDestroy(long nativePtr);
    private native void nativeSetPhysicalSize(long nativePtr, int width, int height);
    private native int nativeGetWidth(long nativePtr);
    private native int nativeGetHeight(long nativePtr);
    private native void nativeSetSurface(long nativePtr, @androidx.annotation.Nullable Surface surface);
}
