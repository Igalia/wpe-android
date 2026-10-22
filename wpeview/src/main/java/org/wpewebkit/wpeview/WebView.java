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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.R;
import org.wpewebkit.wpe.WPEEventType;
import org.wpewebkit.wpe.WPEInputMethodContext;
import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitSettings;
import org.wpewebkit.wpe.WebKitWebView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

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
    private WebKitSettings webKitSettings;
    private WebSettings webSettings;
    private @Nullable Surface attachedSurface;
    private boolean inToplevel;

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
        // Validate XML before allocating the owned context or any native view objects.
        int backgroundColor = initialBackgroundColor();
        init(new WebContext(context), true, false, backgroundColor);
    }

    public WebView(@NonNull WebContext context) {
        super(context.getApplicationContext());
        init(context, false, false, initialBackgroundColor());
    }

    /**
     * Constructs a {@link WebView} that renders with {@code uiContext} while sharing the given
     * {@link WebContext}. Pass an Activity context (not the application context): it is required to
     * show the built-in JavaScript dialogs and the soft keyboard, mirroring
     * {@code android.webkit.WebView}.
     */
    public WebView(@NonNull android.content.Context uiContext, @NonNull WebContext context) {
        super(uiContext);
        init(context, false, false, initialBackgroundColor());
    }

    /**
     * Constructs a {@link WebView} without an on-screen Surface. JS, navigation and listeners
     * still work; the view simply never gets a backing window so the compositor reports it as
     * unmappable. Used by automation/WebDriver headless sessions.
     */
    public WebView(@NonNull WebContext context, boolean headless) {
        super(context.getApplicationContext());
        init(context, false, headless, initialBackgroundColor());
    }

    private void init(@NonNull WebContext context, boolean ownsContext, boolean headless,
                      @ColorInt int backgroundColor) {
        this.wpeContext = context;
        this.ownsContext = ownsContext;
        this.headless = headless;

        this.webKitSettings = new WebKitSettings();
        webKitSettings.setUserAgentString(wpeContext.getDefaultUserAgent());
        this.webKitWebView = new WebKitWebView(wpeContext.getWPEDisplay(), wpeContext.getWebKitWebContext(), null,
                                               wpeContext.getWebKitNetworkSession(), webKitSettings);

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
        this.webSettings = new WebSettings(webKitSettings);
        this.wpeToplevel = new WPEToplevel(wpeContext.getWPEDisplay(), null);
        applyBackgroundColor(backgroundColor);
        // A new SurfaceView cuts its hole in the window only after its first draw. Until then the window shows this
        // view's own background where the page goes, so give it the page color too. This also makes getBackground()
        // match the page color.
        if (getBackground() == null)
            super.setBackgroundColor(backgroundColor);
        // WebKit gives every new view a toplevel of its own, which this replaces. The view starts out of any
        // toplevel and joins the WebView's when the WebView gets a surface; a headless WebView never gets one, so
        // its view stays in the toplevel for its whole life.
        this.platformView.setToplevel(headless ? wpeToplevel : null);

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

    /**
     * Sets the opaque color behind the page, including before its first frame is available.
     * The default is white. The same color is used by WebKit for pages without an explicit
     * background; a page's CSS background takes precedence. This does not enable dark mode
     * or change the page's text colors. Call again when the embedding app changes its theme.
     * A solid XML background color is used as the initial color; other Android background
     * drawables do not configure the native page background. To change the color later, call
     * this method: setBackground() and setBackgroundResource() do not change it.
     *
     * <p>Unlike android.webkit.WebView, transparent and translucent backgrounds are not yet
     * supported: the underlying SurfaceView has an opaque background layer.
     *
     * @throws IllegalArgumentException if the color is not opaque
     */
    @Override
    public void setBackgroundColor(@ColorInt int color) {
        applyBackgroundColor(color);
        super.setBackgroundColor(color);
    }

    private int initialBackgroundColor() {
        int color =
            getBackground() instanceof ColorDrawable ? ((ColorDrawable)getBackground()).getColor() : Color.WHITE;
        if (Color.alpha(color) != 255)
            throw new IllegalArgumentException("WebView background color must be opaque");
        return color;
    }

    private void applyBackgroundColor(@ColorInt int color) {
        if (Color.alpha(color) != 255)
            throw new IllegalArgumentException("WebView background color must be opaque");
        // View construction and calls after destroy() have no native objects to update.
        if (webKitWebView != null)
            webKitWebView.setBackgroundColor(color);
        if (wpeToplevel != null)
            wpeToplevel.setBackgroundColor(color);
    }

    @NonNull
    WebKitWebView getInternalWebKitWebView() {
        return webKitWebView;
    }

    public void destroy() {
        // Unfocusing the view, when it leaves its toplevel, clears inputFieldFocused through the input method
        // context.
        boolean inputMethodConnected = inputFieldFocused;
        leaveToplevel();
        if (platformView != null)
            platformView.setToplevel(null);
        if (wpeToplevel != null) {
            wpeToplevel.destroy();
            wpeToplevel = null;
        }
        if (webKitWebView != null) {
            webKitWebView.destroy();
            webKitWebView = null;
            // The view and input method proxies are borrowed from the WebKitWebView, and die with it.
            platformView = null;
            imContext = null;
            if (inputMethodConnected)
                releaseInputMethod();
        }
        if (webKitSettings != null) {
            webSettings.detach();
            webKitSettings.destroy();
            webKitSettings = null;
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
        joinToplevel();
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
            leaveToplevel();
            attachedSurface = null;
            wpeToplevel.onSurfaceDestroyed();
        }
    }

    // The view is in its toplevel, and so the page in a window, while the WebView has a surface. Android takes
    // the surface away when the WebView leaves its window and when its activity stops.
    private void joinToplevel() {
        if (platformView == null || inToplevel)
            return;
        inToplevel = true;
        platformView.setToplevel(wpeToplevel);
        wpeToplevel.setWindowFocused(hasWindowFocus());
        platformView.setFocused(isFocused());
        platformView.setVisible(isShown());
    }

    // Leaving the toplevel neither unmaps the view nor clears the focus and activity it took from it, so this
    // does both before the view leaves.
    private void leaveToplevel() {
        if (platformView == null || !inToplevel)
            return;
        inToplevel = false;
        platformView.setMapped(false);
        platformView.setFocused(false);
        wpeToplevel.setWindowFocused(false);
        platformView.setToplevel(null);
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
        // The engine always parses content as UTF-8 HTML.
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
    /**
     * Returns the settings of this WebView. Every WebView has its own, starting from the defaults of its
     * {@link WebContext}, so changing them does not affect other WebViews. After {@link #destroy()} they keep
     * their last values, and changing them has no effect.
     */
    public @NonNull WebSettings getSettings() { return webSettings; }
    public @NonNull CookieManager getCookieManager() { return wpeContext.getCookieManager(); }

    @FunctionalInterface
    public interface JavascriptCallback {
        void onResult(@NonNull String result);
    }

    @FunctionalInterface
    public interface SnapshotCallback {
        void onResult(@Nullable Bitmap bitmap);
    }

    public static final int SNAPSHOT_REGION_VISIBLE = WebKitWebView.SNAPSHOT_REGION_VISIBLE;
    public static final int SNAPSHOT_REGION_FULL_DOCUMENT = WebKitWebView.SNAPSHOT_REGION_FULL_DOCUMENT;

    @IntDef({SNAPSHOT_REGION_VISIBLE, SNAPSHOT_REGION_FULL_DOCUMENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SnapshotRegion {}

    /**
     * Captures a snapshot of the currently visible page content and delivers it as a
     * {@link Bitmap} to {@code callback} on the main thread. The callback always runs after
     * this method returns, never synchronously. The caller owns the returned bitmap.
     * <p>
     * The snapshot is a new paint of the page by WebKit, not a copy of the screen: WebGL
     * canvases appear in it, but playing video does not.
     * <p>
     * The bitmap is in device pixels, but WebKit sizes the page in whole CSS pixels, so at a
     * fractional density it can be a pixel or two smaller than the view: a 1080 by 2400 view at
     * density 2.625 gives a 1078 by 2399 snapshot. Use the size of the bitmap, not of the view.
     * <p>
     * The bitmap is null if the snapshot could not be taken, for example because the view was
     * already destroyed when this method was called, or the bitmap could not be allocated. A
     * snapshot that is already running when the view is destroyed still delivers its bitmap.
     */
    public void captureSnapshot(@NonNull SnapshotCallback callback) {
        captureSnapshot(SNAPSHOT_REGION_VISIBLE, callback);
    }

    /**
     * Captures a snapshot of the page content for the given {@code region}, which must be
     * {@link #SNAPSHOT_REGION_VISIBLE} or {@link #SNAPSHOT_REGION_FULL_DOCUMENT}, and delivers it
     * like {@link #captureSnapshot(SnapshotCallback)}.
     * <p>
     * A full document snapshot can be very large. The bitmap is in device pixels, 4 bytes each,
     * and WebKit does not limit its size, so a long page can take hundreds of megabytes. That
     * memory is held twice for a moment, by WebKit and by the bitmap, while the pixels are copied
     * on the main thread. Android does not draw bitmaps larger than 100 MB on a hardware
     * accelerated canvas, so scale such a bitmap down before showing it.
     *
     * @throws IllegalArgumentException if the region is not one of the two above.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, @NonNull SnapshotCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (region != SNAPSHOT_REGION_VISIBLE && region != SNAPSHOT_REGION_FULL_DOCUMENT)
            throw new IllegalArgumentException("Unknown snapshot region: " + region);
        if (webKitWebView != null)
            webKitWebView.captureSnapshot(region, result -> callback.onResult(result));
        else
            getContext().getMainExecutor().execute(() -> callback.onResult(null));
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
        // A focus change posted before destroy() may arrive after it.
        if (webKitWebView == null)
            return;
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

    // Makes an input method connected to the destroyed page let go of it: restarting the input asks for a new
    // connection, which no longer exists.
    private void releaseInputMethod() {
        inputFieldFocused = false;
        if (inputMethodManager == null)
            return;
        inputMethodManager.restartInput(this);
        if (getWindowToken() != null)
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
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
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        // Hiding the WebView or one of its ancestors keeps its surface; WPE unmaps a view that is not visible.
        if (webKitWebView != null && !headless)
            platformView.setVisible(isVisible);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (webKitWebView != null && !headless)
            wpeToplevel.setWindowFocused(hasWindowFocus);
    }

    @Override
    protected void onDetachedFromWindow() {
        // The SurfaceView child detaches first and normally loses its surface then; a surface still here is let
        // go of the same way, so a later attach with it joins the toplevel again.
        if (attachedSurface != null)
            detachToplevelSurface();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, @Nullable android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        // Mirror the widget's focus onto the WebKit view so DOM focus, document.hasFocus() and the
        // input method engage; without this a focused editable never raises the IME.
        if (platformView != null)
            platformView.setFocused(gainFocus);
    }

    // Keys that Android handles, as in GeckoView: the volume keys change the volume, and Back, Forward, Menu, Search
    // and the headset button go to the app or the system. They are not sent to the page, which would consume them.
    private static boolean isKeyForAndroid(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_VOLUME_UP:
        case KeyEvent.KEYCODE_VOLUME_DOWN:
        case KeyEvent.KEYCODE_VOLUME_MUTE:
        case KeyEvent.KEYCODE_BACK:
        case KeyEvent.KEYCODE_FORWARD:
        case KeyEvent.KEYCODE_MENU:
        case KeyEvent.KEYCODE_SEARCH:
        case KeyEvent.KEYCODE_HEADSETHOOK:
            return true;
        default:
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (platformView == null || isKeyForAndroid(event.getKeyCode()))
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
