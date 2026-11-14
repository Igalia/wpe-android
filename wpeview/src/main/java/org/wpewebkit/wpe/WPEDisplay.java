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

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * WPEDisplay is an owning JNI proxy for a native WPEDisplay object.
 * <p>It has a screen for each Android display, kept up to date as displays come, change and go: when the device
 * turns or folds, or the display size setting changes. It also tells WebKit which kinds of input devices are
 * connected, and whether the app is in dark mode.</p>
 */
@UiThread
public final class WPEDisplay {
    private static final float MILLIMETERS_PER_INCH = 25.4F;

    // The kinds of input devices, as WPEAvailableInputDevices has them.
    private static final int INPUT_DEVICE_MOUSE = 1 << 0;
    private static final int INPUT_DEVICE_KEYBOARD = 1 << 1;
    private static final int INPUT_DEVICE_TOUCHSCREEN = 1 << 2;

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
    private final InputManager inputManager;
    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            updateInputDevices();
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            updateInputDevices();
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            updateInputDevices();
        }
    };

    // Pages see whether the app is in dark mode, with the prefers-color-scheme media feature, and the ones that allow a
    // dark color scheme get it. WebKit has one such setting for the whole display, so it follows the night mode of the
    // application, which the app may choose for itself with UiModeManager.setApplicationNightMode().
    private boolean darkMode;
    // The WebViews, which show the dark canvas color before the first frame of a page. Held weakly, so that a WebView
    // that is never destroyed is not kept alive.
    private final Set<DarkModeListener> darkModeListeners = Collections.newSetFromMap(new WeakHashMap<>());

    /** Hears when the app enters or leaves dark mode. */
    public interface DarkModeListener {
        void onDarkModeChanged();
    }
    private final ComponentCallbacks configurationCallbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            updateDarkMode(newConfig);
        }

        @Override
        public void onLowMemory() {}
    };

    public WPEDisplay(@NonNull Context context) {
        this.context = context.getApplicationContext();
        displayManager = this.context.getSystemService(DisplayManager.class);
        inputManager = this.context.getSystemService(InputManager.class);
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

        updateInputDevices();
        inputManager.registerInputDeviceListener(inputDeviceListener, new Handler(Looper.getMainLooper()));

        updateDarkMode(this.context.getResources().getConfiguration());
        this.context.registerComponentCallbacks(configurationCallbacks);
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

    // Pages learn from the kinds of input devices whether the primary pointer is coarse or fine and whether it can
    // hover: a touchscreen is coarse and cannot hover, while a mouse or a touchpad is fine and can, and is the primary
    // pointer while it is connected. A stylus goes with its touchscreen. There is always a keyboard, on the screen if
    // there is no other. Package-private: called from the InputDeviceListener inner class (avoids a synthetic
    // accessor).
    void updateInputDevices() {
        int devices = INPUT_DEVICE_KEYBOARD;
        for (int deviceId : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(deviceId);
            if (device == null || !device.isEnabled())
                continue;
            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN))
                devices |= INPUT_DEVICE_TOUCHSCREEN;
            if (device.supportsSource(InputDevice.SOURCE_MOUSE))
                devices |= INPUT_DEVICE_MOUSE;
        }
        if (mNativePtr != 0)
            nativeSetAvailableInputDevices(mNativePtr, devices);
    }

    /** Whether the app is in dark mode, as the pages see it. */
    public boolean isDarkMode() { return darkMode; }

    public void addDarkModeListener(@NonNull DarkModeListener listener) { darkModeListeners.add(listener); }

    public void removeDarkModeListener(@NonNull DarkModeListener listener) { darkModeListeners.remove(listener); }

    // Only a change goes to WebKit, which starts in light mode. Package-private: called from the ComponentCallbacks
    // inner class (avoids a synthetic accessor).
    void updateDarkMode(@NonNull Configuration configuration) {
        if (configuration.isNightModeActive() == darkMode)
            return;
        darkMode = !darkMode;
        if (mNativePtr != 0)
            nativeSetDarkMode(mNativePtr, darkMode);
        for (DarkModeListener listener : new ArrayList<>(darkModeListeners))
            listener.onDarkModeChanged();
    }

    public void destroy() {
        context.unregisterComponentCallbacks(configurationCallbacks);
        inputManager.unregisterInputDeviceListener(inputDeviceListener);
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
    private native void nativeSetAvailableInputDevices(long nativePtr, int devices);
    private native void nativeSetDarkMode(long nativePtr, boolean darkMode);
}
