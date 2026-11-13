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

package org.wpewebkit.wpeview;

import android.net.Uri;
import android.os.Looper;
import android.util.LongSparseArray;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.wpe.LocalNetworkAccess;
import org.wpewebkit.wpe.WPEInputMethodContext;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitDownload;
import org.wpewebkit.wpe.WebKitSettings;
import org.wpewebkit.wpe.WebKitWebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/**
 * The page that a WebView shows: WebKit's web view with its settings and input method context, the state of its
 * loads, history, title and sound, and the requests of the page that wait for an answer, such as JavaScript dialogs,
 * file and color fields and HTTP authentication. It uses no Android context but the application's, so it does not
 * depend on an activity. Once it is destroyed, it reports nothing more, and its methods no longer reach WebKit.
 */
@UiThread
final class WebPage {
    /** Receives what the page reports, on the main thread. */
    interface Delegate {
        void onCloseWindow();
        void onPageStarted(@NonNull String url);
        void onPageFinished(@NonNull String url);
        void onProgressChanged(int progress);
        void onReceivedTitle(@NonNull String title);
        void doUpdateVisitedHistory(@NonNull String url);
        void onReceivedHttpError(@NonNull WPEResourceRequest request, @NonNull WPEResourceResponse response);
        void onReceivedError(@NonNull WPEResourceRequest request, @NonNull WPEResourceError error);
        /** The dialog must be answered, by the app or the user, or cancelled. */
        void onScriptDialog(@NonNull ScriptDialog dialog);
        void onWebProcessTerminated(int reason);
        /** Returns the page made for the new window with {@link #WebPage(WebPage)} in this call, or null for none. */
        @Nullable
        WebPage onCreateWindow(boolean isUserGesture);
        boolean shouldOverrideUrlLoading(@NonNull WPEResourceRequest request);
        void onDownloadStart(@NonNull WPEDownload download);
        /** Returns whether the request is taken. One that is not is cancelled. */
        boolean onShowFileChooser(@NonNull WebChromeClient.FileChooserParams params,
                                  @NonNull WPECallback<String[]> callback);
        /** Returns whether the request is taken. One that is not is cancelled. */
        boolean onShowColorChooser(@NonNull WebChromeClient.ColorChooserParams params,
                                   @NonNull WebChromeClient.ColorChooserCallback callback);
        void onHideColorChooser();
        void onReceivedHttpAuthRequest(@NonNull WebViewClient.HttpAuthHandler handler, @NonNull String host,
                                       @NonNull String realm);
        void onHttpAuthRequestCanceled(@NonNull WebViewClient.HttpAuthHandler handler);
        void onPlayingAudioChanged(boolean playingAudio);
    }

    private final WebContext context;
    private final WebKitSettings webKitSettings;
    private final WebSettings settings;
    // Package-private: used by the inner classes (avoids synthetic accessors).
    final WebKitWebView webKitWebView;
    @Nullable
    Delegate delegate;
    boolean destroyed;
    // The JavaScript dialogs that are not answered yet, which destroy() cancels.
    final ArrayList<ScriptDialog> pendingScriptDialogs = new ArrayList<>();
    // The request of the color field that the page has open, if any. WebKit has one at most.
    @Nullable
    ColorChooserResult colorChooser;
    // The requests for HTTP authentication that the delegate has not answered, by their id.
    final LongSparseArray<HttpAuthRequest> httpAuthRequests = new LongSparseArray<>();

    private @Nullable String originalUrl;
    String url = "about:blank";
    // The title of the page, empty while it has none.
    String title = "";
    // The last title the page reported, which getTitle returns.
    @Nullable
    String reportedTitle;
    int progress;
    boolean canGoBack;
    boolean canGoForward;
    // Whether the page plays audio, as it last reported.
    boolean playingAudio;
    // Whether the page was muted when it was destroyed.
    private boolean mutedWhenDestroyed;

    WebPage(@NonNull WebContext context) { this(context, null); }

    /** Makes the page of a new window that {@code opener} asks for, which shares its context. */
    WebPage(@NonNull WebPage opener) { this(opener.context, opener.webKitWebView); }

    // The page of a new window is related to the page of its opener, which asks for it.
    private WebPage(@NonNull WebContext context, @Nullable WebKitWebView openerView) {
        this.context = context;
        webKitSettings = new WebKitSettings();
        webKitSettings.setUserAgentString(context.getDefaultUserAgent());
        // The zoom level of the web view is the text zoom of WebSettings.
        webKitSettings.setZoomTextOnly(true);
        webKitWebView = openerView != null ? new WebKitWebView(openerView, webKitSettings)
                                           : new WebKitWebView(context.getWPEDisplay(), context.getWebKitWebContext(),
                                                               null, context.getWebKitNetworkSession(), webKitSettings);
        settings = new WebSettings(webKitSettings, webKitWebView, context.getApplicationContext());
        webKitWebView.setListener(new PageListener());
    }

    void setDelegate(@Nullable Delegate delegate) { this.delegate = delegate; }

    @NonNull
    WebKitWebView getWebKitWebView() {
        return webKitWebView;
    }

    // The proxies are borrowed from the WebKitWebView. They do nothing once the page is destroyed.
    @NonNull
    WPEView getWPEView() {
        return webKitWebView.getWPEView();
    }

    @NonNull
    WPEInputMethodContext getInputMethodContext() {
        return webKitWebView.getInputMethodContext();
    }

    boolean isDestroyed() { return destroyed; }

    /** The settings keep their last values once the page is destroyed, and changing them has no effect. */
    @NonNull
    WebSettings getSettings() {
        return settings;
    }

    void setBackgroundColor(@ColorInt int color) { webKitWebView.setBackgroundColor(color); }

    @NonNull
    String getUrl() {
        return url;
    }

    @Nullable
    String getOriginalUrl() {
        return originalUrl;
    }

    /** The title the page last reported, which is empty before the first one. */
    @NonNull
    String getTitle() {
        return reportedTitle != null ? reportedTitle : "";
    }

    int getProgress() { return progress; }

    boolean canGoBack() { return canGoBack; }

    boolean canGoForward() { return canGoForward; }

    boolean isPlayingAudio() { return playingAudio; }

    void setMuted(boolean muted) { webKitWebView.setMuted(muted); }

    /** Whether the page is muted. Once it is destroyed, whether it was then. */
    boolean isMuted() { return destroyed ? mutedWhenDestroyed : webKitWebView.isMuted(); }

    /**
     * Shows the address of a page without a title as android.webkit.WebView does: without "http://", "https://",
     * the user name and password, or "www.", without the "/" after a bare host name, and with spaces unescaped.
     */
    static @NonNull String titleFromUrl(@NonNull String url) {
        String title;
        if (url.startsWith("http://"))
            title = url.substring("http://".length());
        else if (url.startsWith("https://"))
            title = url.substring("https://".length());
        else
            return url;
        title = title.replaceFirst("^[^/?#]*@", "");
        if (title.startsWith("www."))
            title = title.substring("www.".length());
        if (title.endsWith("/") && title.indexOf('/') == title.length() - 1)
            title = title.substring(0, title.length() - 1);
        return title.replace("%20", " ");
    }

    // A page without a title has its address as its title, so the title changes with the title, and with the address
    // once a navigation is done, not when a load starts, as with android.webkit.WebView.
    void updateTitle() {
        String newTitle = title.isEmpty() ? titleFromUrl(url) : title;
        if (newTitle.equals(reportedTitle))
            return;
        reportedTitle = newTitle;
        if (delegate != null)
            delegate.onReceivedTitle(newTitle);
    }

    void loadUrl(@NonNull String url) {
        originalUrl = url;
        webKitWebView.loadUrl(url);
    }

    void loadHtml(@NonNull String content, @Nullable String baseUri) {
        originalUrl = baseUri;
        webKitWebView.loadHtml(content, baseUri);
    }

    void goBack() { webKitWebView.goBack(); }

    void goForward() { webKitWebView.goForward(); }

    void reload() { webKitWebView.reload(); }

    void stopLoading() { webKitWebView.stopLoading(); }

    void terminateWebProcess() { webKitWebView.terminateWebProcess(); }

    /** Returns null once the page is destroyed. */
    @Nullable
    byte[] saveState() {
        return webKitWebView.saveState();
    }

    boolean restoreState(@NonNull byte[] state) {
        if (!webKitWebView.restoreState(state))
            return false;
        canGoBack = webKitWebView.canGoBack();
        canGoForward = webKitWebView.canGoForward();
        return true;
    }

    void evaluateJavascript(@NonNull String script, @Nullable WebKitWebView.EvalCallback callback) {
        if (!destroyed)
            webKitWebView.evaluateJavascript(script, callback);
    }

    /** A maxWidth or maxHeight of 0 leaves that side unlimited. Gives null, later, once the page is destroyed. */
    void captureSnapshot(int region, int maxWidth, int maxHeight, @NonNull WebKitWebView.SnapshotCallback callback) {
        webKitWebView.captureSnapshot(region, maxWidth, maxHeight, callback);
    }

    /** Cancels the JavaScript dialogs that are not answered yet. */
    void cancelScriptDialogs() {
        while (!pendingScriptDialogs.isEmpty())
            pendingScriptDialogs.get(0).cancel();
    }

    void destroy() {
        if (destroyed)
            return;
        // Answer the dialogs while the page can still take the answers.
        cancelScriptDialogs();
        mutedWhenDestroyed = webKitWebView.isMuted();
        destroyed = true;
        // This also cancels the requests of file and color fields.
        webKitWebView.destroy();
        httpAuthRequests.clear();
        playingAudio = false;
        settings.detach();
        webKitSettings.destroy();
        // A request that the app still holds, such as an HTTP authentication waiting for credentials, keeps the page,
        // but no longer the view or its activity.
        delegate = null;
    }

    // WebKit is answered on the main thread, where its main loop runs. Returns whether the task was sent there.
    boolean runOnMainThread(@NonNull Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper())
            return false;
        context.getApplicationContext().getMainExecutor().execute(task);
        return true;
    }

    // The network process tells when Android blocked a connection before it ends it, so it is known by the time the
    // load fails because of it. Ending the connection fails the load with one of these codes; other failures, such as
    // a host name that does not resolve, have another reason.
    @NonNull
    WPEResourceError loadError(@NonNull Uri uri, int errorCode, @NonNull String description) {
        String host = uri.getHost();
        boolean endedConnection = errorCode == WebViewClient.ERROR_IO || errorCode == WebViewClient.ERROR_CONNECT ||
                                  errorCode == WebViewClient.ERROR_UNKNOWN;
        if (!endedConnection || host == null ||
            !LocalNetworkAccess.getInstance().isBlocked(context.getApplicationContext(), host))
            return new WPEResourceError(errorCode, description);
        // WebKit's description would say that the server ended the connection.
        return new WPEResourceError(WebViewClient.ERROR_LOCAL_NETWORK_ACCESS_DENIED,
                                    "Could not connect to " + host +
                                        ": the app does not have the local network permission");
    }

    class PageListener implements WebKitWebView.Listener {
        @Override
        public void onClose() {
            if (delegate != null)
                delegate.onCloseWindow();
        }

        @Override
        public void onPageStarted(@NonNull String startedUrl) {
            if (delegate != null)
                delegate.onPageStarted(startedUrl);
        }

        @Override
        public void onPageFinished(@NonNull String finishedUrl) {
            if (delegate != null)
                delegate.onPageFinished(finishedUrl);
        }

        @Override
        public void onURLChanged(@NonNull String uri) {
            url = uri;
        }

        @Override
        public void onLoadProgress(double estimatedProgress) {
            progress = (int)Math.round(estimatedProgress * 100);
            if (delegate != null)
                delegate.onProgressChanged(progress);
        }

        @Override
        public void onTitleChanged(@NonNull String newTitle) {
            title = newTitle;
            updateTitle();
        }

        @Override
        public void onHistoryChanged(@NonNull String uri, boolean goBack, boolean goForward) {
            canGoBack = goBack;
            canGoForward = goForward;
            if (delegate != null)
                delegate.doUpdateVisitedHistory(uri);
            updateTitle();
        }

        @Override
        public void onReceivedHttpError(@NonNull String uri, @NonNull String method, @NonNull String mimeType,
                                        int statusCode, boolean isMainFrame) {
            if (delegate == null)
                return;
            WPEResourceRequest request = new HttpResourceRequest(Uri.parse(uri), method, isMainFrame, false, false);
            WPEResourceResponse response = new WPEResourceResponse(mimeType, statusCode, Collections.emptyMap());
            delegate.onReceivedHttpError(request, response);
        }

        @Override
        public void onReceivedError(@NonNull String uri, @NonNull String method, int errorCode,
                                    @NonNull String description) {
            if (delegate == null)
                return;
            Uri parsedUri = Uri.parse(uri);
            delegate.onReceivedError(new HttpResourceRequest(parsedUri, method, true, false, false),
                                     loadError(parsedUri, errorCode, description));
        }

        @Override
        public void onScriptDialog(long dialogPtr, int type, @Nullable String dialogUrl, @Nullable String message,
                                   @NonNull String defaultText) {
            ScriptDialog dialog = new ScriptDialog(dialogPtr, type, dialogUrl, message, defaultText);
            pendingScriptDialogs.add(dialog);
            if (delegate != null)
                delegate.onScriptDialog(dialog);
            else
                dialog.cancel();
        }

        @Override
        public void onWebProcessTerminated(int reason) {
            if (delegate != null)
                delegate.onWebProcessTerminated(reason);
        }

        // WebKit can only put the page of the new window in a view made for it, in this call.
        @Override
        public @Nullable WebKitWebView onCreateWindow(boolean isUserGesture) {
            WebPage window = delegate != null ? delegate.onCreateWindow(isUserGesture) : null;
            // The window may have been destroyed meanwhile.
            return window != null && !window.destroyed ? window.webKitWebView : null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull String uri, @NonNull String method, boolean isMainFrame,
                                                boolean hasGesture, boolean isRedirect) {
            return delegate != null && delegate.shouldOverrideUrlLoading(new HttpResourceRequest(
                                           Uri.parse(uri), method, isMainFrame, hasGesture, isRedirect));
        }

        @Override
        public void onDownloadStarted(@NonNull WebKitDownload download) {
            WPEDownload wpeDownload = new WPEDownload(download);
            if (delegate != null)
                delegate.onDownloadStart(wpeDownload);
            else
                wpeDownload.cancel();
        }

        @Override
        public boolean onRunFileChooser(long requestPtr, @NonNull String[] mimeTypes, boolean selectMultiple) {
            return showFileChooser(requestPtr, mimeTypes, selectMultiple);
        }

        @Override
        public boolean onRunColorChooser(long requestPtr, int color, boolean supportsAlpha) {
            return showColorChooser(requestPtr, color, supportsAlpha);
        }

        @Override
        public void onColorChooserFinished(long requestPtr) {
            ColorChooserResult result = colorChooser;
            if (result == null || result.requestPtr != requestPtr)
                return;
            result.end();
            if (delegate != null)
                delegate.onHideColorChooser();
        }

        @Override
        public void onHttpAuthRequest(long requestId, @NonNull String host, int port, @NonNull String scheme,
                                      @NonNull String realm, boolean isProxy, boolean isRetry) {
            HttpAuthRequest handler = new HttpAuthRequest(requestId, scheme, port, isProxy, isRetry);
            httpAuthRequests.put(requestId, handler);
            if (delegate != null)
                delegate.onReceivedHttpAuthRequest(handler, host, realm);
            else
                handler.cancel();
        }

        @Override
        public void onHttpAuthRequestCancelled(long requestId) {
            HttpAuthRequest handler = httpAuthRequests.get(requestId);
            if (handler == null)
                return;
            httpAuthRequests.remove(requestId);
            if (delegate != null)
                delegate.onHttpAuthRequestCanceled(handler);
        }

        @Override
        public void onPlayingAudioChanged(boolean isPlayingAudio) {
            // A destroyed page plays nothing.
            if (destroyed || playingAudio == isPlayingAudio)
                return;
            playingAudio = isPlayingAudio;
            if (delegate != null)
                delegate.onPlayingAudioChanged(isPlayingAudio);
        }
    }

    /**
     * A JavaScript dialog of the page. WebKit takes one answer per dialog, and the native dialog is released with
     * it, so later answers do nothing.
     */
    final class ScriptDialog implements WPEJsPromptResult {
        private final long dialogPtr;
        final int type;
        final @Nullable String url;
        final @Nullable String message;
        final @NonNull String defaultText;
        private boolean answered;
        private @Nullable Runnable onAnswered;

        ScriptDialog(long dialogPtr, int type, @Nullable String url, @Nullable String message,
                     @NonNull String defaultText) {
            this.dialogPtr = dialogPtr;
            this.type = type;
            this.url = url;
            this.message = message;
            this.defaultText = defaultText;
        }

        boolean isAnswered() { return answered; }

        /** Runs once the dialog is answered, for example to close the built-in dialog that asks the user. */
        void setOnAnswered(@Nullable Runnable onAnswered) { this.onAnswered = onAnswered; }

        @Override
        public void cancel() {
            answer(false, null);
        }

        @Override
        public void confirm() {
            answer(true, null);
        }

        @Override
        public void confirm(@NonNull String result) {
            answer(true, result);
        }

        private void answer(boolean confirmed, @Nullable String text) {
            if (answered)
                return;
            answered = true;
            pendingScriptDialogs.remove(this);
            webKitWebView.scriptDialogConfirm(dialogPtr, confirmed, text);
            webKitWebView.scriptDialogClose(dialogPtr);
            Runnable callback = onAnswered;
            onAnswered = null;
            if (callback != null)
                callback.run();
        }
    }

    // Package-private: called from the PageListener inner class (avoids a synthetic accessor).
    boolean showFileChooser(long requestPtr, @NonNull String[] mimeTypes, boolean selectMultiple) {
        WebChromeClient.FileChooserParams params =
            new WebChromeClient.FileChooserParams(selectMultiple ? WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                                                                 : WebChromeClient.FileChooserParams.MODE_OPEN,
                                                  mimeTypes);
        FileChooserResult result = new FileChooserResult(requestPtr);
        boolean taken = false;
        try {
            taken = delegate != null && delegate.onShowFileChooser(params, result);
        } finally {
            // A request that is not taken is cancelled, so the callback, if the app keeps it, has nothing to answer.
            if (!taken)
                result.answered = true;
        }
        return taken;
    }

    private class FileChooserResult implements WPECallback<String[]> {
        private final long requestPtr;
        // Once answered, the request is gone, and its pointer may be used by another one.
        boolean answered;

        FileChooserResult(long requestPtr) { this.requestPtr = requestPtr; }

        @Override
        public void onResult(@Nullable String[] paths) {
            // WebKit would take a relative path from the directory of its own process. The request is cancelled, so
            // that the field does not wait for another answer.
            if (paths != null) {
                for (String path : paths) {
                    if (path == null || !path.startsWith("/")) {
                        onResult(null);
                        throw new IllegalArgumentException("Not the absolute path of a file: " + path);
                    }
                }
            }
            if (runOnMainThread(() -> onResult(paths)))
                return;
            // Destroying the page cancelled the request.
            if (answered || destroyed)
                return;
            answered = true;
            if (paths == null || paths.length == 0)
                webKitWebView.fileChooserCancel(requestPtr);
            else
                webKitWebView.fileChooserSelect(requestPtr, paths);
        }
    }

    // Package-private: called from the PageListener inner class (avoids a synthetic accessor).
    boolean showColorChooser(long requestPtr, @ColorInt int color, boolean supportsAlpha) {
        ColorChooserResult result = new ColorChooserResult(requestPtr, supportsAlpha);
        colorChooser = result;
        boolean taken = false;
        try {
            taken = delegate != null &&
                    delegate.onShowColorChooser(new WebChromeClient.ColorChooserParams(color, supportsAlpha), result);
        } finally {
            if (!taken)
                result.end();
        }
        return taken;
    }

    // Package-private, like the field that keeps the request: read by the PageListener inner class.
    class ColorChooserResult implements WebChromeClient.ColorChooserCallback {
        final long requestPtr;
        private final boolean supportsAlpha;
        // Once ended, by the app or by the page, the request is gone, and its pointer may be used by another one.
        private boolean ended;

        ColorChooserResult(long requestPtr, boolean supportsAlpha) {
            this.requestPtr = requestPtr;
            this.supportsAlpha = supportsAlpha;
        }

        void end() {
            ended = true;
            if (colorChooser == this)
                colorChooser = null;
        }

        // WebKit gives the page a change event for each color it is given, not only for the last one, so the field
        // gets only the chosen one.
        @Override
        public void confirm(@ColorInt int color) {
            if (runOnMainThread(() -> confirm(color)) || ended || destroyed)
                return;
            end();
            // A field without an alpha takes only opaque colors.
            webKitWebView.colorChooserSetColor(requestPtr, supportsAlpha ? color : color | 0xff000000);
            webKitWebView.colorChooserFinish(requestPtr);
        }

        @Override
        public void cancel() {
            if (runOnMainThread(this::cancel) || ended || destroyed)
                return;
            end();
            webKitWebView.colorChooserCancel(requestPtr);
        }
    }

    // Package-private, like the field that keeps the requests: read by the PageListener inner class.
    class HttpAuthRequest implements WebViewClient.HttpAuthHandler {
        private final long requestId;
        private final String scheme;
        private final int port;
        private final boolean isProxy;
        private final boolean isRetry;

        HttpAuthRequest(long requestId, @NonNull String scheme, int port, boolean isProxy, boolean isRetry) {
            this.requestId = requestId;
            this.scheme = scheme;
            this.port = port;
            this.isProxy = isProxy;
            this.isRetry = isRetry;
        }

        // The app may answer from any thread, as after it looked the credentials up. Destroying the page cancelled
        // the request.
        @Override
        public void proceed(@NonNull String username, @NonNull String password) {
            if (runOnMainThread(() -> proceed(username, password)) || destroyed)
                return;
            httpAuthRequests.remove(requestId);
            webKitWebView.authenticate(requestId, username, password);
        }

        @Override
        public void cancel() {
            if (runOnMainThread(this::cancel) || destroyed)
                return;
            httpAuthRequests.remove(requestId);
            webKitWebView.cancelAuthentication(requestId);
        }

        @Override
        public boolean isProxy() {
            return isProxy;
        }

        @Override
        public boolean isRetry() {
            return isRetry;
        }

        @Override
        public @NonNull String getScheme() {
            return scheme;
        }

        @Override
        public int getPort() {
            return port;
        }
    }

    private static class HttpResourceRequest implements WPEResourceRequest {
        private final Uri uri;
        private final String method;
        private final boolean isForMainFrame;
        private final boolean hasGesture;
        private final boolean isRedirect;
        HttpResourceRequest(Uri uri, String method, boolean isForMainFrame, boolean hasGesture, boolean isRedirect) {
            this.uri = uri;
            this.method = method;
            this.isForMainFrame = isForMainFrame;
            this.hasGesture = hasGesture;
            this.isRedirect = isRedirect;
        }
        @Override
        public @NonNull Uri getUrl() {
            return uri;
        }
        @Override
        public @NonNull String getMethod() {
            return method;
        }
        @Override
        public @NonNull Map<String, String> getRequestHeaders() {
            return Collections.emptyMap();
        }
        @Override
        public boolean isForMainFrame() {
            return isForMainFrame;
        }
        @Override
        public boolean hasGesture() {
            return hasGesture;
        }
        @Override
        public boolean isRedirect() {
            return isRedirect;
        }
    }
}
