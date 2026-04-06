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
import android.graphics.Color;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitWebView;

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
    private WebKitWebView webKitWebView;
    private WPEToplevel wpeToplevel;
    private WPEView platformView;
    private PageSurfaceView surfaceView;
    private WebSettings webSettings;
    private @Nullable Surface attachedSurface;

    private ViewClient viewClient = new ViewClient();
    private ChromeClient chromeClient = new ChromeClient();
    private String currentUrl = "about:blank";
    private String currentTitle = "";
    private int currentProgress = 0;
    private boolean canGoBack = false;
    private boolean canGoForward = false;

    public WebView(@NonNull android.content.Context context) { this(context, (AttributeSet)null); }

    public WebView(@NonNull android.content.Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(new WebContext(context), true);
    }

    public WebView(@NonNull WebContext context) {
        super(context.getApplicationContext());
        init(context, false);
    }

    private void init(@NonNull WebContext context, boolean ownsContext) {
        this.wpeContext = context;
        this.ownsContext = ownsContext;

        this.webKitWebView = new WebKitWebView(wpeContext.getWPEDisplay(), wpeContext.getWebKitWebContext(), null,
                                               wpeContext.getWebKitNetworkSession(), wpeContext.getWebKitSettings());

        this.platformView = webKitWebView.getWPEView();
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
                chromeClient.onUriChanged(WebView.this, uri);
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
        });

        this.surfaceView = new PageSurfaceView(getContext());
        this.surfaceView.getHolder().addCallback(new SurfaceCallback());
        addView(surfaceView);

        setFocusable(true);
        setFocusableInTouchMode(true);
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

    private void attachToplevelSurface(@NonNull Surface surface) {
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

    private void detachToplevelSurface() {
        if (wpeToplevel != null) {
            if (platformView != null)
                platformView.setMapped(false);
            attachedSurface = null;
            wpeToplevel.onSurfaceDestroyed();
        }
    }

    public void setViewClient(@NonNull ViewClient client) { this.viewClient = client; }
    public void setChromeClient(@NonNull ChromeClient client) { this.chromeClient = client; }

    public @NonNull String getUrl() { return currentUrl; }
    public @NonNull String getTitle() { return currentTitle; }
    public int getProgress() { return currentProgress; }
    public boolean canGoBack() { return canGoBack; }
    public boolean canGoForward() { return canGoForward; }

    public void loadUrl(@NonNull String url) {
        if (webKitWebView != null)
            webKitWebView.loadUrl(url);
    }

    public void loadHtml(@NonNull String content, @Nullable String baseUri) {
        if (webKitWebView != null)
            webKitWebView.loadHtml(content, baseUri);
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
    private class PageSurfaceView extends SurfaceView {
        public PageSurfaceView(android.content.Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent event) {
            if (platformView == null)
                return false;

            // WPEEventType values from WPEEvent.h
            final int WPE_EVENT_TOUCH_DOWN = 9;
            final int WPE_EVENT_TOUCH_UP = 10;
            final int WPE_EVENT_TOUCH_MOVE = 11;
            final int WPE_EVENT_TOUCH_CANCEL = 12;

            // MotionEvent coords are in physical pixels; WPE expects logical pixels.
            float density = getContext().getResources().getDisplayMetrics().density;

            int action = event.getActionMasked();
            int type;
            int actionIndex;
            switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                WebView.this.requestFocus();
                type = WPE_EVENT_TOUCH_DOWN;
                actionIndex = event.getActionIndex();
                platformView.dispatchTouchEvent(
                    event.getEventTime(), type, 1, new int[] {event.getPointerId(actionIndex)},
                    new float[] {event.getX(actionIndex) / density}, new float[] {event.getY(actionIndex) / density});
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                type = WPE_EVENT_TOUCH_UP;
                actionIndex = event.getActionIndex();
                platformView.dispatchTouchEvent(
                    event.getEventTime(), type, 1, new int[] {event.getPointerId(actionIndex)},
                    new float[] {event.getX(actionIndex) / density}, new float[] {event.getY(actionIndex) / density});
                return true;
            case MotionEvent.ACTION_MOVE:
                type = WPE_EVENT_TOUCH_MOVE;
                break;
            case MotionEvent.ACTION_CANCEL:
                type = WPE_EVENT_TOUCH_CANCEL;
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (platformView == null)
            return super.dispatchKeyEvent(event);

        // WPEEventType values from WPEEvent.h
        final int WPE_EVENT_KEYBOARD_KEY_DOWN = 7;
        final int WPE_EVENT_KEYBOARD_KEY_UP = 8;

        int action = event.getAction();
        int type;
        if (action == KeyEvent.ACTION_DOWN)
            type = WPE_EVENT_KEYBOARD_KEY_DOWN;
        else if (action == KeyEvent.ACTION_UP)
            type = WPE_EVENT_KEYBOARD_KEY_UP;
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

    private class SurfaceCallback implements SurfaceHolder.Callback {
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
