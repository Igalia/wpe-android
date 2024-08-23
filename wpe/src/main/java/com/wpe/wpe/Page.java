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

package com.wpe.wpe;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.wpe.wpeview.WPEJsResult;
import com.wpe.wpeview.WPEResourceRequest;
import com.wpe.wpeview.WPEResourceResponse;
import com.wpe.wpeview.WPEView;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * A Page roughly corresponds with a tab in a regular browser UI.
 * There is a 1:1 relationship between WPEView and Page.
 * Each Page instance has its own wpe.wpe.gfx.View and WebKitWebView instances associated.
 * It also keeps references to the Services that host the logic of WebKit's auxiliary
 * processes (WebProcess and NetworkProcess).
 */
@UiThread
public final class Page {
    private static final String LOGTAG = "WPEPage";

    public static final int LOAD_STARTED = 0;
    public static final int LOAD_REDIRECTED = 1;
    public static final int LOAD_COMMITTED = 2;
    public static final int LOAD_FINISHED = 3;

    public static final int WEBKIT_SCRIPT_DIALOG_ALERT = 0;
    public static final int WEBKIT_SCRIPT_DIALOG_CONFIRM = 1;
    public static final int WEBKIT_SCRIPT_DIALOG_PROMPT = 2;
    public static final int WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM = 3;

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    private native long nativeInit(long nativeContextPtr, int width, int height, float deviceScale, boolean headless);
    private native void nativeClose(long nativePtr);
    private native void nativeDestroy(long nativePtr);
    private native void nativeLoadUrl(long nativePtr, @NonNull String url);
    private native void nativeLoadHtml(long nativePtr, @NonNull String content, @Nullable String baseUri);
    private native void nativeGoBack(long nativePtr);
    private native void nativeGoForward(long nativePtr);
    private native void nativeStopLoading(long nativePtr);
    private native void nativeReload(long nativePtr);
    protected native void nativeSurfaceCreated(long nativePtr, @NonNull Surface surface);
    protected native void nativeSurfaceChanged(long nativePtr, int format, int width, int height);
    protected native void nativeSurfaceRedrawNeeded(long nativePtr);
    protected native void nativeSurfaceDestroyed(long nativePtr);
    protected native void nativeSetZoomLevel(long nativePtr, double zoomLevel);
    protected native void nativeOnTouchEvent(long nativePtr, long time, int type, float x, float y);
    private native void nativeSetInputMethodContent(long nativePtr, int unicodeChar);
    private native void nativeDeleteInputMethodContent(long nativePtr, int offset);
    private native void nativeRequestExitFullscreenMode(long nativePtr);
    private native void nativeEvaluateJavascript(long nativePtr, String script, WKCallback<String> callback);
    private native void nativeScriptDialogClose(long nativeDialogPtr);
    private native void nativeScriptDialogConfirm(long nativeDialogPtr, boolean confirm);

    private final WPEView wpeView;
    private final PageSurfaceView surfaceView;
    protected final ScaleGestureDetector scaleDetector;
    private final PageSettings pageSettings;

    public @NonNull PageSettings getPageSettings() { return pageSettings; }

    private boolean isClosed = false;
    private boolean canGoBack = true;
    private boolean canGoForward = true;
    protected boolean ignoreTouchEvents = false;

    private static int kHeadlessWidth = 1080;
    private static int kHeadlessHeight = 2274;

    public Page(@NonNull WPEView wpeView, @NonNull WKWebContext context, boolean headless) {
        Log.v(LOGTAG, "Creating Page: " + this);

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
            Log.d(LOGTAG, "Page: " + this + " surface holder: " + holder);
            holder.addCallback(new PageSurfaceHolderCallback());
        }
        surfaceView.requestLayout();

        scaleDetector = new ScaleGestureDetector(ctx, new PageScaleListener());

        wpeView.onPageSurfaceViewCreated(surfaceView);
        wpeView.onPageSurfaceViewReady(surfaceView);

        pageSettings = new PageSettings(this);
    }

    public void close() {
        if (!isClosed) {
            isClosed = true;
            Log.v(LOGTAG, "Closing Page: " + this);
            nativeClose(nativePtr);
        }
    }

    public void destroy() {
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

    public void loadUrl(@NonNull String url) {
        Log.d(LOGTAG, "loadUrl('" + url + "')");
        nativeLoadUrl(nativePtr, url);
    }

    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        Log.d(LOGTAG, "loadHtml(..., '" + baseUri + "')");
        nativeLoadHtml(nativePtr, content, baseUri);
    }

    @Keep
    public void onLoadChanged(int loadEvent) {
        wpeView.onLoadChanged(loadEvent);
        if (loadEvent == Page.LOAD_STARTED) {
            onInputMethodContextOut();
        }
    }

    @Keep
    public void onClose() {
        wpeView.onClose();
    }
    @Keep
    public void onLoadProgress(double progress) {
        wpeView.onLoadProgress(progress);
    }

    @Keep
    public void onUriChanged(@NonNull String uri) {
        wpeView.onUriChanged(uri);
    }

    @Keep
    public void onTitleChanged(@NonNull String title, boolean canGoBack, boolean canGoForward) {
        this.canGoBack = canGoBack;
        this.canGoForward = canGoForward;
        wpeView.onTitleChanged(title);
    }

    private class ScriptDialogResult implements WPEJsResult {

        private final long nativeScriptDialogPtr;

        public ScriptDialogResult(long nativeScriptDialogPtr) { this.nativeScriptDialogPtr = nativeScriptDialogPtr; }
        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void cancel() {
            nativeScriptDialogConfirm(nativeScriptDialogPtr, false);
            nativeScriptDialogClose(nativeScriptDialogPtr);
        }
        @Override
        @SuppressWarnings("SyntheticAccessor")
        public void confirm() {
            nativeScriptDialogConfirm(nativeScriptDialogPtr, true);
            nativeScriptDialogClose(nativeScriptDialogPtr);
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
        private final WPEJsResult result;

        public ScriptDialogPositiveListener(@NonNull WPEJsResult result) { this.result = result; }

        @Override
        public void onClick(DialogInterface dialogInterface, int which) {
            result.confirm();
        }
    }

    @Keep
    public boolean onScriptDialog(long nativeDialogPtr, int dialogType, @NonNull String url, @NonNull String message) {
        ScriptDialogResult result = new ScriptDialogResult(nativeDialogPtr);
        if (!wpeView.onDialogScript(dialogType, url, message, result)) {
            if (dialogType == Page.WEBKIT_SCRIPT_DIALOG_ALERT || dialogType == Page.WEBKIT_SCRIPT_DIALOG_CONFIRM) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(wpeView.getContext());
                String title = url;
                try {
                    URL alertUrl = new URL(url);
                    title = "The page at " + alertUrl.getProtocol() + "://" + alertUrl.getHost() + " says";
                } catch (MalformedURLException ex) {
                    // NOOP
                }
                builder.setTitle(title);
                builder.setMessage(message);
                builder.setOnCancelListener(new ScriptDialogCancelListener(result));
                builder.setPositiveButton("Yes", new ScriptDialogPositiveListener(result));
                if (dialogType != Page.WEBKIT_SCRIPT_DIALOG_ALERT) {
                    builder.setNegativeButton("No", new ScriptDialogCancelListener(result));
                }
                builder.show();
                return true;
            }
        }

        return false;
    }

    @Keep
    public void onInputMethodContextIn() {
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
    public void onInputMethodContextOut() {
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

    @Keep
    public void onEnterFullscreenMode() {
        Log.d(LOGTAG, "onEnterFullscreenMode()");
        wpeView.onEnterFullscreenMode();
    }

    @Keep
    public void onExitFullscreenMode() {
        Log.d(LOGTAG, "onExitFullscreenMode()");
        wpeView.onExitFullscreenMode();
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
    public void onReceivedHttpError( // WPEResourceRequest
        @NonNull String requestUri, @NonNull String requestMethod, @NonNull String[] requestHeaders,
        // WPEResourceResponse
        @NonNull String responseMimeType, int responseStatusCode, @NonNull String[] responseHeaders) {
        PageResourceRequest request = new PageResourceRequest(requestUri, requestMethod, requestHeaders);

        HashMap<String, String> responseHeadersMap = new HashMap<>();
        int i = 0;
        while (i < responseHeaders.length) {
            responseHeadersMap.put(responseHeaders[i++], responseHeaders[i++]);
        }
        WPEResourceResponse response =
            new WPEResourceResponse(responseMimeType, responseStatusCode, responseHeadersMap);
        wpeView.onReceivedHttpError(request, response);
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

    public void evaluateJavascript(@NonNull String script, @Nullable WKCallback<String> callback) {
        nativeEvaluateJavascript(nativePtr, script, callback);
    }

    protected final class PageSurfaceHolderCallback implements SurfaceHolder.Callback2 {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceCreated()");
            nativeSurfaceCreated(nativePtr, holder.getSurface());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceChanged() with format: " + format + " and size: " + width +
                              " x " + height);
            nativeSurfaceChanged(nativePtr, format, width, height);
        }

        @Override
        public void surfaceRedrawNeeded(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceRedrawNeeded()");
            nativeSurfaceRedrawNeeded(nativePtr);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(LOGTAG, "PageSurfaceHolderCallback::surfaceDestroyed()");
            nativeSurfaceDestroyed(nativePtr);
        }
    }

    protected final class PageScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float m_scaleFactor = 1.f;

        @Override
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

            nativeOnTouchEvent(nativePtr, event.getEventTime(), eventType, event.getX(0), event.getY(0));
            return true;
        }
    }
}
