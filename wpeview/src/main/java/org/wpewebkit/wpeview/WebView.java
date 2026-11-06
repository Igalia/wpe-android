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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
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
import org.wpewebkit.wpe.LocalNetworkAccess;
import org.wpewebkit.wpe.WPEEventType;
import org.wpewebkit.wpe.WPEInputHints;
import org.wpewebkit.wpe.WPEInputMethodContext;
import org.wpewebkit.wpe.WPEInputPurpose;
import org.wpewebkit.wpe.WPEScreen;
import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitDownload;
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
    private static final String LOGTAG = "WPEWebView";

    private WebContext wpeContext;
    private @Nullable OwnedContext ownedContext;
    private boolean headless;
    // Package-private: read by the ScriptDialogResult inner class (avoids a synthetic accessor).
    WebKitWebView webKitWebView;
    // Package-private: read by the touch handler of the surface inner class (avoids a synthetic accessor).
    WPEToplevel wpeToplevel;
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
    // The title of the page, empty while it has none.
    String currentTitle = "";
    // The last title given to the chrome client, which getTitle returns.
    @Nullable
    String reportedTitle;
    int currentProgress = 0;
    boolean canGoBack = false;
    boolean canGoForward = false;
    // While the chrome client is asked for a new window of the page, and the WebView made for it then, if any.
    private boolean creatingWindow;
    private @Nullable WebView createdWindow;

    public WebView(@NonNull android.content.Context context) { this(context, (AttributeSet)null); }

    public WebView(@NonNull android.content.Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // Validate XML before allocating the owned context or any native view objects.
        int backgroundColor = initialBackgroundColor();
        WebContext ownContext = new WebContext(context);
        init(ownContext, new OwnedContext(ownContext), false, backgroundColor, null);
    }

    // A WebContext that a WebView made for itself, which the windows its pages open share. The last of them to be
    // destroyed destroys it.
    private static final class OwnedContext {
        final WebContext context;
        int users = 1;

        OwnedContext(@NonNull WebContext context) { this.context = context; }
    }

    public WebView(@NonNull WebContext context) {
        super(context.getApplicationContext());
        init(context, null, false, initialBackgroundColor(), null);
    }

    /**
     * Constructs a {@link WebView} that renders with {@code uiContext} while sharing the given
     * {@link WebContext}. Pass an Activity context (not the application context): it is required to
     * show the built-in JavaScript dialogs and the soft keyboard, mirroring
     * {@code android.webkit.WebView}.
     */
    public WebView(@NonNull android.content.Context uiContext, @NonNull WebContext context) {
        super(uiContext);
        init(context, null, false, initialBackgroundColor(), null);
    }

    /**
     * Constructs the {@link WebView} of a new window that the page in {@code opener} asks for, which
     * {@link WebChromeClient#onCreateWindow} returns. It can only be made there, once in each call. It shares the
     * {@link WebContext} of {@code opener}, and one that {@code opener} made for itself stays until both are destroyed.
     * Like any new WebView, it has settings of its own. Pass an Activity context, as for
     * {@link #WebView(android.content.Context, WebContext)}.
     *
     * @throws IllegalStateException if {@code opener} is not asking for a window in
     *     {@link WebChromeClient#onCreateWindow}, or a WebView was already made for it there.
     */
    public WebView(@NonNull android.content.Context uiContext, @NonNull WebView opener) {
        super(uiContext);
        if (!opener.creatingWindow || opener.createdWindow != null || opener.webKitWebView == null)
            throw new IllegalStateException(
                "The WebView of a new window can only be made once in WebChromeClient.onCreateWindow");
        if (opener.ownedContext != null)
            opener.ownedContext.users++;
        init(opener.wpeContext, opener.ownedContext, false, initialBackgroundColor(), opener.webKitWebView);
        opener.createdWindow = this;
    }

    /**
     * Constructs a {@link WebView} without an on-screen Surface. JS, navigation and listeners
     * still work; the view simply never gets a backing window so the compositor reports it as
     * unmappable. Used by automation/WebDriver headless sessions.
     */
    public WebView(@NonNull WebContext context, boolean headless) {
        super(context.getApplicationContext());
        init(context, null, headless, initialBackgroundColor(), null);
    }

    // The view of a new window is related to the view of its opener, whose page asks for it.
    private void init(@NonNull WebContext context, @Nullable OwnedContext ownedContext, boolean headless,
                      @ColorInt int backgroundColor, @Nullable WebKitWebView openerView) {
        this.wpeContext = context;
        this.ownedContext = ownedContext;
        this.headless = headless;

        this.webKitSettings = new WebKitSettings();
        webKitSettings.setUserAgentString(wpeContext.getDefaultUserAgent());
        this.webKitWebView = openerView != null
                                 ? new WebKitWebView(openerView, webKitSettings)
                                 : new WebKitWebView(wpeContext.getWPEDisplay(), wpeContext.getWebKitWebContext(), null,
                                                     wpeContext.getWebKitNetworkSession(), webKitSettings);

        this.platformView = webKitWebView.getWPEView();
        this.imContext = webKitWebView.getInputMethodContext();
        this.inputMethodManager = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        this.imContext.setListener(new WPEInputMethodContext.Listener() {
            @Override
            public void onFocusIn() {
                onImeFieldFocused(true);
            }
            @Override
            public void onFocusOut() {
                onImeFieldFocused(false);
            }
            @Override
            public void onPreeditReset() {
                onImePreeditReset();
            }
            @Override
            public void onInputPurposeChanged() {
                onImeInputPurposeChanged();
            }
        });
        this.webSettings = new WebSettings(webKitSettings);
        this.wpeToplevel = new WPEToplevel(wpeContext.getWPEDisplay(), null);
        this.wpeToplevel.setListener(this::onFullscreenRequested);
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
            }

            @Override
            public void onLoadProgress(double progress) {
                currentProgress = (int)Math.round(progress * 100);
                chromeClient.onProgressChanged(WebView.this, currentProgress);
            }

            @Override
            public void onTitleChanged(String title) {
                currentTitle = title;
                updateTitle();
            }

            @Override
            public void onHistoryChanged(String uri, boolean goBack, boolean goForward) {
                canGoBack = goBack;
                canGoForward = goForward;
                viewClient.doUpdateVisitedHistory(WebView.this, uri, /* isReload */ false);
                updateTitle();
            }

            @Override
            public void onReceivedHttpError(String uri, String method, String mimeType, int statusCode,
                                            boolean isMainFrame) {
                WPEResourceRequest request = new HttpResourceRequest(Uri.parse(uri), method, isMainFrame, false, false);
                WPEResourceResponse response = new WPEResourceResponse(mimeType, statusCode, Collections.emptyMap());
                viewClient.onReceivedHttpError(WebView.this, request, response);
            }

            @Override
            public void onReceivedError(String uri, String method, int errorCode, String description) {
                // The network process tells when Android blocked a connection before it ends it, so it is known by
                // the time the load fails because of it. Ending the connection fails the load with one of these codes;
                // other failures, such as a host name that does not resolve, have another reason.
                Uri parsedUri = Uri.parse(uri);
                String host = parsedUri.getHost();
                boolean endedConnection = errorCode == WebViewClient.ERROR_IO ||
                                          errorCode == WebViewClient.ERROR_CONNECT ||
                                          errorCode == WebViewClient.ERROR_UNKNOWN;
                boolean blocked =
                    endedConnection && host != null && LocalNetworkAccess.getInstance().isBlocked(getContext(), host);
                WPEResourceRequest request = new HttpResourceRequest(parsedUri, method, true, false, false);
                // WebKit's description would say that the server ended the connection.
                WPEResourceError error =
                    blocked ? new WPEResourceError(WebViewClient.ERROR_LOCAL_NETWORK_ACCESS_DENIED,
                                                   "Could not connect to " + host +
                                                       ": the app does not have the local network permission")
                            : new WPEResourceError(errorCode, description);
                viewClient.onReceivedError(WebView.this, request, error);
            }

            @Override
            public void onScriptDialog(long dialogPtr, int type, String url, String message, String defaultText) {
                showScriptDialog(dialogPtr, type, url, message, defaultText);
            }

            @Override
            public void onWebProcessTerminated(int reason) {
                // The focused field went with the page, so the input method lets go of it.
                if (inputFieldFocused)
                    releaseInputMethod();
                viewClient.onWebProcessTerminated(WebView.this, reason);
            }

            @Override
            public @Nullable WebKitWebView onCreateWindow(boolean isUserGesture) {
                return createWindow(isUserGesture);
            }

            @Override
            public boolean shouldOverrideUrlLoading(String uri, String method, boolean isMainFrame, boolean hasGesture,
                                                    boolean isRedirect) {
                return viewClient.shouldOverrideUrlLoading(
                    WebView.this, new HttpResourceRequest(Uri.parse(uri), method, isMainFrame, hasGesture, isRedirect));
            }

            @Override
            public void onDownloadStarted(WebKitDownload download) {
                viewClient.onDownloadStart(WebView.this, new WPEDownload(download));
            }
        });

        if (!headless) {
            this.surfaceView = new PageSurfaceView(getContext());
            this.surfaceView.getHolder().addCallback(new SurfaceCallback());
            addView(surfaceView);
        }

        setFocusable(true);
        setFocusableInTouchMode(true);
        // The page draws its own focus rings. Android's highlight, drawn when the view gets the focus from a
        // key, would cover the whole page, since it is drawn over the SurfaceView.
        setDefaultFocusHighlightEnabled(false);
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
        exitFullscreen();
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
        if (ownedContext != null) {
            if (--ownedContext.users == 0)
                ownedContext.context.destroy();
            ownedContext = null;
            wpeContext = null;
        }
    }

    void attachToplevelSurface(@NonNull Surface surface) {
        if (wpeContext == null || wpeToplevel == null)
            return;
        if (attachedSurface == surface)
            return;
        attachedSurface = surface;
        updateScreen();
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

    // The page gets the screen of the display the WebView shows on, which changes when its window moves to another
    // display.
    private void updateScreen() {
        Display display = getDisplay();
        if (wpeContext == null || wpeToplevel == null || display == null)
            return;
        WPEScreen screen = wpeContext.getWPEDisplay().getScreen(display);
        if (screen != null)
            wpeToplevel.setScreen(screen);
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
    // does both before the view leaves. A page out of its window cannot stay fullscreen, and WebKit only sees the
    // toplevel leave fullscreen while the view is in it.
    private void leaveToplevel() {
        if (platformView == null || !inToplevel)
            return;
        inToplevel = false;
        platformView.setMapped(false);
        platformView.setFocused(false);
        wpeToplevel.setWindowFocused(false);
        exitFullscreen();
        platformView.setToplevel(null);
    }

    // WebKit asks the toplevel to enter fullscreen when an element of the page goes fullscreen, and to leave it when
    // the page leaves fullscreen by itself. The toplevel has done it already.
    private void onFullscreenRequested(boolean fullscreen) {
        if (fullscreen)
            chromeClient.onEnterFullscreen(this);
        else
            chromeClient.onExitFullscreen(this);
    }

    /**
     * Whether an element of the page is fullscreen, from {@link WebChromeClient#onEnterFullscreen} until
     * {@link WebChromeClient#onExitFullscreen}.
     */
    public boolean isFullscreen() { return wpeToplevel != null && wpeToplevel.isFullscreen(); }

    /**
     * Takes the page out of fullscreen, as the user asks with Back, and calls {@link WebChromeClient#onExitFullscreen}
     * right away. The page sees it a moment later, with a {@code fullscreenchange} event. Does nothing if the page is
     * not fullscreen.
     */
    public void exitFullscreen() {
        if (!isFullscreen())
            return;
        // WebKit takes the page out of fullscreen when the toplevel leaves it, without asking the toplevel again.
        wpeToplevel.setFullscreen(false);
        chromeClient.onExitFullscreen(this);
    }

    public void setWebViewClient(@NonNull WebViewClient client) { this.viewClient = client; }
    public @NonNull WebViewClient getWebViewClient() { return viewClient; }

    public void setWebChromeClient(@NonNull WebChromeClient client) { this.chromeClient = client; }
    public @NonNull WebChromeClient getWebChromeClient() { return chromeClient; }

    public @NonNull String getUrl() { return currentUrl; }
    public @Nullable String getOriginalUrl() { return originalUrl; }
    /**
     * Returns the title of the page, or while it has none, its address, as {@link #titleFromUrl} shows it. This is
     * the title that {@link WebChromeClient#onReceivedTitle} reports, and it is empty before the first one, as with
     * android.webkit.WebView.
     */
    public @NonNull String getTitle() { return reportedTitle != null ? reportedTitle : ""; }

    private @NonNull String pageTitle() { return currentTitle.isEmpty() ? titleFromUrl(currentUrl) : currentTitle; }

    public int getProgress() { return currentProgress; }
    public boolean canGoBack() { return canGoBack; }
    public boolean canGoForward() { return canGoForward; }

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
        String title = pageTitle();
        if (title.equals(reportedTitle))
            return;
        reportedTitle = title;
        chromeClient.onReceivedTitle(this, title);
    }

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
     * Ends the web process that runs the page, for example when the page stops responding. The view then gets
     * {@link WebViewClient#onWebProcessTerminated} with {@link WebViewClient#WEB_PROCESS_TERMINATED_BY_API}, and stays
     * usable: {@link #reload()} loads the page again. Other views whose pages run in the same web process lose them
     * too.
     */
    public void terminateWebProcess() {
        if (webKitWebView != null)
            webKitWebView.terminateWebProcess();
    }

    /**
     * Saves the history of the view, for {@link #restoreState} to give it to a new WebView later, for example after
     * Android ended the app: the Back/Forward list with its current page, and for each page its address, its title, and
     * the scroll position and form contents that WebKit kept for it. Cookies, storage and the state of scripts are not
     * in it. Returns null once the view is destroyed.
     * <p>
     * The state grows with the history and the form contents, which may be private, so keep it in the app's own
     * storage, such as a file. In the Bundle of {@link android.app.Activity#onSaveInstanceState} a large state makes
     * Android fail with a {@link android.os.TransactionTooLargeException}. Save the address of the current page with
     * the state, to load the page if {@link #restoreState} cannot read it.
     */
    public @Nullable byte[] saveState() { return webKitWebView != null ? webKitWebView.saveState() : null; }

    /**
     * Gives a new WebView, before it loads anything, the history that {@link #saveState} saved, and loads its current
     * page again. {@link #canGoBack} and {@link #canGoForward} follow the restored Back/Forward list at once, while the
     * page loads. Returns false, and loads nothing, if the state has no page, or if this version of WPE WebKit cannot
     * read it, as one saved by a newer version: the app can load the address that it saved with the state instead.
     */
    public boolean restoreState(@NonNull byte[] state) {
        Objects.requireNonNull(state, "state");
        if (webKitWebView == null || !webKitWebView.restoreState(state))
            return false;
        canGoBack = webKitWebView.canGoBack();
        canGoForward = webKitWebView.canGoForward();
        return true;
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
     * memory is held twice for a moment, by WebKit and by the bitmap, while the pixels are
     * copied. Android does not draw bitmaps larger than 100 MB on a hardware accelerated canvas.
     * To get a smaller bitmap, use {@link #captureSnapshot(int, int, int, SnapshotCallback)}.
     *
     * @throws IllegalArgumentException if the region is not one of the two above.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, @NonNull SnapshotCallback callback) {
        checkSnapshotArguments(region, callback);
        startSnapshot(region, 0, 0, callback);
    }

    /**
     * Captures a snapshot like {@link #captureSnapshot(int, SnapshotCallback)}, scaled down to fit in
     * {@code maxWidth} by {@code maxHeight} pixels, with the same aspect ratio rounded to whole pixels. A limit
     * of 0 leaves that side unlimited, for example to limit only the width. A snapshot that already fits keeps its
     * size: it is never enlarged.
     * <p>
     * The pixels are scaled while they are copied out of WebKit, so only the scaled bitmap is allocated, which
     * suits thumbnails: at full size, a visible snapshot of a 1080 by 2400 view takes about 10 MB. WebKit still
     * paints the snapshot at full size for a moment, in native memory.
     *
     * @throws IllegalArgumentException if the region is not {@link #SNAPSHOT_REGION_VISIBLE} or
     *     {@link #SNAPSHOT_REGION_FULL_DOCUMENT}, or {@code maxWidth} or {@code maxHeight} is negative.
     * @throws NullPointerException if the callback is null.
     */
    public void captureSnapshot(@SnapshotRegion int region, int maxWidth, int maxHeight,
                                @NonNull SnapshotCallback callback) {
        checkSnapshotArguments(region, callback);
        if (maxWidth < 0 || maxHeight < 0)
            throw new IllegalArgumentException("Snapshot size limits must not be negative: " + maxWidth + "x" +
                                               maxHeight);
        startSnapshot(region, maxWidth, maxHeight, callback);
    }

    private static void checkSnapshotArguments(@SnapshotRegion int region, @NonNull SnapshotCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (region != SNAPSHOT_REGION_VISIBLE && region != SNAPSHOT_REGION_FULL_DOCUMENT)
            throw new IllegalArgumentException("Unknown snapshot region: " + region);
    }

    // A maxWidth or maxHeight of 0 leaves that side unlimited.
    private void startSnapshot(@SnapshotRegion int region, int maxWidth, int maxHeight,
                               @NonNull SnapshotCallback callback) {
        if (webKitWebView != null)
            webKitWebView.captureSnapshot(region, maxWidth, maxHeight, result -> callback.onResult(result));
        else
            getContext().getMainExecutor().execute(() -> callback.onResult(null));
    }

    public void evaluateJavascript(@NonNull String script, @Nullable JavascriptCallback callback) {
        if (webKitWebView != null)
            webKitWebView.evaluateJavascript(script, callback != null ? result -> callback.onResult(result) : null);
    }

    public void setTLSErrorsPolicy(int policy) { wpeContext.getWebKitNetworkSession().setTLSErrorsPolicy(policy); }

    // Package-private: called from the WebKitWebView.Listener inner class (avoids a synthetic accessor). WebKit can
    // only put the page of the new window in a view made for it, in this call.
    @Nullable
    WebKitWebView createWindow(boolean isUserGesture) {
        WebView window;
        WebView created;
        creatingWindow = true;
        try {
            window = chromeClient.onCreateWindow(this, isUserGesture);
        } finally {
            created = createdWindow;
            creatingWindow = false;
            createdWindow = null;
        }
        if (window == null)
            return null;
        // WebKit is waiting for the window, so a wrong one opens none instead of throwing into it.
        if (window != created) {
            Log.e(LOGTAG, "WebChromeClient.onCreateWindow must return the WebView it made for the window with "
                              + "WebView(Context, WebView), or null: no window opens");
            return null;
        }
        // Null if the window was destroyed meanwhile.
        return window.webKitWebView;
    }

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

    /**
     * Internal SurfaceView that handles the drawing surface.
     */
    class PageSurfaceView extends SurfaceView {
        // A tap brings back the keyboard of the focused field, which Back may have hidden: one finger that stays
        // within the touch slop and is lifted before a long press. A scroll, a pinch or a long press does not.
        private final GestureDetector tapDetector;

        public PageSurfaceView(android.content.Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(@NonNull MotionEvent event) {
                    if (inputFieldFocused)
                        showSoftInput();
                    return true;
                }
            });
            // The second tap of a double tap is a tap too.
            tapDetector.setOnDoubleTapListener(null);
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent event) {
            if (platformView == null || wpeToplevel == null)
                return false;
            tapDetector.onTouchEvent(event);

            // MotionEvent coords are in physical pixels; WPE expects logical pixels, at the scale of the screen the
            // page is on.
            float density = wpeToplevel.getScale();

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
            showSoftInput();
        } else if (getWindowToken() != null) {
            inputMethodManager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // Asks the input method to show its keyboard for the focused field.
    void showSoftInput() {
        if (inputMethodManager != null)
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    // WebKit ended the composition (a tap in the page, for instance) and kept its text. The input method still
    // thinks it is composing and would send the whole word again with its next key, so restart it.
    void onImePreeditReset() {
        if (webKitWebView == null || !inputFieldFocused || inputMethodManager == null)
            return;
        inputMethodManager.restartInput(this);
    }

    // The focused field changed its purpose without losing focus, as a password field does when a "show password"
    // button turns it into a text field, or the focus moved to a field with another purpose: WebKit tells no focus
    // out or focus in for that. Restart the input method so that it asks for the new input type.
    void onImeInputPurposeChanged() {
        if (webKitWebView == null || !inputFieldFocused || inputMethodManager == null)
            return;
        inputMethodManager.restartInput(this);
    }

    // A field that has been a password field has the private hint, also when a "show password" button turns it into
    // a text field. It gets a visible password keyboard, which neither suggests nor learns what is typed.
    private static int inputTypeForPurpose(int purpose, int hints) {
        boolean isPrivate = (hints & WPEInputHints.PRIVATE) != 0;
        switch (purpose) {
        case WPEInputPurpose.DIGITS:
            return EditorInfo.TYPE_CLASS_NUMBER | (isPrivate ? EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD : 0);
        case WPEInputPurpose.NUMBER:
            return EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_FLAG_SIGNED |
                EditorInfo.TYPE_NUMBER_FLAG_DECIMAL;
        case WPEInputPurpose.PHONE:
            return EditorInfo.TYPE_CLASS_PHONE;
        case WPEInputPurpose.URL:
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI;
        case WPEInputPurpose.EMAIL:
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        case WPEInputPurpose.PASSWORD:
            return EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        case WPEInputPurpose.PIN:
            return EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
        default:
            return EditorInfo.TYPE_CLASS_TEXT | (isPrivate ? EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                                           : EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);
        }
    }

    // Makes an input method connected to the destroyed page let go of it: restarting the input asks for a new
    // connection, which no longer exists. Package-private: also called from the WebKitWebView.Listener inner class
    // when the web process ends (avoids a synthetic accessor).
    void releaseInputMethod() {
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
        outAttrs.inputType = inputTypeForPurpose(imContext.getInputPurpose(), imContext.getInputHints());
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
        // Hiding the WebView or one of its ancestors keeps its surface; WPE unmaps a view that is not visible. A hidden
        // page cannot stay fullscreen either, as when a tab is hidden in place.
        if (webKitWebView != null && !headless) {
            platformView.setVisible(isVisible);
            if (!isVisible)
                exitFullscreen();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreen();
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
