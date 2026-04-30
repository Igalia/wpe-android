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
 * WPEScreen is a borrowed JNI proxy for the native WPEScreen object owned by WPEDisplay.
 * This wrapper does not own the native pointer and must not outlive the parent WPEDisplay.
 */
public final class WPEScreen {
    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    // This constructor wraps a borrowed pointer returned by WPEDisplay.
    WPEScreen(long nativePtr) { this.mNativePtr = nativePtr; }

    public float getScale() { return nativeGetScale(mNativePtr); }

    public void setScale(float scale) { nativeSetScale(mNativePtr, scale); }

    public float getRefreshRateHz() { return nativeGetRefreshRateHz(mNativePtr); }

    public void setRefreshRateHz(float refreshRateHz) { nativeSetRefreshRateHz(mNativePtr, refreshRateHz); }

    void invalidate() { mNativePtr = 0; }

    private native float nativeGetScale(long nativePtr);
    private native void nativeSetScale(long nativePtr, float scale);
    private native float nativeGetRefreshRateHz(long nativePtr);
    private native void nativeSetRefreshRateHz(long nativePtr, float refreshRateHz);
}
