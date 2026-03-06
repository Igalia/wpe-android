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

import android.os.Looper;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebKitWebContext is an owning JNI proxy for a native WebKitWebContext instance.
 * Automation view creation is the one intentional callback-oriented exception to the otherwise thin pointer-wrapper
 * model in this package.
 */
public final class WebKitWebContext {
    public interface Client {
        // Automation view creation is dispatched to the main looper.
        @Nullable
        WebKitWebView createWebKitWebViewForAutomation();
    }

    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }
    private @Nullable Client client;

    public WebKitWebContext(boolean automationMode) { mNativePtr = nativeInit(automationMode); }

    public void destroy() {
        if (mNativePtr != 0) {
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

    private native long nativeInit(boolean automationMode);
    private native void nativeDestroy(long nativePtr);
    private native boolean nativeIsAutomationMode(long nativePtr);
    private native long nativeGetWebKitWebContextPtr(long nativePtr);
}
