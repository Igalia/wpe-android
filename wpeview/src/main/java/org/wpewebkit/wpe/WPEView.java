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
import androidx.annotation.Nullable;

/**
 * WPEView is a borrowed JNI proxy for the native WPEView object owned by WebKitWebView.
 * This wrapper does not own the native pointer. Its methods do nothing once the WebKitWebView is destroyed.
 */
public final class WPEView {
    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    // This constructor wraps a borrowed pointer returned by WebKitWebView.
    WPEView(long nativePtr) { this.mNativePtr = nativePtr; }

    public void setToplevel(@Nullable WPEToplevel toplevel) {
        if (mNativePtr != 0)
            nativeSetToplevel(mNativePtr, toplevel != null ? toplevel.getNativePtr() : 0);
    }

    public void resized(int width, int height) {
        if (mNativePtr != 0)
            nativeResized(mNativePtr, width, height);
    }

    public void setMapped(boolean mapped) {
        if (mNativePtr != 0)
            nativeSetMapped(mNativePtr, mapped);
    }

    public void setVisible(boolean visible) {
        if (mNativePtr != 0)
            nativeSetVisible(mNativePtr, visible);
    }

    public void setFocused(boolean focused) {
        if (mNativePtr != 0)
            nativeSetFocused(mNativePtr, focused);
    }

    public void dispatchTouchEvent(long time, int type, int pointerCount, @NonNull int[] ids, @NonNull float[] xs,
                                   @NonNull float[] ys) {
        if (mNativePtr == 0 || pointerCount <= 0 || ids.length < pointerCount || xs.length < pointerCount ||
            ys.length < pointerCount)
            return;
        nativeDispatchTouchEvent(mNativePtr, time, type, pointerCount, ids, xs, ys);
    }

    public void dispatchKeyEvent(long time, int type, int keyCode, int unicodeChar, int metaState) {
        if (mNativePtr != 0)
            nativeDispatchKeyEvent(mNativePtr, time, type, keyCode, unicodeChar, metaState);
    }

    void invalidate() { mNativePtr = 0; }

    private native void nativeSetToplevel(long nativePtr, long toplevelPtr);
    private native void nativeResized(long nativePtr, int width, int height);
    private native void nativeSetMapped(long nativePtr, boolean mapped);
    private native void nativeSetVisible(long nativePtr, boolean visible);
    private native void nativeSetFocused(long nativePtr, boolean focused);
    private native void nativeDispatchTouchEvent(long nativePtr, long time, int type, int pointerCount, int[] ids,
                                                 float[] xs, float[] ys);
    private native void nativeDispatchKeyEvent(long nativePtr, long time, int type, int keyCode, int unicodeChar,
                                               int metaState);
}
