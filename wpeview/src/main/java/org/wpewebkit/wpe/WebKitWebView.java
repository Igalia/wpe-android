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

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WebKitWebView is an owning JNI proxy for a native WebKitWebView instance.
 * It contains no Android UI logic, but it does act as the low-level event bridge for native WebKit signals.
 */
public final class WebKitWebView {
    protected long nativePtr = 0;
    private WPEView wpeView;

    public long getNativePtr() { return nativePtr; }

    /**
     * Creates a new WebKitWebView instance bound to the supplied native objects.
     * Size, mapping, and scale are driven explicitly through WPEView and WPEToplevel.
     */
    public WebKitWebView(@NonNull WPEDisplay display, @NonNull WebKitWebContext webContext,
                         @Nullable WPEToplevel toplevel, @Nullable WebKitNetworkSession networkSession,
                         @Nullable WebKitSettings settings) {
        nativePtr = nativeInit(
            display.getNativePtr(), webContext.getNativePtr(), toplevel != null ? toplevel.getNativePtr() : 0,
            networkSession != null ? networkSession.getNativePtr() : 0, settings != null ? settings.getNativePtr() : 0);
    }

    public void destroy() {
        if (nativePtr != 0) {
            if (wpeView != null) {
                wpeView.invalidate();
                wpeView = null;
            }
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    public void loadUrl(@NonNull String url) { nativeLoadUrl(nativePtr, url); }
    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        nativeLoadHtml(nativePtr, content, baseUri != null ? baseUri : "");
    }
    public void stopLoading() { nativeStopLoading(nativePtr); }
    public void reload() { nativeReload(nativePtr); }
    public void goBack() { nativeGoBack(nativePtr); }
    public void goForward() { nativeGoForward(nativePtr); }

    /**
     * Returns a borrowed WPEView handle owned by the wrapped native WebKitWebView.
     */
    public @NonNull WPEView getWPEView() {
        if (wpeView == null)
            wpeView = new WPEView(nativeGetWPEView(nativePtr));
        return wpeView;
    }

    public void setZoomLevel(double zoomLevel) { nativeSetZoomLevel(nativePtr, zoomLevel); }

    public interface JavascriptCallback {
        // Results are delivered on the main looper.
        void onResult(String value);
    }

    static final class EvalCallbackHolder {
        private final JavascriptCallback callback;
        EvalCallbackHolder(JavascriptCallback cb) { this.callback = cb; }
        @Keep
        void commitResult(String result) {
            MainLooperDispatcher.post(() -> callback.onResult(result));
        }
    }

    public void evaluateJavascript(@NonNull String script, @Nullable JavascriptCallback callback) {
        nativeEvaluateJavascript(nativePtr, script, callback != null ? new EvalCallbackHolder(callback) : null);
    }

    /**
     * Internal low-level listener interface for Layer 1 to receive native WebKit events.
     * Callbacks are delivered on the main looper.
     */
    public interface Listener {
        void onClose();
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onURLChanged(String uri);
        void onLoadProgress(double progress);
        void onTitleChanged(String title, boolean canGoBack, boolean canGoForward);
        void onReceivedHttpError(String uri, String method, String mimeType, int statusCode);
    }

    private Listener listener;

    public void setListener(Listener listener) { this.listener = listener; }

    @Keep
    private void onClose() {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(currentListener::onClose);
    }

    @Keep
    private void onLoadStarted(String url) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onPageStarted(url));
    }

    @Keep
    private void onLoadFinished(String url) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onPageFinished(url));
    }

    @Keep
    private void onUriChanged(String uri) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onURLChanged(uri));
    }

    @Keep
    private void onEstimatedLoadProgress(double progress) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onLoadProgress(progress));
    }

    @Keep
    private void onTitleChanged(String title, boolean canGoBack, boolean canGoForward) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onTitleChanged(title, canGoBack, canGoForward));
    }

    @Keep
    private void onReceivedHttpError(String uri, String method, String mimeType, int statusCode) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onReceivedHttpError(uri, method, mimeType, statusCode));
    }

    // Direct C-API JNI methods
    private native long nativeInit(long displayPtr, long webContextPtr, long toplevelPtr, long networkSessionPtr,
                                   long settingsPtr);
    private native void nativeDestroy(long nativePtr);
    private native void nativeLoadUrl(long nativePtr, String url);
    private native void nativeLoadHtml(long nativePtr, String content, String baseUri);
    private native void nativeStopLoading(long nativePtr);
    private native void nativeReload(long nativePtr);
    private native void nativeGoBack(long nativePtr);
    private native void nativeGoForward(long nativePtr);
    private native long nativeGetWPEView(long nativePtr);
    private native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    private native void nativeEvaluateJavascript(long nativePtr, String script,
                                                 @Nullable EvalCallbackHolder callbackHolder);
}
