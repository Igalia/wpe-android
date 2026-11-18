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
import android.os.Looper;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebKitWebContext is an owning JNI proxy for a native WebKitWebContext instance.
 */
public final class WebKitWebContext {
    public interface Client {
        @Nullable
        WebKitWebView createWebKitWebViewForAutomation();
    }

    /**
     * Hears when WebKit wants the location of the device for the pages of the context, on the main thread, and gives
     * it with {@link #updateGeolocationPosition} or {@link #geolocationFailed}, later.
     */
    public interface GeolocationListener {
        void onGeolocationStart(boolean highAccuracy);
        void onGeolocationStop();
        void onGeolocationHighAccuracyChanged(boolean highAccuracy);
    }

    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }
    private @Nullable Client client;
    private @Nullable GeolocationListener geolocationListener;

    public WebKitWebContext(boolean automationMode) {
        Context applicationContext = WPERuntime.getInstance().getApplicationContext();
        if (applicationContext == null)
            throw new IllegalStateException(
                "WPERuntime.initialize() must be called before creating a WebKitWebContext");
        mNativePtr = nativeInit(automationMode);
        PreferredLanguages.add(applicationContext, this);
    }

    public void destroy() {
        if (mNativePtr != 0) {
            PreferredLanguages.remove(this);
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    public boolean isAutomationMode() { return nativeIsAutomationMode(mNativePtr); }
    public void setClient(@Nullable Client client) { this.client = client; }

    @Keep
    private long createWebKitWebViewForAutomation() {
        Client currentClient = client;
        if (currentClient == null)
            return 0;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            WebKitWebView webView = currentClient.createWebKitWebViewForAutomation();
            return webView != null ? webView.getWebKitWebViewPtr() : 0;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong webViewPtr = new AtomicLong(0);
        MainLooperDispatcher.post(() -> {
            WebKitWebView webView = currentClient.createWebKitWebViewForAutomation();
            webViewPtr.set(webView != null ? webView.getWebKitWebViewPtr() : 0);
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        }

        return webViewPtr.get();
    }

    public long getWebKitWebContextPtr() { return nativeGetWebKitWebContextPtr(mNativePtr); }

    /**
     * Sets who gives the pages the location of the device. Without one, WebKit's start fails, as it would look for a
     * location service of desktop systems.
     */
    public void setGeolocationListener(@Nullable GeolocationListener listener) { geolocationListener = listener; }

    // WebKit asks on the main thread, where its main loop runs, and the listener starts the source right away.
    @Keep
    private void onGeolocationStart(boolean highAccuracy) {
        GeolocationListener listener = geolocationListener;
        if (listener != null)
            listener.onGeolocationStart(highAccuracy);
        else
            geolocationFailed("No location source");
    }

    @Keep
    private void onGeolocationStop() {
        GeolocationListener listener = geolocationListener;
        if (listener != null)
            listener.onGeolocationStop();
    }

    @Keep
    private void onGeolocationHighAccuracyChanged(boolean highAccuracy) {
        GeolocationListener listener = geolocationListener;
        if (listener != null)
            listener.onGeolocationHighAccuracyChanged(highAccuracy);
    }

    /**
     * Gives the pages that wait for the location a new position. The values that a position does not have are NaN, and
     * a time of 0, in seconds since the epoch, is the current one.
     */
    public void updateGeolocationPosition(double latitude, double longitude, double accuracy, double altitude,
                                          double altitudeAccuracy, double heading, double speed, long timeSeconds) {
        if (mNativePtr != 0)
            nativeUpdateGeolocationPosition(mNativePtr, latitude, longitude, accuracy, altitude, altitudeAccuracy,
                                            heading, speed, timeSeconds);
    }

    /** Tells the pages that wait for the location that there is none, which they get as POSITION_UNAVAILABLE. */
    public void geolocationFailed(@NonNull String message) {
        if (mNativePtr != 0)
            nativeGeolocationFailed(mNativePtr, message);
    }

    /**
     * Sets the languages pages get, most preferred first, as BCP 47 tags: {@code navigator.languages} and the
     * {@code Accept-Language} header. WebKit keeps them for the whole process, so they apply to every web context,
     * but only the running processes of this one hear of them; {@link PreferredLanguages} calls this on every web
     * context for that reason.
     */
    void setPreferredLanguages(@NonNull String[] languages) {
        if (mNativePtr != 0)
            nativeSetPreferredLanguages(mNativePtr, languages);
    }

    /**
     * Sets the origins whose pages may show notifications, and those that may not, as {@code scheme://host:port}
     * addresses, which each web process that starts from now on takes. The running ones keep what they had.
     */
    public void setNotificationPermissions(@NonNull String[] allowed, @NonNull String[] denied) {
        if (mNativePtr != 0)
            nativeSetNotificationPermissions(mNativePtr, allowed, denied);
    }

    private native long nativeInit(boolean automationMode);
    private native void nativeDestroy(long nativePtr);
    private native boolean nativeIsAutomationMode(long nativePtr);
    private native long nativeGetWebKitWebContextPtr(long nativePtr);
    private native void nativeSetPreferredLanguages(long nativePtr, String[] languages);
    private native void nativeUpdateGeolocationPosition(long nativePtr, double latitude, double longitude,
                                                        double accuracy, double altitude, double altitudeAccuracy,
                                                        double heading, double speed, long timeSeconds);
    private native void nativeGeolocationFailed(long nativePtr, String message);
    private native void nativeSetNotificationPermissions(long nativePtr, String[] allowed, String[] denied);
}
