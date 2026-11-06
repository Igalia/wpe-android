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

import android.graphics.Color;
import android.view.Surface;

import androidx.annotation.ColorInt;
import androidx.annotation.Keep;
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

    /** Returns the scale of the screen the toplevel is on, the ratio of physical to logical pixels. */
    public float getScale() { return nativeGetScale(mNativePtr); }

    public void onSurfaceCreated(@NonNull Surface surface) { setSurface(surface); }

    public void onSurfaceDestroyed() { setSurface(null); }

    public void setSurface(@Nullable Surface surface) { nativeSetSurface(mNativePtr, surface); }

    public void setWindowFocused(boolean focused) { nativeSetWindowFocused(mNativePtr, focused); }

    /** Moves the toplevel to the screen of the Android display that its window shows on. */
    public void setScreen(@NonNull WPEScreen screen) { nativeSetScreen(mNativePtr, screen.getNativePtr()); }

    /**
     * Receives WebKit's requests to enter and leave fullscreen, which the toplevel has already done when they arrive.
     */
    public interface Listener {
        void onFullscreenRequested(boolean fullscreen);
    }

    public void setListener(@Nullable Listener listener) {
        if (mNativePtr != 0)
            nativeSetListener(mNativePtr, listener != null ? new ListenerHolder(listener) : null);
    }

    /**
     * Sets whether the toplevel is fullscreen, without telling the listener. Leaving fullscreen this way takes the page
     * out of fullscreen.
     */
    public void setFullscreen(boolean fullscreen) {
        if (mNativePtr != 0)
            nativeSetFullscreen(mNativePtr, fullscreen);
    }

    public boolean isFullscreen() { return mNativePtr != 0 && nativeIsFullscreen(mNativePtr); }

    /**
     * Sets the color of the layer under the web content, kept across surface replacements. The layer is opaque, so
     * the alpha is ignored.
     */
    public void setBackgroundColor(@ColorInt int color) {
        if (mNativePtr != 0) {
            Color rgb = Color.valueOf(color);
            nativeSetBackgroundColor(mNativePtr, rgb.red(), rgb.green(), rgb.blue());
        }
    }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeSetListener(mNativePtr, null);
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    private static class ListenerHolder {
        private final Listener delegate;

        ListenerHolder(@NonNull Listener delegate) { this.delegate = delegate; }

        @Keep
        public void onFullscreenRequested(boolean fullscreen) {
            MainLooperDispatcher.post(() -> delegate.onFullscreenRequested(fullscreen));
        }
    }

    private native long nativeInit(long displayPtr, Surface surface);
    private native void nativeDestroy(long nativePtr);
    private native void nativeSetPhysicalSize(long nativePtr, int width, int height);
    private native void nativeGetSize(long nativePtr, int[] out);
    private native float nativeGetScale(long nativePtr);
    private native void nativeSetSurface(long nativePtr, Surface surface);
    private native void nativeSetWindowFocused(long nativePtr, boolean focused);
    private native void nativeSetScreen(long nativePtr, long screenPtr);
    private native void nativeSetFullscreen(long nativePtr, boolean fullscreen);
    private native boolean nativeIsFullscreen(long nativePtr);
    private native void nativeSetListener(long nativePtr, @Nullable ListenerHolder listener);
    private native void nativeSetBackgroundColor(long nativePtr, float red, float green, float blue);
}
