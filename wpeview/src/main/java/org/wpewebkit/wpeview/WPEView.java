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

package org.wpewebkit.wpeview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.wpewebkit.wpe.WKCallback;
import org.wpewebkit.wpe.WKRuntime;
import org.wpewebkit.wpe.WKWebView;

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

    private WKWebView wkWebView;
    private WPESettings wpeSettings;
    private SurfaceClient surfaceClient = null;

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
        init(context, false, headless);
    }

    private void init(@NonNull WPEContext context, boolean ownsContext, boolean headless) {
        wpeContext = context;
        this.ownsContext = ownsContext;
        wkWebView = new WKWebView(this, wpeContext.getWebContext(), headless);
        wpeSettings = new WPESettings(wkWebView.getSettings());

        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    /**
     * Destroys the internal state of this WebView. This method should be called
     * after this WebView has been removed from the view system. No other
     * methods may be called on this WebView after destroy.
     */
    public void destroy() {
        wkWebView.destroy();
        if (ownsContext) {
            wpeContext.destroy();
        }
    }

    /**
     * Enables remote inspector for debugging WPEView from remote machine (from desktop machine for example).
     * If useHttpInspector is true then HTTP protocol is used to connect to inspector server, otherwise
     * inspector:// protocol is used. With HTTP protocol any browser can connect to remote inspector. Inspector://
     * protocol only works with WebKit browsers.
     * <p>
     * NOTE! This needs to be called before any WPEView or WPEContext is created.
     *
     * @param inspectorPort The port for which inspector server listens
     * @param useHttpInspector Whether to use HTTP protocol for remote inspector server connection. If false then
     *                         inspector:// protocol is used.
     */
    public static void enableRemoteInspector(int inspectorPort, boolean useHttpInspector) {
        WKRuntime.enableRemoteInspector(inspectorPort, useHttpInspector);
    }

    /**
     * Loads the given URL.
     * @param url The URL of the resource to load.
     */
    public void loadUrl(@NonNull String url) { wkWebView.loadUrl(url); }

    /**
     * Loads an HTML page from its content.
     * @param content The HTML content to load.
     * @param baseUri The base URI for the content loaded.
     */
    public void loadHtml(@NonNull String content, @Nullable String baseUri) { wkWebView.loadHtml(content, baseUri); }

    /**
     * Gets whether this WPEView has a back history item.
     * @return true if this WPEView has a back history item.
     */
    public boolean canGoBack() { return wkWebView.canGoBack(); }

    /**
     * Gets whether this WPEView has a forward history item.
     * @return true if this WPEView has a forward history item.
     */
    public boolean canGoForward() { return wkWebView.canGoForward(); }

    /**
     * Goes back in the history of this WPEView.
     */
    public void goBack() { wkWebView.goBack(); }

    /**
     * Goes forward in the history of this WPEView.
     */
    public void goForward() { wkWebView.goForward(); }

    /**
     * Stop current loading process.
     */
    public void stopLoading() { wkWebView.stopLoading(); }

    /**
     * Reloads the current page.
     */
    public void reload() { wkWebView.reload(); }

    /**
     * Returns whether this WebView is muted.
     * @return {@code true} if the WebView is muted, {@code false} otherwise.
     */
    public boolean isMuted() { return wkWebView.isMuted(); }

    /**
     * Sets whether this WebView should be muted.
     * @param muted {@code true} to mute the WebView, {@code false} to unmute.
     */
    public void setMuted(boolean muted) { wkWebView.setMuted(muted); }

    /**
     * Gets loading progress for the current page.
     * @return the loading progress for the current page (between 0 and 100).
     */
    public int getProgress() { return wkWebView.getEstimatedLoadProgress(); }

    /**
     * Gets the title for the current page. This is the title of the current page
     * until WPEViewClient.onReceivedTitle is called.
     *
     * @return the title for the current page or {@code null} if no page has been loaded
     */
    public @Nullable String getTitle() { return wkWebView.getTitle(); }

    /**
     * Gets the URL for the current page. This is not always the same as the URL
     * passed to WPEViewClient.onPageStarted because although the load for
     * that URL has begun, the current page may not have changed.
     *
     * @return the URL for the current page or {@code null} if no page has been loaded
     */
    public @Nullable String getUrl() { return wkWebView.getUrl(); }

    /**
     * Gets the original URL for the current page. This is not always the same
     * as the URL passed to WPEViewClient.onPageStarted because although the
     * load for that URL has begun, the current page may not have changed.
     * Also, there may have been redirects resulting in a different URL to that
     * originally requested.
     *
     * @return the URL that was originally requested for the current page or
     * {@code null} if no page has been loaded
     */
    public @Nullable String getOriginalUrl() { return wkWebView.getOriginalUrl(); }

    /**
     * Gets the chrome handler.
     * @return the WPEChromeClient, or {@code null} if it has not been set yet.
     * @see #setWPEChromeClient
     */
    public @Nullable WPEChromeClient getWPEChromeClient() { return wkWebView.getWPEChromeClient(); }

    /**
     * Sets the chrome handler. This is an implementation of WPEChromeClient for
     * use in handling JavaScript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     * @param client an implementation of WPEChromeClient
     * @see #getWPEChromeClient
     */
    public void setWPEChromeClient(@Nullable WPEChromeClient client) { wkWebView.setWPEChromeClient(client); }

    /**
     * Gets the WPEViewClient.
     *
     * @return the WPEViewClient, or a default client if not yet set
     * @see #setWPEViewClient
     */
    public @NonNull WPEViewClient getWPEViewClient() { return wkWebView.getWPEViewClient(); }

    /**
     * Sets the WPEViewClient that will receive various notifications and
     * requests. This will replace the current handler.
     *
     * @param client an implementation of WPEViewClient
     * @see #getWPEViewClient
     */
    public void setWPEViewClient(@NonNull WPEViewClient client) { wkWebView.setWPEViewClient(client); }

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
     * Sets the policy to follow when connecting to a resource with an invalid SSL certificate.
     *
     * @param policy pass {@code WKWebView.WEBKIT_TLS_ERRORS_POLICY_IGNORE} to ignore all SSL certificates errors
     * (it should only be used for testing purposes), or pass {@code WKWebView.WEBKIT_TLS_ERRORS_POLICY_FAIL}
     * to fail when trying to reach a resource with an invalid SSL certificate (default behavior).
     */
    public void setTLSErrorsPolicy(int policy) { wkWebView.setTLSErrorsPolicy(policy); }

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
        wkWebView.evaluateJavascript(script, WKCallback.fromWPECallback(resultCallback));
    }

    /**
     * Return the WPESettings object used to control the settings for this
     * WPEView.
     * @return A WPESettings object that can be used to control this WPEView's
     *         settings.
     */
    public @NonNull WPESettings getSettings() { return wpeSettings; }

    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        // Only provide InputConnection when an input field is focused in the web content
        if (!wkWebView.isInputFieldFocused()) {
            return null;
        }

        // Configure the EditorInfo for the soft keyboard
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.actionLabel = null;

        // Return our custom InputConnection that bridges to WebKit
        return new WPEInputConnection(this, true, wkWebView);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        // Tell Android this view is a text editor when an input field is focused
        return wkWebView.isInputFieldFocused();
    }

    // Internal API
    @NonNull
    WKWebView getWKWebView() {
        return wkWebView;
    }
}
