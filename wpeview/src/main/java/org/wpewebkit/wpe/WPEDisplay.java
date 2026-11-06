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

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

/**
 * WPEDisplay is an owning JNI proxy for a native WPEDisplay object.
 * <p>It has a screen for each Android display, kept up to date as displays come, change and go: when the device
 * turns or folds, or the display size setting changes.</p>
 */
@UiThread
public final class WPEDisplay {
    private static final float MILLIMETERS_PER_INCH = 25.4F;

    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    private final Context context;
    private final DisplayManager displayManager;
    private final SparseArray<WPEScreen> screens = new SparseArray<>();
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            updateScreen(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateScreen(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            removeScreen(displayId);
        }
    };

    public WPEDisplay(@NonNull Context context) {
        this.context = context.getApplicationContext();
        displayManager = this.context.getSystemService(DisplayManager.class);
        mNativePtr = nativeInit();
        if (mNativePtr == 0)
            return;

        // The default display goes first: WebKit takes the first screen as the primary one.
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (defaultDisplay != null)
            getScreen(defaultDisplay);
        for (Display display : displayManager.getDisplays())
            getScreen(display);
        displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
    }

    /**
     * Returns the screen of an Android display, or null if the display is gone. A display that the WPEDisplay has not
     * heard of yet gets its screen here.
     */
    public @Nullable WPEScreen getScreen(@NonNull Display display) {
        int displayId = display.getDisplayId();
        WPEScreen screen = screens.get(displayId);
        if (screen != null || mNativePtr == 0 || !display.isValid())
            return screen;

        screen = new WPEScreen(displayId);
        configureScreen(screen, display);
        screens.put(displayId, screen);
        nativeAddScreen(mNativePtr, screen.getNativePtr());
        return screen;
    }

    // Package-private: called from the DisplayListener inner class (avoids a synthetic accessor).
    void updateScreen(int displayId) {
        Display display = displayManager.getDisplay(displayId);
        if (display == null)
            return;
        WPEScreen screen = screens.get(displayId);
        if (screen != null)
            configureScreen(screen, display);
        else
            getScreen(display);
    }

    // Package-private: called from the DisplayListener inner class (avoids a synthetic accessor).
    void removeScreen(int displayId) {
        WPEScreen screen = screens.get(displayId);
        if (screen == null)
            return;
        screens.remove(displayId);
        if (mNativePtr != 0)
            nativeRemoveScreen(mNativePtr, screen.getNativePtr());
        screen.destroy();
    }

    // Pages see the size of the screen in CSS pixels: the size of the whole display in pixels divided by its density,
    // which they see as the device pixel ratio. The real size is the one of the whole display; the window metrics that
    // replace it in newer versions of Android are the ones of a window.
    @SuppressWarnings("deprecation")
    private void configureScreen(@NonNull WPEScreen screen, @NonNull Display display) {
        DisplayMetrics metrics = context.createDisplayContext(display).getResources().getDisplayMetrics();
        Point size = new Point();
        display.getRealSize(size);

        screen.setScale(metrics.density);
        screen.setSize(Math.round(size.x / metrics.density), Math.round(size.y / metrics.density));
        if (metrics.xdpi > 0 && metrics.ydpi > 0) {
            screen.setPhysicalSize(Math.round(size.x / metrics.xdpi * MILLIMETERS_PER_INCH),
                                   Math.round(size.y / metrics.ydpi * MILLIMETERS_PER_INCH));
        }
        screen.setRefreshRateHz(display.getRefreshRate());
    }

    public void destroy() {
        displayManager.unregisterDisplayListener(displayListener);
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
        for (int i = 0; i < screens.size(); i++)
            screens.valueAt(i).destroy();
        screens.clear();
    }

    private native long nativeInit();
    private native void nativeDestroy(long nativePtr);
    private native void nativeAddScreen(long nativePtr, long screenPtr);
    private native void nativeRemoveScreen(long nativePtr, long screenPtr);
}
