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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.R;
import org.wpewebkit.wpe.WPEEventType;
import org.wpewebkit.wpe.WPEInputMethodContext;
import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitWebView;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

/**
 * WebView is the public Android widget for the WPE browser engine.
 * It handles the Surface lifecycle, gestures, and UI delegation.
 */
@UiThread
public class WebView extends FrameLayout {
    private WebContext wpeContext;
    private boolean ownsContext;
    private boolean headless;
    // Package-private: read by the ScriptDialogResult inner class (avoids a synthetic accessor).
    WebKitWebView webKitWebView;
    private WPEToplevel wpeToplevel;
    WPEView platformView;
    private WPEInputMethodContext imContext;
    @Nullable
    InputMethodManager inputMethodManager;
    boolean inputFieldFocused;
    private PageSurfaceView surfaceView;
    private WebSettings webSettings;
    private @Nullable Surface attachedSurface;

    WebViewClient viewClient = new WebViewClient();
    WebChromeClient chromeClient = new WebChromeClient();
    private @Nullable String originalUrl = null;
    String currentUrl = "about:blank";
    String currentTitle = "";
    int currentProgress = 0;
    boolean canGoBack = false;
    boolean canGoForward = false;

    public WebView(@NonNull android.content.Context context) { this(context, (AttributeSet)null); }

    public WebView(@NonNull android.content.Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(new WebContext(context), true, false);
    }

    public WebView(@NonNull WebContext context) {
        super(context.getApplicationContext());
        init(context, false, false);
    }

    /**
     * Constructs a {@link WebView} that renders with {@code uiContext} while sharing the given
     * {@link WebContext}. Pass an Activity context (not the application context): it is required to
     * show the built-in JavaScript dialogs and the soft keyboard, mirroring
     * {@code android.webkit.WebView}.
     */
    public WebView(@NonNull android.content.Context uiContext, @NonNull WebContext context) {
        super(uiContext);
        init(context, false, false);
    }

    /**
     * Constructs a {@link WebView} without an on-screen Surface. JS, navigation and listeners
     * still work; the view simply never gets a backing window so the compositor reports it as
     * unmappable. Used by automation/WebDriver headless sessions.
     */
    public WebView(@NonNull WebContext context, boolean headless) {
        super(context.getApplicationContext());
        init(context, false, headless);
    }

    private void init(@NonNull WebContext context, boolean ownsContext, boolean headless) {
        this.wpeContext = context;
        this.ownsContext = ownsContext;
        this.headless = headless;

        this.webKitWebView = new WebKitWebView(wpeContext.getWPEDisplay(), wpeContext.getWebKitWebContext(), null,
                                               wpeContext.getWebKitNetworkSession(), wpeContext.getWebKitSettings());

        this.platformView = webKitWebView.getWPEView();
        this.imContext = webKitWebView.getInputMethodContext();
        this.inputMethodManager = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        this.imContext.setFocusListener(new WPEInputMethodContext.FocusListener() {
            @Override
            public void onFocusIn() {
                onImeFieldFocused(true);
            }
            @Override
            public void onFocusOut() {
                onImeFieldFocused(false);
            }
        });
        this.webSettings = new WebSettings(wpeContext.getWebKitSettings());
        this.wpeToplevel = new WPEToplevel(wpeContext.getWPEDisplay(), null);
        this.platformView.setToplevel(wpeToplevel);

        this.webKitWebView.setListener(new WebKitWebView.Listener() {
            @Override
            public void onClose() {
                chromeClient.onCloseWindow(WebView.this);
            }

            @Override
            public void onPageStarted(String url) {
                viewClient.onPageStarted(WebView.this, url);
            }

            @Override
            public void onPageFinished(String url) {
                viewClient.onPageFinished(WebView.this, url);
            }

            @Override
            public void onURLChanged(String uri) {
                currentUrl = uri;
                viewClient.doUpdateVisitedHistory(WebView.this, uri, /* isReload */ false);
            }

            @Override
            public void onLoadProgress(double progress) {
                currentProgress = (int)Math.round(progress * 100);
                chromeClient.onProgressChanged(WebView.this, currentProgress);
            }

            @Override
            public void onTitleChanged(String title, boolean goBack, boolean goForward) {
                currentTitle = title;
                canGoBack = goBack;
                canGoForward = goForward;
                chromeClient.onReceivedTitle(WebView.this, title);
            }

            @Override
            public void onReceivedHttpError(String uri, String method, String mimeType, int statusCode) {
                WPEResourceRequest request = new HttpResourceRequest(Uri.parse(uri), method);
                WPEResourceResponse response = new WPEResourceResponse(mimeType, statusCode, Collections.emptyMap());
                viewClient.onReceivedHttpError(WebView.this, request, response);
            }

            @Override
            public void onScriptDialog(long dialogPtr, int type, String url, String message, String defaultText) {
                showScriptDialog(dialogPtr, type, url, message, defaultText);
            }
        });

        if (!headless) {
            this.surfaceView = new PageSurfaceView(getContext());
            this.surfaceView.getHolder().addCallback(new SurfaceCallback());
            addView(surfaceView);
        }

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @NonNull
    WebKitWebView getInternalWebKitWebView() {
        return webKitWebView;
    }

    public void destroy() {
        if (platformView != null)
            platformView.setToplevel(null);
        if (wpeToplevel != null) {
            wpeToplevel.destroy();
            wpeToplevel = null;
        }
        if (webKitWebView != null) {
            webKitWebView.destroy();
            webKitWebView = null;
        }
        if (ownsContext && wpeContext != null) {
            wpeContext.destroy();
            wpeContext = null;
        }
    }

    void attachToplevelSurface(@NonNull Surface surface) {
        if (wpeContext == null || wpeToplevel == null)
            return;
        if (attachedSurface == surface)
            return;
        attachedSurface = surface;
        if (platformView != null)
            platformView.setToplevel(wpeToplevel);
        wpeToplevel.onSurfaceCreated(surface);

        int[] size = new int[2];
        wpeToplevel.getSize(size);
        if (platformView != null && size[0] > 0 && size[1] > 0) {
            platformView.resized(size[0], size[1]);
            platformView.setMapped(true);
        }
    }

    void detachToplevelSurface() {
        if (wpeToplevel != null) {
            if (platformView != null)
                platformView.setMapped(false);
            attachedSurface = null;
            wpeToplevel.onSurfaceDestroyed();
        }
    }

    public void setWebViewClient(@NonNull WebViewClient client) { this.viewClient = client; }
    public @NonNull WebViewClient getWebViewClient() { return viewClient; }

    public void setWebChromeClient(@NonNull WebChromeClient client) { this.chromeClient = client; }
    public @NonNull WebChromeClient getWebChromeClient() { return chromeClient; }

    public @NonNull String getUrl() { return currentUrl; }
    public @Nullable String getOriginalUrl() { return originalUrl; }
    public @NonNull String getTitle() { return currentTitle; }
    public int getProgress() { return currentProgress; }
    public boolean canGoBack() { return canGoBack; }
    public boolean canGoForward() { return canGoForward; }

    public void loadUrl(@NonNull String url) {
        originalUrl = url;
        if (webKitWebView != null)
            webKitWebView.loadUrl(url);
    }

    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        originalUrl = baseUri;
        if (webKitWebView != null)
            webKitWebView.loadHtml(content, baseUri);
    }

    public void loadData(@NonNull String data, @Nullable String mimeType, @Nullable String encoding) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null);
    }

    public void loadDataWithBaseURL(@Nullable String baseUrl, @NonNull String data, @Nullable String mimeType,
                                    @Nullable String encoding, @Nullable String historyUrl) {
        // Mirrors android.webkit.WebView.loadDataWithBaseURL: extra args are advisory.
        // The current backend always parses content as UTF-8 HTML.
        loadHtml(data, baseUrl);
    }

    public void goBack() {
        if (webKitWebView != null)
            webKitWebView.goBack();
    }
    public void goForward() {
        if (webKitWebView != null)
            webKitWebView.goForward();
    }
    public void reload() {
        if (webKitWebView != null)
            webKitWebView.reload();
    }
    public void stopLoading() {
        if (webKitWebView != null)
            webKitWebView.stopLoading();
    }
    public @NonNull WebSettings getSettings() { return webSettings; }
    public @NonNull CookieManager getCookieManager() { return wpeContext.getCookieManager(); }

    @FunctionalInterface
    public interface JavascriptCallback {
        void onResult(@NonNull String result);
    }

    public void evaluateJavascript(@NonNull String script, @Nullable JavascriptCallback callback) {
        if (webKitWebView != null)
            webKitWebView.evaluateJavascript(script, callback != null ? result -> callback.onResult(result) : null);
    }

    public void setTLSErrorsPolicy(int policy) { wpeContext.getWebKitNetworkSession().setTLSErrorsPolicy(policy); }

    // Package-private: called from the WebKitWebView.Listener inner class (avoids a synthetic accessor).
    void showScriptDialog(long dialogPtr, int type, @Nullable String url, @Nullable String message,
                          @NonNull String defaultText) {
        ScriptDialogResult result = new ScriptDialogResult(dialogPtr);
        boolean handled = false;
        if (type == WebKitWebView.SCRIPT_DIALOG_ALERT) {
            handled = chromeClient.onJsAlert(this, url, message, result);
        } else if (type == WebKitWebView.SCRIPT_DIALOG_CONFIRM) {
            handled = chromeClient.onJsConfirm(this, url, message, result);
        } else if (type == WebKitWebView.SCRIPT_DIALOG_PROMPT) {
            handled = chromeClient.onJsPrompt(this, url, message, defaultText, result);
        } else if (type == WebKitWebView.SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM) {
            handled = chromeClient.onJsBeforeUnload(this, url, message, result);
        }
        if (handled)
            return;

        String title = url;
        String displayMessage = message;
        int positiveTextId = R.string.ok;
        int negativeTextId = R.string.cancel;
        if (type == WebKitWebView.SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM) {
            title = getContext().getString(R.string.js_dialog_before_unload_title);
            displayMessage = getContext().getString(R.string.js_dialog_before_unload, message);
            positiveTextId = R.string.js_dialog_before_unload_positive_button;
            negativeTextId = R.string.js_dialog_before_unload_negative_button;
        } else if (url != null) {
            try {
                URL alertUrl = new URL(url);
                title = "The page at " + alertUrl.getProtocol() + "://" + alertUrl.getHost() + " says";
            } catch (MalformedURLException ex) {
                // Keep the raw URL as the title.
            }
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title);
        builder.setOnCancelListener(new ScriptDialogCancelListener(result));
        if (type != WebKitWebView.SCRIPT_DIALOG_PROMPT) {
            builder.setMessage(displayMessage);
            builder.setPositiveButton(positiveTextId, new ScriptDialogPositiveListener(result, null));
        } else {
            @SuppressLint("InflateParams")
            final View view = LayoutInflater.from(getContext()).inflate(R.layout.js_prompt, null);
            EditText edit = view.findViewById(R.id.value);
            edit.setText(defaultText);
            builder.setPositiveButton(positiveTextId, new ScriptDialogPositiveListener(result, edit));
            ((TextView)view.findViewById(R.id.message)).setText(message);
            builder.setView(view);
        }
        if (type != WebKitWebView.SCRIPT_DIALOG_ALERT) {
            builder.setNegativeButton(negativeTextId, new ScriptDialogCancelListener(result));
        }
        builder.show();
    }

    private class ScriptDialogResult implements WPEJsPromptResult {
        private final long dialogPtr;
        private @Nullable String stringResult;

        ScriptDialogResult(long dialogPtr) { this.dialogPtr = dialogPtr; }

        @Override
        public void cancel() {
            // webKitWebView is null once the view has been destroyed; the dialog is torn down with
            // the page in that case, so there is nothing to answer.
            if (webKitWebView != null) {
                webKitWebView.scriptDialogConfirm(dialogPtr, false, stringResult);
                webKitWebView.scriptDialogClose(dialogPtr);
            }
        }

        @Override
        public void confirm() {
            if (webKitWebView != null) {
                webKitWebView.scriptDialogConfirm(dialogPtr, true, stringResult);
                webKitWebView.scriptDialogClose(dialogPtr);
            }
        }

        @Override
        public void confirm(@NonNull String result) {
            stringResult = result;
            confirm();
        }
    }

    private static class ScriptDialogCancelListener
        implements DialogInterface.OnCancelListener, DialogInterface.OnClickListener {
        private final WPEJsResult result;

        ScriptDialogCancelListener(@NonNull WPEJsResult result) { this.result = result; }

        @Override
        public void onCancel(DialogInterface dialog) {
            result.cancel();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            result.cancel();
        }
    }

    private static class ScriptDialogPositiveListener implements DialogInterface.OnClickListener {
        private final WPEJsPromptResult result;
        private final @Nullable EditText edit;

        ScriptDialogPositiveListener(@NonNull WPEJsPromptResult result, @Nullable EditText edit) {
            this.result = result;
            this.edit = edit;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (edit != null) {
                result.confirm(edit.getText().toString());
            } else {
                result.confirm();
            }
        }
    }

    private static class HttpResourceRequest implements WPEResourceRequest {
        private final Uri uri;
        private final String method;
        HttpResourceRequest(Uri uri, String method) {
            this.uri = uri;
            this.method = method;
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
    }

    /**
     * Internal SurfaceView that handles the drawing surface.
     */
    class PageSurfaceView extends SurfaceView {
        public PageSurfaceView(android.content.Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent event) {
            if (platformView == null)
                return false;

            // MotionEvent coords are in physical pixels; WPE expects logical pixels.
            float density = getContext().getResources().getDisplayMetrics().density;

            int action = event.getActionMasked();
            int type;
            int actionIndex;
            switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                WebView.this.requestFocus();
                type = WPEEventType.TOUCH_DOWN;
                actionIndex = event.getActionIndex();
                platformView.dispatchTouchEvent(
                    event.getEventTime(), type, 1, new int[] {event.getPointerId(actionIndex)},
                    new float[] {event.getX(actionIndex) / density}, new float[] {event.getY(actionIndex) / density});
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                type = WPEEventType.TOUCH_UP;
                actionIndex = event.getActionIndex();
                platformView.dispatchTouchEvent(
                    event.getEventTime(), type, 1, new int[] {event.getPointerId(actionIndex)},
                    new float[] {event.getX(actionIndex) / density}, new float[] {event.getY(actionIndex) / density});
                if (action == MotionEvent.ACTION_UP && inputFieldFocused && inputMethodManager != null)
                    inputMethodManager.showSoftInput(WebView.this, InputMethodManager.SHOW_IMPLICIT);
                return true;
            case MotionEvent.ACTION_MOVE:
                type = WPEEventType.TOUCH_MOVE;
                break;
            case MotionEvent.ACTION_CANCEL:
                type = WPEEventType.TOUCH_CANCEL;
                break;
            default:
                return false;
            }

            // For MOVE and CANCEL send all active pointers
            int pointerCount = event.getPointerCount();
            int[] ids = new int[pointerCount];
            float[] xs = new float[pointerCount];
            float[] ys = new float[pointerCount];
            for (int i = 0; i < pointerCount; i++) {
                ids[i] = event.getPointerId(i);
                xs[i] = event.getX(i) / density;
                ys[i] = event.getY(i) / density;
            }
            platformView.dispatchTouchEvent(event.getEventTime(), type, pointerCount, ids, xs, ys);
            return true;
        }
    }

    void onImeFieldFocused(boolean focused) {
        inputFieldFocused = focused;
        if (inputMethodManager == null)
            return;
        if (focused) {
            // restartInput refreshes EditorInfo (and re-invokes onCreateInputConnection) for the new field.
            inputMethodManager.restartInput(this);
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        } else if (getWindowToken() != null) {
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        if (!inputFieldFocused || imContext == null)
            return null;
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.actionLabel = null;
        return new WPEInputConnection(this, /* fullEditor */ true, imContext);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return inputFieldFocused;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (platformView == null)
            return super.dispatchKeyEvent(event);

        int action = event.getAction();
        int type;
        if (action == KeyEvent.ACTION_DOWN)
            type = WPEEventType.KEYBOARD_KEY_DOWN;
        else if (action == KeyEvent.ACTION_UP)
            type = WPEEventType.KEYBOARD_KEY_UP;
        else
            return super.dispatchKeyEvent(event);

        platformView.dispatchKeyEvent(event.getEventTime(), type, event.getKeyCode(), event.getUnicodeChar(),
                                      event.getMetaState());
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (wpeToplevel != null) {
            wpeToplevel.setPhysicalSize(w, h);
            int[] size = new int[2];
            wpeToplevel.getSize(size);
            if (platformView != null)
                platformView.resized(size[0], size[1]);
        }
    }

    class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            attachToplevelSurface(holder.getSurface());
            viewClient.onViewReady(WebView.this);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            attachToplevelSurface(holder.getSurface());
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            detachToplevelSurface();
        }
    }
}
