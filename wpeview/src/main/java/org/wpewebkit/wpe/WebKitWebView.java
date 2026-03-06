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

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WebKitWebView is an owning JNI proxy for a native WebKitWebView object.
 */
public final class WebKitWebView {
    private long mNativePtr = 0;
    private final WPEView wpeView;
    private final WebKitSettings settings;
    private final WebKitNetworkSession networkSession;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public long getNativePtr() { return mNativePtr; }

    /** Package-private: only used by WebKitWebContext for the automation create-web-view callback. */
    long getWebKitWebViewPtr() { return nativeGetWebKitWebViewPtr(mNativePtr); }

    public WebKitWebView(@NonNull WPEDisplay display, @NonNull WebKitWebContext context, @Nullable WPEToplevel toplevel,
                         @Nullable WebKitNetworkSession networkSession, @Nullable WebKitSettings settings) {
        this.networkSession = networkSession;
        this.settings = settings;
        mNativePtr = nativeInit(
            display.getNativePtr(), context.getWebKitWebContextPtr(), toplevel != null ? toplevel.getNativePtr() : 0,
            networkSession != null ? networkSession.getNativePtr() : 0, settings != null ? settings.getNativePtr() : 0);
        wpeView = new WPEView(nativeGetWPEView(mNativePtr));
    }

    public @NonNull WPEView getWPEView() { return wpeView; }

    public @Nullable WebKitSettings getSettings() { return settings; }

    public @Nullable WebKitNetworkSession getNetworkSession() { return networkSession; }

    public void loadUrl(@NonNull String url) {
        if (mNativePtr != 0) {
            nativeLoadUrl(mNativePtr, url);
        }
    }

    public void loadHtml(@NonNull String content, @NonNull String baseUri) {
        if (mNativePtr != 0) {
            nativeLoadHtml(mNativePtr, content, baseUri);
        }
    }

    public void stopLoading() {
        if (mNativePtr != 0) {
            nativeStopLoading(mNativePtr);
        }
    }

    public void reload() {
        if (mNativePtr != 0) {
            nativeReload(mNativePtr);
        }
    }

    public void goBack() {
        if (mNativePtr != 0) {
            nativeGoBack(mNativePtr);
        }
    }

    public void goForward() {
        if (mNativePtr != 0) {
            nativeGoForward(mNativePtr);
        }
    }

    public void setZoomLevel(double zoomLevel) { nativeSetZoomLevel(mNativePtr, zoomLevel); }

    public void evaluateJavascript(@NonNull String script, @Nullable EvalCallback callback) {
        if (mNativePtr == 0) {
            if (callback != null) {
                callback.onResult("null");
            }
            return;
        }
        nativeEvaluateJavascript(mNativePtr, script, callback != null ? new EvalCallbackHolder(callback) : null);
    }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
        wpeView.invalidate();
    }

    public interface EvalCallback {
        void onResult(String result);
    }

    public interface Listener {
        void onClose();
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onURLChanged(String uri);
        void onLoadProgress(double progress);
        void onTitleChanged(String title, boolean canGoBack, boolean canGoForward);
        void onReceivedHttpError(String uri, String method, String mimeType, int statusCode);
    }

    private @Nullable Listener listener;

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    @Keep
    private void onClose() {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(currentListener::onClose);
    }

    @Keep
    private void onLoadStarted(String url) {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(() -> currentListener.onPageStarted(url));
    }

    @Keep
    private void onLoadFinished(String url) {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(() -> currentListener.onPageFinished(url));
    }

    @Keep
    private void onUriChanged(String uri) {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(() -> currentListener.onURLChanged(uri));
    }

    @Keep
    private void onEstimatedLoadProgress(double progress) {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(() -> currentListener.onLoadProgress(progress));
    }

    @Keep
    private void onTitleChanged(String title, boolean canGoBack, boolean canGoForward) {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(() -> currentListener.onTitleChanged(title, canGoBack, canGoForward));
    }

    @Keep
    private void onReceivedHttpError(String uri, String method, String mimeType, int statusCode) {
        Listener currentListener = listener;
        if (currentListener != null)
            mMainHandler.post(() -> currentListener.onReceivedHttpError(uri, method, mimeType, statusCode));
    }

    private static class EvalCallbackHolder {
        private final EvalCallback callback;
        private final Handler handler;

        EvalCallbackHolder(EvalCallback callback) {
            this.callback = callback;
            this.handler = new Handler(Looper.getMainLooper());
        }

        @Keep
        public void commitResult(String result) {
            handler.post(() -> callback.onResult(result));
        }
    }

    private native long nativeInit(long displayPtr, long contextPtr, long toplevelPtr, long networkSessionPtr,
                                   long settingsPtr);
    private native void nativeDestroy(long nativePtr);
    private native void nativeLoadUrl(long nativePtr, String url);
    private native void nativeLoadHtml(long nativePtr, String content, String baseUri);
    private native void nativeStopLoading(long nativePtr);
    private native void nativeReload(long nativePtr);
    private native void nativeGoBack(long nativePtr);
    private native void nativeGoForward(long nativePtr);
    private native long nativeGetWPEView(long nativePtr);
    private native long nativeGetWebKitWebViewPtr(long nativePtr);
    private native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    private native void nativeEvaluateJavascript(long nativePtr, String script,
                                                 @Nullable EvalCallbackHolder callbackHolder);
}
