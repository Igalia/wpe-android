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

/**
 * WPEScreen is an owning JNI proxy for the native WPEScreen of an Android display. WPEDisplay makes one for each
 * display, keeps it up to date, and destroys it when the display goes.
 */
public final class WPEScreen {
    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    // WebKit takes 0 for no screen, while Android numbers its first display 0: the id of a screen is the id of its
    // display plus one.
    WPEScreen(int displayId) { mNativePtr = nativeInit(displayId + 1); }

    /** Returns the width of the screen in logical pixels, the CSS pixels of pages, or -1 if it is not known. */
    public int getWidth() { return mNativePtr != 0 ? nativeGetWidth(mNativePtr) : -1; }

    /** Returns the height of the screen in logical pixels, the CSS pixels of pages, or -1 if it is not known. */
    public int getHeight() { return mNativePtr != 0 ? nativeGetHeight(mNativePtr) : -1; }

    void setSize(int width, int height) {
        if (mNativePtr != 0)
            nativeSetSize(mNativePtr, width, height);
    }

    // The size in millimeters.
    void setPhysicalSize(int width, int height) {
        if (mNativePtr != 0)
            nativeSetPhysicalSize(mNativePtr, width, height);
    }

    public float getScale() { return mNativePtr != 0 ? nativeGetScale(mNativePtr) : 1; }

    void setScale(float scale) {
        if (mNativePtr != 0)
            nativeSetScale(mNativePtr, scale);
    }

    public float getRefreshRateHz() { return mNativePtr != 0 ? nativeGetRefreshRateHz(mNativePtr) : 0; }

    void setRefreshRateHz(float refreshRateHz) {
        if (mNativePtr != 0)
            nativeSetRefreshRateHz(mNativePtr, refreshRateHz);
    }

    void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    private native long nativeInit(int id);
    private native void nativeDestroy(long nativePtr);
    private native int nativeGetWidth(long nativePtr);
    private native int nativeGetHeight(long nativePtr);
    private native void nativeSetSize(long nativePtr, int width, int height);
    private native void nativeSetPhysicalSize(long nativePtr, int width, int height);
    private native float nativeGetScale(long nativePtr);
    private native void nativeSetScale(long nativePtr, float scale);
    private native float nativeGetRefreshRateHz(long nativePtr);
    private native void nativeSetRefreshRateHz(long nativePtr, float refreshRateHz);
}
