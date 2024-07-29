/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

package com.wpe.wpeview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.wpe.wpe.Page;
import com.wpe.wpe.WKCallback;

/**
 * WPEView wraps WPE WebKit browser engine in a reusable Android library.
 * WPEView serves a similar purpose as the Android built-in WebView and tries to mimic
 * its API aiming to be an easy to use drop-in replacement with extended functionality.
 * <p>The WPEView class is the API main entry point.</p>
 */
@UiThread
public class WPEView extends FrameLayout {
    private static final String LOGTAG = "WPEView";

    private WPEContext wpeContext;
    private boolean ownsContext;

    private Page page;

    public WPEView(@NonNull Context context) {
        super(context);
        init(new WPEContext(context), true, false);
    }

    public WPEView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(new WPEContext(context), true, false);
    }

    public WPEView(@NonNull WPEContext context) { this(context, false); }

    public WPEView(@NonNull WPEContext context, boolean headless) {
        super(context.getApplicationContext());
        init(context, false, false);
    }

    private void init(@NonNull WPEContext context, boolean ownsContext, boolean headless) {
        wpeContext = context;
        this.ownsContext = ownsContext;
        page = new Page(this, wpeContext.getWebContext(), false);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    private SurfaceView surfaceView = null;
    private WPEViewClient wpeViewClient = null;
    private WPEChromeClient wpeChromeClient = null;
    private SurfaceClient surfaceClient = null;
    private int currentLoadProgress = 0;
    private String url = "about:blank";
    private String originalUrl = url;
    private String title = url;
    private FrameLayout customView = null;

    public void onPageSurfaceViewCreated(@NonNull SurfaceView view) {
        Log.d(LOGTAG,
              "onPageSurfaceViewCreated() for view: " + view + " (number of children: " + getChildCount() + ")");
        surfaceView = view;

        post(() -> {
            // Add the view during the next UI cycle
            try {
                addView(view);
            } catch (Exception e) {
                Log.e(LOGTAG, "Error while adding the surface view", e);
            }
        });
    }

    public void onPageSurfaceViewReady(@NonNull SurfaceView view) {
        Log.d(LOGTAG, "onPageSurfaceViewReady() for view: " + view + " (number of children: " + getChildCount() + ")");

        // FIXME: Once PSON is enabled we may want to do something smarter here and not
        //        display the view until this point.
        post(() -> {
            if (wpeViewClient != null)
                wpeViewClient.onViewReady(WPEView.this);
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            page.deleteInputMethodContent(-1);
            return true;
        }

        KeyCharacterMap map = KeyCharacterMap.load(event.getDeviceId());
        page.setInputMethodContent(map.get(keyCode, event.getMetaState()));
        return true;
    }

    public void onLoadChanged(int loadEvent) {
        switch (loadEvent) {
        case Page.LOAD_STARTED:
            if (wpeViewClient != null)
                wpeViewClient.onPageStarted(this, url);
            break;

        case Page.LOAD_FINISHED:
            onLoadProgress(100);
            if (wpeViewClient != null)
                wpeViewClient.onPageFinished(this, url);
            break;
        }
    }

    public void onClose() {
        if (wpeChromeClient != null)
            wpeChromeClient.onCloseWindow(this);
    }

    public void onLoadProgress(double progress) {
        currentLoadProgress = Math.max(0, Math.min(100, (int)Math.round(progress * 100)));
        if (wpeChromeClient != null)
            wpeChromeClient.onProgressChanged(this, currentLoadProgress);
    }

    public void onUriChanged(@NonNull String uri) { url = uri; }

    public void onTitleChanged(@NonNull String title) {
        this.title = title;
        if (wpeChromeClient != null)
            wpeChromeClient.onReceivedTitle(this, title);
    }

    public boolean onDialogScript(int dialogType, @NonNull String url, @NonNull String message,
                                  @NonNull WPEJsResult result) {
        if (wpeChromeClient != null) {
            if (dialogType == Page.WEBKIT_SCRIPT_DIALOG_ALERT) {
                return wpeChromeClient.onJsAlert(this, url, message, result);
            } else if (dialogType == Page.WEBKIT_SCRIPT_DIALOG_CONFIRM) {
                return wpeChromeClient.onJsConfirm(this, url, message, result);
            }
        }
        return false;
    }

    public void onEnterFullscreenMode() {
        if ((surfaceView != null) && (wpeChromeClient != null)) {
            removeView(surfaceView);

            customView = new FrameLayout(getContext());
            customView.addView(surfaceView);
            customView.setFocusable(true);
            customView.setFocusableInTouchMode(true);

            wpeChromeClient.onShowCustomView(customView, () -> {
                if (customView != null)
                    page.requestExitFullscreenMode();
            });
        }
    }

    public void onExitFullscreenMode() {
        if ((customView != null) && (surfaceView != null) && (wpeChromeClient != null)) {
            customView.removeView(surfaceView);
            addView(surfaceView);
            customView = null;
            wpeChromeClient.onHideCustomView();
        }
    }

    /************** PUBLIC WPEView API *******************/

    /**
     * Destroys the internal state of this WebView. This method should be called
     * after this WebView has been removed from the view system. No other
     * methods may be called on this WebView after destroy.
     */
    public void destroy() {
        page.destroy();
        if (ownsContext) {
            wpeContext.destroy();
        }
    }

    /**
     * Gets the page associated with this WPEView.
     * @return the associated page.
     */
    public @NonNull Page getPage() { return page; }

    /**
     * Loads the given URL.
     * @param url The URL of the resource to load.
     */
    public void loadUrl(@NonNull String url) {
        originalUrl = url;
        page.loadUrl(url);
    }

    /**
     * Loads an HTML page from its content.
     * @param content The HTML content to load.
     * @param baseUri The base URI for the content loaded.
     */
    public void loadHtml(@NonNull String content, @NonNull String baseUri) {
        originalUrl = baseUri;
        page.loadHtml(content, baseUri);
    }

    /**
     * Gets whether this WPEView has a back history item.
     * @return true if this WPEView has a back history item.
     */
    public boolean canGoBack() { return page.canGoBack(); }

    /**
     * Gets whether this WPEView has a forward history item.
     * @return true if this WPEView has a forward history item.
     */
    public boolean canGoForward() { return page.canGoForward(); }

    /**
     * Goes back in the history of this WPEView.
     */
    public void goBack() { page.goBack(); }

    /**
     * Goes forward in the history of this WPEView.
     */
    public void goForward() { page.goForward(); }

    /**
     * Stop current loading process.
     */
    public void stopLoading() { page.stopLoading(); }

    /**
     * Reloads the current page.
     */
    public void reload() { page.reload(); }

    /**
     * Gets loading progress for the current page.
     * @return the loading progress for the current page (between 0 and 100).
     */
    public int getProgress() { return currentLoadProgress; }

    /**
     * Gets current page title (until WebViewClient.onReceivedTitle is called).
     * @return the title for the current page
     */
    public @NonNull String getTitle() { return title; }

    /**
     * Get the url for the current page. This is not always the same as the url
     * passed to WebViewClient.onPageStarted because although the load for
     * that url has begun, the current page may not have changed.
     * @return The url for the current page.
     */
    public @NonNull String getUrl() { return url; }

    /**
     * Get the original url for the current page. This is not always the same
     * as the url passed to WebViewClient.onPageStarted because although the
     * load for that url has begun, the current page may not have changed.
     * Also, there may have been redirects resulting in a different url to that
     * originally requested.
     * @return The url that was originally requested for the current page.
     */
    public @NonNull String getOriginalUrl() { return originalUrl; }

    /**
     * Gets the chrome handler.
     * @return the WPEChromeClient, or {@code null} if it has not been set yet.
     * @see #setWPEChromeClient
     */
    public @Nullable WPEChromeClient getWPEChromeClient() { return wpeChromeClient; }

    /**
     * Sets the chrome handler. This is an implementation of WPEChromeClient for
     * use in handling JavaScript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     * @param client an implementation of WPEChromeClient
     * @see #getWPEChromeClient
     */
    public void setWPEChromeClient(@Nullable WPEChromeClient client) { wpeChromeClient = client; }

    /**
     * Gets the WPEViewClient.
     * @return the WPEViewClient, or {@code null} if it has not been set yet.
     * @see #setWPEViewClient
     */
    public @Nullable WPEViewClient getWPEViewClient() { return wpeViewClient; }

    /**
     * Set the WPEViewClient that will receive various notifications and
     * requests. This will replace the current handler.
     * @param client An implementation of WPEViewClient.
     */
    public void setWPEViewClient(@Nullable WPEViewClient client) { wpeViewClient = client; }

    /**
     * Gets the SurfaceClient.
     * @return the SurfaceClient, or {@code null} if it has not been set yet.
     * @see #setSurfaceClient
     */
    public @Nullable SurfaceClient getSurfaceClient() { return surfaceClient; }

    /**
     * Set the SurfaceClient that will manage the Surface where
     * WPEView renders it's contents to. This will replace the current handler.
     * @param client An implementation of SurfaceClient.
     */
    public void setSurfaceClient(@Nullable SurfaceClient client) { surfaceClient = client; }

    /**
     * Gets the WPECookieManager instance associated with WPEContext of this view.
     *
     * @return the WPECookieManager instance
     */
    public @NonNull WPECookieManager getCookieManager() { return wpeContext.getCookieManager(); }

    /**
     * Asynchronously evaluates JavaScript in the context of the currently displayed page.
     * If non-null, {@code resultCallback} will be invoked with any result returned from that
     * execution. This method must be called on the UI thread and the callback will
     * be made on the UI thread.
     *
     * @param script the JavaScript to execute.
     * @param resultCallback A callback to be invoked when the script execution
     *                       completes with the result of the execution (if any).
     *                       May be {@code null} if no notification of the result is required.
     */
    public void evaluateJavascript(@NonNull String script, @Nullable WPECallback<String> resultCallback) {
        page.evaluateJavascript(script, WKCallback.fromWPECallback(resultCallback));
    }
}
