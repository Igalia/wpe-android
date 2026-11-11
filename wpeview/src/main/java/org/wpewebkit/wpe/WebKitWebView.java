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

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * WebKitWebView is an owning JNI proxy for a native WebKitWebView object.
 */
public final class WebKitWebView {
    // Mirrors WebKitScriptDialogType.
    public static final int SCRIPT_DIALOG_ALERT = 0;
    public static final int SCRIPT_DIALOG_CONFIRM = 1;
    public static final int SCRIPT_DIALOG_PROMPT = 2;
    public static final int SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM = 3;

    // Values mapped to WebKitSnapshotRegion by nativeCaptureSnapshot. WebView checks the region.
    public static final int SNAPSHOT_REGION_VISIBLE = 0;
    public static final int SNAPSHOT_REGION_FULL_DOCUMENT = 1;

    // Mirrors WebKitWebProcessTerminationReason.
    public static final int WEB_PROCESS_CRASHED = 0;
    public static final int WEB_PROCESS_EXCEEDED_MEMORY_LIMIT = 1;
    public static final int WEB_PROCESS_TERMINATED_BY_API = 2;

    private long mNativePtr = 0;
    private final WPEView wpeView;
    private final WPEInputMethodContext inputMethodContext;
    private final WebKitSettings settings;
    private final WebKitNetworkSession networkSession;
    // The downloads of the view's pages that wait for the listener to set their destination or cancel them.
    private final Set<WebKitDownload> undecidedDownloads = new HashSet<>();
    // The file chooser requests taken from WebKit and not answered yet.
    private final Set<Long> fileChoosers = new HashSet<>();
    // The color chooser requests taken from WebKit and not ended yet.
    private final Set<Long> colorChoosers = new HashSet<>();

    public long getNativePtr() { return mNativePtr; }

    long getWebKitWebViewPtr() { return nativeGetWebKitWebViewPtr(mNativePtr); }

    public WebKitWebView(@NonNull WPEDisplay display, @NonNull WebKitWebContext context, @Nullable WPEToplevel toplevel,
                         @Nullable WebKitNetworkSession networkSession, @Nullable WebKitSettings settings) {
        this.networkSession = networkSession;
        this.settings = settings;
        mNativePtr = nativeInit(
            display.getNativePtr(), context.getWebKitWebContextPtr(), toplevel != null ? toplevel.getNativePtr() : 0,
            networkSession != null ? networkSession.getNativePtr() : 0, settings != null ? settings.getNativePtr() : 0);
        wpeView = new WPEView(nativeGetWPEView(mNativePtr));
        inputMethodContext = new WPEInputMethodContext(WPEInputMethodContext.getForView(wpeView));
    }

    /**
     * Makes the view of a new window that the page in {@code relatedView} asks for, in {@link Listener#onCreateWindow}
     * of {@code relatedView}, which returns it: WebKit puts the page of the window only in a view made then. It shares
     * the display, the web context and the network session of {@code relatedView}.
     */
    public WebKitWebView(@NonNull WebKitWebView relatedView, @Nullable WebKitSettings settings) {
        if (relatedView.mNativePtr == 0)
            throw new IllegalStateException("The related WebKitWebView was destroyed");
        this.networkSession = relatedView.networkSession;
        this.settings = settings;
        mNativePtr = nativeInitRelated(relatedView.mNativePtr, settings != null ? settings.getNativePtr() : 0);
        wpeView = new WPEView(nativeGetWPEView(mNativePtr));
        inputMethodContext = new WPEInputMethodContext(WPEInputMethodContext.getForView(wpeView));
    }

    public @NonNull WPEView getWPEView() { return wpeView; }

    public @NonNull WPEInputMethodContext getInputMethodContext() { return inputMethodContext; }

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

    public void terminateWebProcess() {
        if (mNativePtr != 0) {
            nativeTerminateWebProcess(mNativePtr);
        }
    }

    public @Nullable byte[] saveState() { return mNativePtr != 0 ? nativeSaveState(mNativePtr) : null; }

    // Restores the Back/Forward list and loads its current page. Returns false if WebKit cannot read the state, or if
    // it has no page.
    public boolean restoreState(@NonNull byte[] state) {
        return mNativePtr != 0 && nativeRestoreState(mNativePtr, state);
    }

    public boolean canGoBack() { return mNativePtr != 0 && nativeCanGoBack(mNativePtr); }

    public boolean canGoForward() { return mNativePtr != 0 && nativeCanGoForward(mNativePtr); }

    public void setMuted(boolean muted) {
        if (mNativePtr != 0)
            nativeSetIsMuted(mNativePtr, muted);
    }

    public boolean isMuted() { return mNativePtr != 0 && nativeIsMuted(mNativePtr); }

    public void setZoomLevel(double zoomLevel) { nativeSetZoomLevel(mNativePtr, zoomLevel); }

    public void setBackgroundColor(@ColorInt int color) {
        if (mNativePtr != 0) {
            Color rgba = Color.valueOf(color);
            nativeSetBackgroundColor(mNativePtr, rgba.red(), rgba.green(), rgba.blue(), rgba.alpha());
        }
    }

    // A maxWidth or maxHeight of 0 or less leaves that side unlimited. WebView checks the arguments.
    @UiThread
    public void captureSnapshot(int region, int maxWidth, int maxHeight, @NonNull SnapshotCallback callback) {
        if (mNativePtr == 0) {
            MainLooperDispatcher.postLater(() -> callback.onResult(null));
            return;
        }
        nativeCaptureSnapshot(mNativePtr, region, maxWidth, maxHeight, new SnapshotCallbackHolder(callback));
    }

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
        // Nothing else would end the downloads that still wait for a destination. Those already started go on.
        for (WebKitDownload download : new ArrayList<>(undecidedDownloads))
            download.cancel();
        // Must run before nativeDestroy: the listener cleanup needs the live IM context.
        inputMethodContext.setListener(null);
        for (long requestPtr : new ArrayList<>(fileChoosers))
            fileChooserCancel(requestPtr);
        for (long requestPtr : new ArrayList<>(colorChoosers))
            colorChooserCancel(requestPtr);
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
        inputMethodContext.invalidate();
        wpeView.invalidate();
    }

    @FunctionalInterface
    public interface SnapshotCallback {
        void onResult(@Nullable Bitmap bitmap);
    }

    public interface EvalCallback {
        void onResult(@NonNull String result);
    }

    public interface Listener {
        void onClose();
        void onPageStarted(@NonNull String url);
        void onPageFinished(@NonNull String url);
        void onURLChanged(@NonNull String uri);
        void onLoadProgress(double progress);
        void onTitleChanged(@NonNull String title);
        void onHistoryChanged(@NonNull String uri, boolean canGoBack, boolean canGoForward);
        void onReceivedHttpError(@NonNull String uri, @NonNull String method, @NonNull String mimeType, int statusCode,
                                 boolean isMainFrame);
        void onReceivedError(@NonNull String uri, @NonNull String method, int errorCode, @NonNull String description);
        void onScriptDialog(long dialogPtr, int type, @Nullable String url, @Nullable String message,
                            @NonNull String defaultText);
        void onWebProcessTerminated(int reason);

        /**
         * Asks for the view of a new window that the page opens, made with
         * {@link #WebKitWebView(WebKitWebView, WebKitSettings)} before this returns, or null to open none.
         */
        @Nullable
        WebKitWebView onCreateWindow(boolean isUserGesture);

        boolean shouldOverrideUrlLoading(@NonNull String uri, @NonNull String method, boolean isMainFrame,
                                         boolean hasGesture, boolean isRedirect);
        void onDownloadStarted(@NonNull WebKitDownload download);

        /**
         * A file field of the page asks for files, on the main thread. Returns whether the request is taken, to be
         * answered later with {@link #fileChooserSelect} or {@link #fileChooserCancel}; one that is not is cancelled.
         */
        boolean onRunFileChooser(long requestPtr, @NonNull String[] mimeTypes, boolean selectMultiple);
        /**
         * A color field of the page asks for a color, on the main thread. Returns whether the request is taken, to be
         * ended later with {@link #colorChooserFinish} or {@link #colorChooserCancel}; one that is not is cancelled.
         */
        boolean onRunColorChooser(long requestPtr, @ColorInt int color, boolean supportsAlpha);
        /** The page ended a color chooser request that was taken, for example because the field went away. */
        void onColorChooserFinished(long requestPtr);

        // The scheme is the one of the address that is loaded, also when a proxy asks.
        void onHttpAuthRequest(long requestId, @NonNull String host, int port, @NonNull String scheme,
                               @NonNull String realm, boolean isProxy, boolean isRetry);
        void onHttpAuthRequestCancelled(long requestId);

        void onPlayingAudioChanged(boolean isPlayingAudio);
    }

    private @Nullable Listener listener;

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

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
    private void onTitleChanged(String title) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onTitleChanged(title));
    }

    @Keep
    private void onHistoryChanged(String uri, boolean canGoBack, boolean canGoForward) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onHistoryChanged(uri, canGoBack, canGoForward));
    }

    @Keep
    private void onReceivedHttpError(String uri, String method, String mimeType, int statusCode, boolean isMainFrame) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(
                () -> currentListener.onReceivedHttpError(uri, method, mimeType, statusCode, isMainFrame));
    }

    @Keep
    private void onLoadFailed(String uri, String method, int errorCode, String description) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onReceivedError(uri, method, errorCode, description));
    }

    @Keep
    private void onScriptDialog(long dialogPtr, int type, String url, String message, String defaultText) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onScriptDialog(dialogPtr, type, url, message, defaultText));
        else
            nativeScriptDialogClose(dialogPtr);
    }

    @Keep
    private void onWebProcessTerminated(int reason) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onWebProcessTerminated(reason));
    }

    // WebKit needs the view of the new window before this returns, so the listener is called right away. Like every
    // signal of the view, this comes on the main thread, which runs WebKit's main loop.
    @Keep
    private long onCreateWindow(boolean isUserGesture) {
        Listener currentListener = listener;
        WebKitWebView newView = currentListener != null ? currentListener.onCreateWindow(isUserGesture) : null;
        return newView != null && newView.mNativePtr != 0 ? newView.getWebKitWebViewPtr() : 0;
    }

    // WebKit waits for the answer, so it is given right away, on the main thread, where WebKit runs.
    @Keep
    private boolean shouldOverrideUrlLoading(String uri, String method, boolean isMainFrame, boolean hasGesture,
                                             boolean isRedirect) {
        Listener currentListener = listener;
        return currentListener != null &&
            currentListener.shouldOverrideUrlLoading(uri, method, isMainFrame, hasGesture, isRedirect);
    }

    // The download waits until the listener sets its destination or cancels it, maybe later, or until the view is
    // destroyed.
    @Keep
    private void onDownloadStarted(long downloadPtr, String uri, String suggestedFilename, String mimeType,
                                   long contentLength, boolean hasGesture) {
        WebKitDownload download =
            new WebKitDownload(downloadPtr, uri, suggestedFilename, mimeType, contentLength, hasGesture);
        Listener currentListener = listener;
        if (currentListener == null) {
            download.cancel();
            return;
        }
        undecidedDownloads.add(download);
        download.setDecidedCallback(() -> undecidedDownloads.remove(download));
        MainLooperDispatcher.post(() -> currentListener.onDownloadStarted(download));
    }

    /**
     * Answers a script dialog reported via {@link Listener#onScriptDialog}. Must be paired with
     *  {@link #scriptDialogClose}. For prompts, {@code text} carries the user's input.
     */
    public void scriptDialogConfirm(long dialogPtr, boolean confirm, @Nullable String text) {
        nativeScriptDialogConfirm(dialogPtr, confirm, text);
    }

    /** Releases the native script dialog reported via {@link Listener#onScriptDialog}. */
    public void scriptDialogClose(long dialogPtr) { nativeScriptDialogClose(dialogPtr); }

    // Called by WebKit on the main thread, where its main loop runs. The request is taken first, so that it is
    // cancelled if anything fails.
    @Keep
    private void onRunFileChooser(long requestPtr, String[] mimeTypes, boolean selectMultiple) {
        fileChoosers.add(requestPtr);
        boolean taken = false;
        try {
            Listener currentListener = listener;
            taken = currentListener != null && currentListener.onRunFileChooser(requestPtr, mimeTypes, selectMultiple);
        } finally {
            if (!taken)
                fileChooserCancel(requestPtr);
        }
    }

    /**
     * Answers a file chooser request reported via {@link Listener#onRunFileChooser} with the paths of local files. A
     * request that was already answered, or cancelled when the view was destroyed, is left alone.
     */
    public void fileChooserSelect(long requestPtr, @NonNull String[] paths) {
        if (fileChoosers.remove(requestPtr))
            nativeFileChooserSelect(requestPtr, paths);
    }

    /** Cancels a file chooser request reported via {@link Listener#onRunFileChooser}, if it is not answered yet. */
    public void fileChooserCancel(long requestPtr) {
        if (fileChoosers.remove(requestPtr))
            nativeFileChooserCancel(requestPtr);
    }

    // Called by WebKit on the main thread, where its main loop runs. The request is taken first, so that it is
    // cancelled if anything fails.
    @Keep
    private void onRunColorChooser(long requestPtr, int color, boolean supportsAlpha) {
        colorChoosers.add(requestPtr);
        boolean taken = false;
        try {
            Listener currentListener = listener;
            taken = currentListener != null && currentListener.onRunColorChooser(requestPtr, color, supportsAlpha);
        } finally {
            if (!taken)
                colorChooserCancel(requestPtr);
        }
    }

    // WebKit tells the end of every request, also of the ones ended here, which are not reported again.
    @Keep
    private void onColorChooserFinished(long requestPtr) {
        if (!colorChoosers.remove(requestPtr))
            return;
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onColorChooserFinished(requestPtr);
    }

    /** Gives the field of a color chooser request that is not ended a color, which the page gets at once. */
    public void colorChooserSetColor(long requestPtr, @ColorInt int color) {
        if (colorChoosers.contains(requestPtr)) {
            Color rgba = Color.valueOf(color);
            nativeColorChooserSetColor(requestPtr, rgba.red(), rgba.green(), rgba.blue(), rgba.alpha());
        }
    }

    /** Ends a color chooser request with the color set last, if it is not ended yet. */
    public void colorChooserFinish(long requestPtr) {
        if (colorChoosers.remove(requestPtr))
            nativeColorChooserFinish(requestPtr);
    }

    /** Ends a color chooser request with the color its field had, if it is not ended yet. */
    public void colorChooserCancel(long requestPtr) {
        if (colorChoosers.remove(requestPtr))
            nativeColorChooserCancel(requestPtr);
    }

    @Keep
    private void onAuthenticate(long requestId, String host, int port, String scheme, String realm, boolean isProxy,
                                boolean isRetry) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(
                () -> currentListener.onHttpAuthRequest(requestId, host, port, scheme, realm, isProxy, isRetry));
        else
            nativeAuthenticationCancel(mNativePtr, requestId);
    }

    @Keep
    private void onAuthenticationCancelled(long requestId) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onHttpAuthRequestCancelled(requestId));
    }

    @Keep
    private void onIsPlayingAudioChanged(boolean isPlayingAudio) {
        Listener currentListener = listener;
        if (currentListener != null)
            MainLooperDispatcher.post(() -> currentListener.onPlayingAudioChanged(isPlayingAudio));
    }

    /**
     * Answers a request reported via {@link Listener#onHttpAuthRequest} with these credentials. Only the first answer
     * counts, and none counts once WebKit cancelled the request, because its load ended or another one started.
     */
    public void authenticate(long requestId, @NonNull String username, @NonNull String password) {
        if (mNativePtr != 0)
            nativeAuthenticate(mNativePtr, requestId, username, password);
    }

    /**
     * Answers a request reported via {@link Listener#onHttpAuthRequest} without credentials, so that the load goes on
     * and shows what the server sent with its request for them, such as its 401 page.
     */
    public void cancelAuthentication(long requestId) {
        if (mNativePtr != 0)
            nativeAuthenticationCancel(mNativePtr, requestId);
    }

    private static class SnapshotCallbackHolder {
        private final SnapshotCallback callback;

        SnapshotCallbackHolder(@NonNull SnapshotCallback callback) { this.callback = callback; }

        // Called from the native snapshot thread, or from the main thread if that thread could not be started, which
        // fills the returned bitmap's pixels in place. commitResult can be called from any thread: it posts the
        // callback to the main thread.
        @Keep
        public @Nullable Bitmap createBitmap(int width, int height) {
            try {
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError | RuntimeException error) {
                Log.e("WPEAndroid", "Cannot create snapshot bitmap", error);
                return null;
            }
        }

        @Keep
        public void commitResult(@Nullable Bitmap bitmap) {
            MainLooperDispatcher.postLater(() -> callback.onResult(bitmap));
        }
    }

    private static class EvalCallbackHolder {
        private final EvalCallback callback;

        EvalCallbackHolder(EvalCallback callback) { this.callback = callback; }

        @Keep
        public void commitResult(String result) {
            MainLooperDispatcher.post(() -> callback.onResult(result));
        }
    }

    private native long nativeInit(long displayPtr, long contextPtr, long toplevelPtr, long networkSessionPtr,
                                   long settingsPtr);
    private native long nativeInitRelated(long relatedPtr, long settingsPtr);
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
    private native void nativeSetBackgroundColor(long nativePtr, float red, float green, float blue, float alpha);
    private native void nativeCaptureSnapshot(long nativePtr, int region, int maxWidth, int maxHeight,
                                              @NonNull SnapshotCallbackHolder callbackHolder);
    private native void nativeEvaluateJavascript(long nativePtr, String script,
                                                 @Nullable EvalCallbackHolder callbackHolder);
    private native void nativeScriptDialogConfirm(long dialogPtr, boolean confirm, @Nullable String text);
    private native void nativeScriptDialogClose(long dialogPtr);
    private native void nativeTerminateWebProcess(long nativePtr);
    private native byte[] nativeSaveState(long nativePtr);
    private native boolean nativeRestoreState(long nativePtr, @NonNull byte[] state);
    private native boolean nativeCanGoBack(long nativePtr);
    private native boolean nativeCanGoForward(long nativePtr);
    private native void nativeFileChooserSelect(long requestPtr, @NonNull String[] paths);
    private native void nativeFileChooserCancel(long requestPtr);
    private native void nativeColorChooserSetColor(long requestPtr, float red, float green, float blue, float alpha);
    private native void nativeColorChooserFinish(long requestPtr);
    private native void nativeColorChooserCancel(long requestPtr);
    private native void nativeAuthenticate(long nativePtr, long requestId, String username, String password);
    private native void nativeAuthenticationCancel(long nativePtr, long requestId);
    private native void nativeSetIsMuted(long nativePtr, boolean muted);
    private native boolean nativeIsMuted(long nativePtr);
}
