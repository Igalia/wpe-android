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
 * This wrapper does not own the native pointer and must not outlive the parent WebKitWebView.
 */
public final class WPEView {
    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    // This constructor wraps a borrowed pointer returned by WebKitWebView.
    WPEView(long nativePtr) { this.nativePtr = nativePtr; }

    public void setToplevel(@Nullable WPEToplevel toplevel) {
        nativeSetToplevel(nativePtr, toplevel != null ? toplevel.getNativePtr() : 0);
    }

    public void resized(int width, int height) { nativeResized(nativePtr, width, height); }

    public void setMapped(boolean mapped) { nativeSetMapped(nativePtr, mapped); }

    public void dispatchTouchEvent(long time, int type, int pointerCount, int[] ids, float[] xs, float[] ys) {
        if (pointerCount <= 0 || ids.length < pointerCount || xs.length < pointerCount || ys.length < pointerCount)
            return;
        nativeDispatchTouchEvent(nativePtr, time, type, pointerCount, ids, xs, ys);
    }

    public void dispatchKeyEvent(long time, int type, int keyCode, int unicodeChar, int metaState) {
        nativeDispatchKeyEvent(nativePtr, time, type, keyCode, unicodeChar, metaState);
    }

    void invalidate() { nativePtr = 0; }

    private native void nativeSetToplevel(long nativePtr, long toplevelPtr);
    private native void nativeResized(long nativePtr, int width, int height);
    private native void nativeSetMapped(long nativePtr, boolean mapped);
    private native void nativeDispatchTouchEvent(long nativePtr, long time, int type, int pointerCount, int[] ids,
                                                 float[] xs, float[] ys);
    private native void nativeDispatchKeyEvent(long nativePtr, long time, int type, int keyCode, int unicodeChar,
                                               int metaState);
}
