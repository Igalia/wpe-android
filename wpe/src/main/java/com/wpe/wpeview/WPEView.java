package com.wpe.wpeview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.wpe.wpe.Browser;
import com.wpe.wpe.Page;
import com.wpe.wpe.gfx.WPESurfaceView;
import com.wpe.wpe.gfx.WPESurfaceViewObserver;

/**
 * WPEView wraps WPE WebKit browser engine in a reusable Android library.
 * WPEView serves a similar purpose to Android's built-in WebView and tries to mimic
 * its API aiming to be an easy to use drop-in replacement with extended functionality.
 *
 * The WPEView class is the main API entry point.
 */
@UiThread
public class WPEView extends FrameLayout implements WPESurfaceViewObserver {
    private static final String LOGTAG = "WPEView";

    private final Context context;
    private final WPESettings settings = new WPESettings();

    private WebChromeClient chromeClient;
    private WPEViewClient wpeViewClient;
    private SurfaceClient surfaceClient;
    private int currentLoadProgress = 0;
    private String title = "about:blank";
    private String url = "about:blank";
    private String originalUrl = "about:blank";
    private WPESurfaceView wpeSurfaceView;
    private FrameLayout customView;

    public WPEView(Context context) {
        super(context);
        this.context = context;

        Browser.initialize(context);
    }

    public WPEView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        Browser.initialize(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final WPEView self = this;
        // Queue the creation of the Page until view's measure, layout, etc.
        // so we have a known width and height to create the associated WebKitWebView
        // before having the Surface texture.
        post(() -> Browser.getInstance().createPage(self, context));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Browser.getInstance().destroyPage(this);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        Browser.getInstance().onVisibilityChanged(this, visibility);
    }

    @Override
    public void onSurfaceViewCreated(WPESurfaceView view) {
        Log.v(LOGTAG, "WPESurfaceView created " + view + " number of views " + getChildCount());
        wpeSurfaceView = view;

        post(() -> {
            // Delay adding view a bit to next run cycle
            try {
                addView(view);
            } catch (Exception e) {
                Log.e(LOGTAG, "Error setting view", e);
            }
        });
    }

    @Override
    public void onSurfaceViewReady(WPESurfaceView view) {
        Log.v(LOGTAG, "WPESurfaceView ready " + getChildCount());
        // FIXME: Once PSON is enabled we may want to do something smarter here and not
        //        display the view until this point.
        post(() -> {
            if (wpeViewClient != null) {
                wpeViewClient.onViewReady(WPEView.this);
            }
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            Browser.getInstance().deleteInputMethodContent(this, -1);
            return true;
        }

        final KeyCharacterMap kmap =
            KeyCharacterMap.load(event != null ? event.getDeviceId() : KeyCharacterMap.VIRTUAL_KEYBOARD);
        Browser.getInstance().setInputMethodContent(this, (char)kmap.get(keyCode, event.getMetaState()));
        return true;
    }

    public void onLoadChanged(int loadEvent) {
        switch (loadEvent) {
        case Page.LOAD_STARTED:
            if (wpeViewClient == null) {
                return;
            }
            wpeViewClient.onPageStarted(this, url);
            break;
        case Page.LOAD_FINISHED:
            onLoadProgress(100);
            if (wpeViewClient == null) {
                return;
            }
            wpeViewClient.onPageFinished(this, url);
            break;
        }
    }

    public void onLoadProgress(double progress) {
        currentLoadProgress = (int)(progress * 100);
        if (chromeClient == null) {
            return;
        }
        chromeClient.onProgressChanged(this, currentLoadProgress);
    }

    public void onUriChanged(String uri) { url = uri; }

    public void onTitleChanged(String title) {
        this.title = title;
        if (chromeClient == null) {
            return;
        }
        chromeClient.onReceivedTitle(this, title);
    }

    public void enterFullScreen() {
        removeView(wpeSurfaceView);

        customView = new FrameLayout(context);
        customView.addView(wpeSurfaceView);
        customView.setFocusable(true);
        customView.setFocusableInTouchMode(true);

        chromeClient.onShowCustomView(customView, () -> {
            if (customView != null) {
                Browser.getInstance().requestExitFullscreenMode(WPEView.this);
            }
        });
    }

    public void exitFullScreen() {
        if (customView != null) {
            customView.removeView(wpeSurfaceView);
            addView(wpeSurfaceView);
            customView = null;

            chromeClient.onHideCustomView();
        }
    }

    /************** PUBLIC WPEView API *******************/

    /**
     * Gets the WPESettings object used to control the settings for this WPEView.
     */
    public WPESettings getSettings() { return settings; }

    /**
     * Loads the given URL.
     *
     * @param url The URL of the resource to be loaded.
     */
    public void loadUrl(@NonNull String url) {
        originalUrl = url;
        Browser.getInstance().loadUrl(this, context, url);
    }

    /**
     * Gets whether this WPEView has a back history item.
     *
     * @return true if this WPEView has a back history item.
     */
    public boolean canGoBack() { return Browser.getInstance().canGoBack(this); }

    /**
     * Gets whether this WPEView has a forward history item.
     *
     * @return true if this WPEView has a forward history item.
     */
    public boolean canGoForward() { return Browser.getInstance().canGoForward(this); }

    /**
     * Goes back in the history of this WPEView.
     */
    public void goBack() { Browser.getInstance().goBack(this); }

    /**
     * Goes forward in the history of this WPEView.
     */
    public void goForward() { Browser.getInstance().goForward(this); }

    /**
     * Stop the current load.
     */
    public void stopLoading() { Browser.getInstance().stopLoading(this); }

    /**
     * Reloads the current URL.
     */
    public void reload() { Browser.getInstance().reload(this); }

    /**
     * Gets the progress for the current page.
     *
     * @return the progress for the current page between 0 and 100
     */
    public int getProgress() { return currentLoadProgress; }

    /**
     * Gets the title for the current page. This is the title of the current page until
     * WebViewClient.onReceivedTitle is called
     *
     * @return the title for the current page or null
     */
    public String getTitle() { return title; }

    /**
     * Get the url for the current page. This is not always the same as the url
     * passed to WebViewClient.onPageStarted because although the load for
     * that url has begun, the current page may not have changed.
     *
     * @return The url for the current page.
     */
    public String getUrl() { return url; }

    /**
     * Get the original url for the current page. This is not always the same
     * as the url passed to WebViewClient.onPageStarted because although the
     * load for that url has begun, the current page may not have changed.
     * Also, there may have been redirects resulting in a different url to that
     * originally requested.
     *
     * @return The url that was originally requested for the current page.
     */
    public String getOriginalUrl() { return originalUrl; }

    /**
     * Gets the chrome handler.
     *
     * @return the WebChromeClient, or {@code null} if not yet set
     * @see #setWebChromeClient
     */
    @Nullable
    public WebChromeClient getWebChromeClient() {
        return chromeClient;
    }

    /**
     * Sets the chrome handler. This is an implementation of WebChromeClient for
     * use in handling JavaScript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     *
     * @param client an implementation of WebChromeClient
     * @see #getWebChromeClient
     */
    public void setWebChromeClient(@Nullable WebChromeClient client) { chromeClient = client; }

    /**
     * Gets the WPEViewClient.
     *
     * @return the WPEViewClient, or {@code null} if not yet set
     * @see #setWPEViewClient
     */
    @Nullable
    public WPEViewClient getWPEViewClient() {
        return wpeViewClient;
    }

    /**
     * Set the WPEViewClient that will receive various notifications and
     * requests. This will replace the current handler.
     *
     * @param client An implementation of WPEViewClient.
     */
    public void setWPEViewClient(@Nullable WPEViewClient client) { wpeViewClient = client; }

    /**
     * Gets the SurfaceClient.
     *
     * @return the SurfaceClient, or {@code null} if not yet set
     * @see #setSurfaceClient
     */
    @Nullable
    public SurfaceClient getSurfaceClient() {
        return surfaceClient;
    }

    /**
     * Set the SurfaceClient that will manage the Surface where
     * WPEView renders it's contents to. This will replace the current handler.
     *
     * @param client An implementation of SurfaceClient.
     */
    public void setSurfaceClient(@Nullable SurfaceClient client) { surfaceClient = client; }
}
