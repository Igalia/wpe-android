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

    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }
    private @Nullable Client client;

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
     * Sets the languages pages get, most preferred first, as BCP 47 tags: {@code navigator.languages} and the
     * {@code Accept-Language} header. WebKit keeps them for the whole process, so they apply to every web context,
     * but only the running processes of this one hear of them; {@link PreferredLanguages} calls this on every web
     * context for that reason.
     */
    void setPreferredLanguages(@NonNull String[] languages) {
        if (mNativePtr != 0)
            nativeSetPreferredLanguages(mNativePtr, languages);
    }

    private native long nativeInit(boolean automationMode);
    private native void nativeDestroy(long nativePtr);
    private native boolean nativeIsAutomationMode(long nativePtr);
    private native long nativeGetWebKitWebContextPtr(long nativePtr);
    private native void nativeSetPreferredLanguages(long nativePtr, String[] languages);
}
