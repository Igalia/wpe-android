/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.R;
import org.wpewebkit.wpeview.WPEChromeClient;
import org.wpewebkit.wpeview.WPEJsPromptResult;
import org.wpewebkit.wpeview.WPEJsResult;
import org.wpewebkit.wpeview.WPEResourceRequest;
import org.wpewebkit.wpeview.WPEResourceResponse;
import org.wpewebkit.wpeview.WPEView;
import org.wpewebkit.wpeview.WPEViewClient;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * A WKWebView roughly corresponds with a tab in a regular browser UI.
 * There is a 1:1 relationship between WPEView and WKWebView.
 * Each WKWebView instance has its own wpe.wpe.gfx.View and WebKitWebView instances associated.
 * It also keeps references to the Services that host the logic of WebKit's auxiliary
 * processes (WebProcess and NetworkProcess).
 */
@UiThread
public final class WKWebView {
    private static final String LOGTAG = "WKWebView";

    public static final int LOAD_STARTED = 0;
    public static final int LOAD_REDIRECTED = 1;
    public static final int LOAD_COMMITTED = 2;
    public static final int LOAD_FINISHED = 3;

    public static final int WEBKIT_SCRIPT_DIALOG_ALERT = 0;
    public static final int WEBKIT_SCRIPT_DIALOG_CONFIRM = 1;
    public static final int WEBKIT_SCRIPT_DIALOG_PROMPT = 2;
    public static final int WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM = 3;

    public static final int WEBKIT_TLS_ERRORS_POLICY_IGNORE = 0;
    public static final int WEBKIT_TLS_ERRORS_POLICY_FAIL = 1;

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    private final WPEView wpeView;
    private WPEViewClient wpeViewClient = new WPEViewClient();
    private WPEChromeClient wpeChromeClient = null;

    private final PageSurfaceView surfaceView;
    protected final ScaleGestureDetector scaleDetector;
    private final WKSettings wkSettings;

    private FrameLayout customView = null;

    private String uri = "about:blank";
    private String originalUrl = uri;
    private String title = uri;

    private boolean isClosed = false;
    private boolean canGoBack = true;
    private boolean canGoForward = true;
    protected boolean ignoreTouchEvents = false;
    private boolean isInputFieldFocused = false;

    private static int kHeadlessWidth = 1080;
    private static int kHeadlessHeight = 2274;

    public WKWebView(@NonNull WPEView wpeView, @NonNull WKWebContext context, boolean headless) {
        Log.v(LOGTAG, "Creating WKWebView: " + this);

        this.wpeView = wpeView;

        int width = wpeView.getMeasuredWidth();
        int height = wpeView.getMeasuredHeight();
        if (headless) {
            // Use some hardcoded values
            width = kHeadlessWidth;
            height = kHeadlessHeight;
        }

        DisplayMetrics displayMetrics = context.getApplicationContext().getResources().getDisplayMetrics();

        nativePtr = nativeInit(context.getNativePtr(), width, height, displayMetrics.density, headless);

        Context ctx = wpeView.getContext();
        surfaceView = new PageSurfaceView(ctx);
        if (wpeView.getSurfaceClient() != null) {
            wpeView.getSurfaceClient().addCallback(wpeView, new PageSurfaceHolderCallback());
        } else {
            SurfaceHolder holder = surfaceView.getHolder();
            Log.d(LOGTAG, "WKWebView: " + this + " surface holder: " + holder);
            holder.addCallback(new PageSurfaceHolderCallback());
        }
        surfaceView.requestLayout();

        wkSettings = new WKSettings(this);
        scaleDetector = new ScaleGestureDetector(ctx, new PageScaleListener());

        wpeView.post(() -> {
            // Add the view during the next UI cycle
            try {
                wpeView.addView(surfaceView);
            } catch (Exception e) {
                Log.e(LOGTAG, "Error while adding the surface view", e);
            }
        });

        wpeView.post(() -> {
            if (wpeViewClient != null)
                wpeViewClient.onViewReady(wpeView);
        });
    }

    public void close() {
        if (!isClosed) {
            isClosed = true;
            Log.v(LOGTAG, "Closing WKWebView: " + this);
            nativeClose(nativePtr);
        }
    }

    public void destroy() {
        // Make sure that we do not trigger any callbacks after destruction
        wpeChromeClient = null;
        wpeViewClient = null;
        if (nativePtr != 0) {
            close();
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    public @NonNull WKSettings getSettings() { return wkSettings; }

    public void setWPEViewClient(@NonNull WPEViewClient wpeViewClient) { this.wpeViewClient = wpeViewClient; }

    public @NonNull WPEViewClient getWPEViewClient() { return wpeViewClient; }

    public @Nullable WPEChromeClient getWPEChromeClient() { return wpeChromeClient; }

    public void setWPEChromeClient(@Nullable WPEChromeClient client) { wpeChromeClient = client; }

    public @Nullable String getTitle() { return title; }

    public @Nullable String getUrl() { return uri; }

    public @Nullable String getOriginalUrl() { return originalUrl; }

    public int getEstimatedLoadProgress() { return (int)Math.round(nativeGetEstimatedLoadProgress(nativePtr) * 100); }

    public void loadUrl(@NonNull String url) {
        Log.d(LOGTAG, "loadUrl('" + url + "')");
        originalUrl = url;
        nativeLoadUrl(nativePtr, url);
    }

    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        Log.d(LOGTAG, "loadHtml(..., '" + baseUri + "')");
        originalUrl = baseUri;
        nativeLoadHtml(nativePtr, content, baseUri);
    }

    public void requestExitFullscreenMode() { nativeRequestExitFullscreenMode(nativePtr); }

    public boolean canGoBack() { return canGoBack; }

    public boolean canGoForward() { return canGoForward; }

    public void goBack() { nativeGoBack(nativePtr); }

    public void goForward() { nativeGoForward(nativePtr); }

    public void stopLoading() { nativeStopLoading(nativePtr); }

    public void reload() { nativeReload(nativePtr); }

    public void setInputMethodContent(int unicodeChar) { nativeSetInputMethodContent(nativePtr, unicodeChar); }

    public void deleteInputMethodContent(int offset) { nativeDeleteInputMethodContent(nativePtr, offset); }

    public boolean isInputFieldFocused() { return isInputFieldFocused; }

    public void evaluateJavascript(@NonNull String script, @Nullable WKCallback<String> callback) {
        nativeEvaluateJavascript(nativePtr, script, callback);
    }

    public void setTLSErrorsPolicy(int policy) {
        if ((policy == WEBKIT_TLS_ERRORS_POLICY_IGNORE) || (policy == WEBKIT_TLS_ERRORS_POLICY_FAIL)) {
            nativeSetTLSErrorsPolicy(nativePtr, policy);
        }
    }

    protected final class PageSurfaceHolderCallback implements SurfaceHolder.Callback2 {
        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceCreated()");
            nativeSurfaceCreated(nativePtr, holder.getSurface());
        }

        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceChanged() with format: " + format + " and size: " + width +
                              " x " + height);
            nativeSurfaceChanged(nativePtr, format, width, height);
        }

        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void surfaceRedrawNeeded(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceRedrawNeeded()");
            nativeSurfaceRedrawNeeded(nativePtr);
        }

        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceDestroyed()");
            nativeSurfaceDestroyed(nativePtr);
        }
    }

    protected final class PageScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float m_scaleFactor = 1.f;

        @Override
        @SuppressWarnings("SyntheticAccessor")
        public boolean onScale(ScaleGestureDetector detector) {
            Log.d(LOGTAG, "PageScaleListener::onScale()");

            m_scaleFactor *= detector.getScaleFactor();
            m_scaleFactor = Math.max(0.1f, Math.min(m_scaleFactor, 5.0f));
            nativeSetZoomLevel(nativePtr, m_scaleFactor);

            ignoreTouchEvents = true;
            return true;
        }
    }

    private final class PageSurfaceView extends SurfaceView {
        public PageSurfaceView(Context context) { super(context); }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        @SuppressWarnings("SyntheticAccessor")
        public boolean onTouchEvent(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            if (pointerCount < 1)
                return false;

            scaleDetector.onTouchEvent(event);
            if (ignoreTouchEvents)
                ignoreTouchEvents = false;

            int eventType;
            int eventAction = event.getActionMasked();
            switch (eventAction) {
            case MotionEvent.ACTION_DOWN:
                eventType = 0;
                break;

            case MotionEvent.ACTION_MOVE:
                eventType = 1;
                break;

            case MotionEvent.ACTION_UP:
                eventType = 2;
                break;

            default:
                return false;
            }

            int[] ids = new int[pointerCount];
            float[] xs = new float[pointerCount];
            float[] ys = new float[pointerCount];
            for (int i = 0; i < pointerCount; i++) {
                ids[i] = event.getPointerId(i);
                xs[i] = event.getX(i);
                ys[i] = event.getY(i);
            }

            nativeOnTouchEvent(nativePtr, event.getEventTime(), eventType, pointerCount, ids, xs, ys);
            return true;
        }
    }

    /**
     * --------------------------------------------------------------------------------------------
     *  Methods called from native via JNI
     * --------------------------------------------------------------------------------------------
     */

    private class ScriptDialogResult implements WPEJsPromptResult {

        private final long nativeScriptDialogPtr;

        private String stringResult;

        public ScriptDialogResult(long nativeScriptDialogPtr) { this.nativeScriptDialogPtr = nativeScriptDialogPtr; }
        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void cancel() {
            nativeScriptDialogConfirm(nativeScriptDialogPtr, false, stringResult);
            nativeScriptDialogClose(nativeScriptDialogPtr);
        }
        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void confirm() {
            nativeScriptDialogConfirm(nativeScriptDialogPtr, true, stringResult);
            nativeScriptDialogClose(nativeScriptDialogPtr);
        }

        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void confirm(String result) {
            stringResult = result;
            confirm();
        }

        @SuppressWarnings("SyntheticAccessor")
        public String getStringResult() {
            return stringResult;
        }
    }

    private static class ScriptDialogCancelListener
        implements DialogInterface.OnCancelListener, DialogInterface.OnClickListener {
        private final WPEJsResult result;

        public ScriptDialogCancelListener(@NonNull WPEJsResult result) { this.result = result; }
        @Override
        public void onCancel(DialogInterface dialogInterface) {
            result.cancel();
        }
        @Override
        public void onClick(DialogInterface dialogInterface, int which) {
            result.cancel();
        }
    }

    private static class ScriptDialogPositiveListener implements DialogInterface.OnClickListener {
        private final WPEJsPromptResult result;

        private final EditText edit;

        public ScriptDialogPositiveListener(@NonNull WPEJsPromptResult result, @Nullable EditText edit) {
            this.result = result;
            this.edit = edit;
        }

        @Override
        public void onClick(DialogInterface dialogInterface, int which) {
            if (edit != null) {
                result.confirm(edit.getText().toString());
            } else {
                result.confirm();
            }
        }
    }

    private static class PageResourceRequest implements WPEResourceRequest {

        private final Uri uri;
        private final String method;
        private final HashMap<String, String> requestHeaders = new HashMap<>();

        PageResourceRequest(String uri, String method, String[] headers) {
            this.uri = Uri.parse(uri);
            this.method = method;

            int i = 0;
            while (i < headers.length) {
                requestHeaders.put(headers[i++], headers[i++]);
            }
        }

        @NonNull
        @Override
        public Uri getUrl() {
            return uri;
        }

        @NonNull
        @Override
        public String getMethod() {
            return method;
        }

        @NonNull
        @Override
        public Map<String, String> getRequestHeaders() {
            return requestHeaders;
        }
    }

    @Keep
    private void onLoadChanged(int loadEvent) {
        switch (loadEvent) {
        case WKWebView.LOAD_STARTED:
            if (wpeViewClient != null)
                wpeViewClient.onPageStarted(wpeView, uri);
            break;
        case WKWebView.LOAD_FINISHED:
            if (wpeViewClient != null)
                wpeViewClient.onPageFinished(wpeView, uri);
            break;
        }
    }

    @Keep
    private void onClose() {
        if (wpeChromeClient != null)
            wpeChromeClient.onCloseWindow(wpeView);
    }

    @Keep
    private void onEstimatedLoadProgress(double progress) {
        if (wpeChromeClient != null)
            wpeChromeClient.onProgressChanged(wpeView, (int)Math.round(progress * 100));
    }

    @Keep
    private void onUriChanged(@NonNull String uri) {
        this.uri = uri;
    }

    @Keep
    private void onTitleChanged(@NonNull String title, boolean canGoBack, boolean canGoForward) {
        this.title = title;
        this.canGoBack = canGoBack;
        this.canGoForward = canGoForward;
        if (wpeChromeClient != null)
            wpeChromeClient.onReceivedTitle(wpeView, title);
    }

    @SuppressLint("StringFormatInvalid")
    @Keep
    private boolean onScriptDialog(long nativeDialogPtr, int dialogType, @NonNull String url, @NonNull String message,
                                   @NonNull String defaultText) {
        WPEChromeClient client = wpeView.getWPEChromeClient();
        if (client != null) {
            boolean clientHandledDialog = false;
            ScriptDialogResult result = new ScriptDialogResult(nativeDialogPtr);
            if (dialogType == WEBKIT_SCRIPT_DIALOG_ALERT) {
                clientHandledDialog = client.onJsAlert(wpeView, url, message, result);
            } else if (dialogType == WEBKIT_SCRIPT_DIALOG_CONFIRM) {
                clientHandledDialog = client.onJsConfirm(wpeView, url, message, result);
            } else if (dialogType == WEBKIT_SCRIPT_DIALOG_PROMPT) {
                clientHandledDialog = client.onJsPrompt(wpeView, url, message, defaultText, result);
            } else if (dialogType == WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM) {
                clientHandledDialog = client.onJsBeforeUnload(wpeView, url, message, result);
            }

            if (!clientHandledDialog) {
                String title = url;
                String displayMessage;
                int positiveTextId, negativeTextId;
                if (dialogType == WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM) {
                    title = wpeView.getContext().getString(R.string.js_dialog_before_unload_title);
                    displayMessage = wpeView.getContext().getString(R.string.js_dialog_before_unload, message);
                    positiveTextId = R.string.js_dialog_before_unload_positive_button;
                    negativeTextId = R.string.js_dialog_before_unload_negative_button;
                } else {
                    try {
                        URL alertUrl = new URL(url);
                        title = "The page at " + alertUrl.getProtocol() + "://" + alertUrl.getHost() + " says";
                    } catch (MalformedURLException ex) {
                        // NOOP
                    }
                    displayMessage = message;
                    positiveTextId = R.string.ok;
                    negativeTextId = R.string.cancel;
                }

                final AlertDialog.Builder builder = new AlertDialog.Builder(wpeView.getContext());
                builder.setTitle(title);
                builder.setOnCancelListener(new ScriptDialogCancelListener(result));
                if (dialogType != WEBKIT_SCRIPT_DIALOG_PROMPT) {
                    builder.setMessage(displayMessage);
                    builder.setPositiveButton(positiveTextId, new ScriptDialogPositiveListener(result, null));
                } else {
                    @SuppressLint("InflateParams")
                    final View view = LayoutInflater.from(wpeView.getContext()).inflate(R.layout.js_prompt, null);
                    EditText edit = ((EditText)view.findViewById(R.id.value));
                    builder.setPositiveButton(positiveTextId, new ScriptDialogPositiveListener(result, edit));
                    ((TextView)view.findViewById(R.id.message)).setText(message);
                    builder.setView(view);
                }
                if (dialogType != WEBKIT_SCRIPT_DIALOG_ALERT) {
                    builder.setNegativeButton(negativeTextId, new ScriptDialogCancelListener(result));
                }
                builder.show();
            }
            return true;
        }
        return false;
    }

    @Keep
    private void onInputMethodContextIn() {
        isInputFieldFocused = true;
        WeakReference<WPEView> weakRefecence = new WeakReference<>(wpeView);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            WPEView view = weakRefecence.get();
            if (view != null) {
                if (view.requestFocus()) {
                    InputMethodManager imm =
                        (InputMethodManager)wpeView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(wpeView, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    @Keep
    private void onInputMethodContextOut() {
        isInputFieldFocused = false;
        WeakReference<WPEView> weakRefecence = new WeakReference<>(wpeView);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            WPEView view = weakRefecence.get();
            if (view != null) {
                InputMethodManager imm =
                    (InputMethodManager)wpeView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(wpeView.getWindowToken(), 0);
            }
        });
    }

    private static final class SslErrorHandlerImpl implements WPEViewClient.SslErrorHandler {
        private final Handler m_handler;
        private long m_nativeHandlerPtr = 0;

        protected SslErrorHandlerImpl(long nativeHandlerPtr) {
            m_nativeHandlerPtr = nativeHandlerPtr;

            Looper looper = Looper.myLooper();
            if (looper == null) {
                looper = Looper.getMainLooper();
            }
            m_handler = new Handler(looper);
        }

        private void triggerSslErrorHandler(boolean acceptCertificate) {
            if (m_nativeHandlerPtr != 0) {
                nativeTriggerSslErrorHandler(m_nativeHandlerPtr, acceptCertificate);
                m_nativeHandlerPtr = 0;
            }
        }

        private void executeOrPostTask(Runnable task) {
            if (Looper.myLooper() == m_handler.getLooper()) {
                task.run();
            } else {
                m_handler.post(task);
            }
        }

        @Override
        public void proceed() {
            executeOrPostTask(() -> triggerSslErrorHandler(true));
        }

        @Override
        public void cancel() {
            executeOrPostTask(() -> triggerSslErrorHandler(false));
        }

        @Override
        protected void finalize() throws Throwable {
            cancel();
        }
    }

    @Keep
    private boolean onReceivedSslError(@NonNull String failingURI, @NonNull String certificatePEM,
                                       @NonNull int[] sslErrors, long nativeHandlerPtr) {
        Log.d(LOGTAG, "onReceivedSslError()");
        if (wpeViewClient == null) {
            return false;
        }

        SslError sslError;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate =
                (X509Certificate)factory.generateCertificate(new ByteArrayInputStream(certificatePEM.getBytes()));

            sslError = new SslError(sslErrors[0], certificate, failingURI);
            for (int i = 1; i < sslErrors.length; ++i) {
                sslError.addError(sslErrors[i]);
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Error while wrapping the SSL certificate in onReceivedSslError()", e);
            return false;
        }

        SslErrorHandlerImpl handler = new SslErrorHandlerImpl(nativeHandlerPtr);
        try {
            wpeViewClient.onReceivedSslError(wpeView, handler, sslError);
        } catch (Exception e) {
            Log.e(LOGTAG,
                  "Exception thrown while calling WPEViewClient.onReceivedSslError(), certificate is "
                      + "automatically rejected",
                  e);
            handler.cancel();
        }

        return true;
    }

    @Keep
    private void onEnterFullscreenMode() {
        Log.d(LOGTAG, "onEnterFullscreenMode()");
        if ((surfaceView != null) && (wpeChromeClient != null)) {
            wpeView.removeView(surfaceView);

            customView = new FrameLayout(wpeView.getContext());
            customView.addView(surfaceView);
            customView.setFocusable(true);
            customView.setFocusableInTouchMode(true);

            wpeChromeClient.onShowCustomView(customView, () -> {
                if (customView != null)
                    requestExitFullscreenMode();
            });
        }
    }

    @Keep
    private void onExitFullscreenMode() {
        Log.d(LOGTAG, "onExitFullscreenMode()");
        if ((customView != null) && (surfaceView != null) && (wpeChromeClient != null)) {
            customView.removeView(surfaceView);
            wpeView.addView(surfaceView);
            customView = null;
            wpeChromeClient.onHideCustomView();
        }
    }

    @Keep
    private void onReceivedHttpError( // WPEResourceRequest
        @NonNull String requestUri, @NonNull String requestMethod, @NonNull String[] requestHeaders,
        // WPEResourceResponse
        @NonNull String responseMimeType, int responseStatusCode, @NonNull String[] responseHeaders) {

        if (wpeViewClient != null) {
            PageResourceRequest request = new PageResourceRequest(requestUri, requestMethod, requestHeaders);

            HashMap<String, String> responseHeadersMap = new HashMap<>();
            int i = 0;
            while (i < responseHeaders.length) {
                responseHeadersMap.put(responseHeaders[i++], responseHeaders[i++]);
            }
            WPEResourceResponse response =
                new WPEResourceResponse(responseMimeType, responseStatusCode, responseHeadersMap);

            wpeViewClient.onReceivedHttpError(wpeView, request, response);
        }
    }

    private native long nativeInit(long nativeContextPtr, int width, int height, float deviceScale, boolean headless);
    private native void nativeClose(long nativePtr);
    private native void nativeDestroy(long nativePtr);
    private native void nativeLoadUrl(long nativePtr, @NonNull String url);
    private native void nativeLoadHtml(long nativePtr, @NonNull String content, @Nullable String baseUri);
    private native double nativeGetEstimatedLoadProgress(long nativePtr);
    private native void nativeGoBack(long nativePtr);
    private native void nativeGoForward(long nativePtr);
    private native void nativeStopLoading(long nativePtr);
    private native void nativeReload(long nativePtr);
    private native void nativeSurfaceCreated(long nativePtr, @NonNull Surface surface);
    private native void nativeSurfaceChanged(long nativePtr, int format, int width, int height);
    private native void nativeSurfaceRedrawNeeded(long nativePtr);
    private native void nativeSurfaceDestroyed(long nativePtr);
    private native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    private native void nativeOnTouchEvent(long nativePtr, long time, int type, int pointerCount, int[] ids, float[] xs,
                                           float[] ys);
    private native void nativeSetInputMethodContent(long nativePtr, int unicodeChar);
    private native void nativeDeleteInputMethodContent(long nativePtr, int offset);
    private native void nativeRequestExitFullscreenMode(long nativePtr);
    private native void nativeEvaluateJavascript(long nativePtr, String script, WKCallback<String> callback);
    private native void nativeScriptDialogClose(long nativeDialogPtr);
    private native void nativeScriptDialogConfirm(long nativeDialogPtr, boolean confirm, @Nullable String text);
    private native void nativeSetTLSErrorsPolicy(long nativePtr, int policy);

    protected static native void nativeTriggerSslErrorHandler(long nativeHandlerPtr, boolean acceptCertificate);
}
