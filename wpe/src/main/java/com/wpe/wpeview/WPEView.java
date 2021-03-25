package com.wpe.wpeview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.wpe.wpe.Browser;
import com.wpe.wpe.gfx.View;
import com.wpe.wpe.gfx.ViewObserver;

/**
 * WPEView wraps WPE WebKit browser engine in a reusable Android library.
 * WPEView serves a similar purpose to Android's built-in WebView and tries to mimick
 * its API aiming to be an easy to use drop-in replacement with extended functionality.
 *
 * The WPEView class is the main API entry point.
 */
@UiThread
public class WPEView extends FrameLayout implements ViewObserver {
    private static final String LOGTAG = "WPEView";

    private final Context m_context;

    private WebChromeClient m_chromeClient;
    private int m_currentLoadProgress = 0;

    public WPEView(final Context context) {
        super(context);
        m_context = context;
    }

    public WPEView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        m_context = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        WPEView self = this;
        // Queue the creation of the Page until view's measure, layout, etc.
        // so we have a known width and height to create the associated WebKitWebView
        // before having the Surface texture.
        post(new Runnable() {
            @Override
            public void run() {
                Browser.getInstance().createPage(self, m_context);
            }
        });
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

    @Override @WorkerThread
    public void onViewCreated(View view) {
        Log.v(LOGTAG, "View created " + view + " number of views " + getChildCount());
        post(new Runnable() {
            public void run() {
                // Run on the main thread
                try {
                    addView(view);
                } catch(Exception e) {
                    Log.e(LOGTAG, "Error setting view " + e.toString());
                }
            }
        });
    }

    @Override @WorkerThread
    public void onViewReady(View view) {
        Log.v(LOGTAG, "View ready " + getChildCount());
        // FIXME: Once PSON is enabled we may want to do something smarter here and not
        //        display the view until this point.
    }

    public void onLoadProgress(double progress) {
        m_currentLoadProgress = (int) (progress * 100);
        if (m_chromeClient != null) {
            m_chromeClient.onProgressChanged(this, m_currentLoadProgress);
        }
    }

    /************** PUBLIC WPEView API *******************/

    /**
     * Loads the given URL.
     * @param url The URL of the resource to be loaded.
     */
    public void loadUrl(@NonNull String url) {
        Browser.getInstance().loadUrl(this, m_context, url);
    }

    /**
     * Gets whether this WPEView has a back history item.
     *
     * @return true if this WPEView has a back history item.
     */
    public boolean canGoBack() {
        return Browser.getInstance().canGoBack(this);
    }

    /**
     * Gets whether this WPEView has a forward history item.
     *
     * @return true if this WPEView has a forward history item.
     */
    public boolean canGoForward() {
        return Browser.getInstance().canGoForward(this);
    }

    /**
     * Goes back in the history of this WPEView.
     */
    public void goBack() {
        Browser.getInstance().goBack(this);
    }

    /**
     * Goes forward in the history of this WPEView.
     */
    public void goForward() {
        Browser.getInstance().goForward(this);
    }

    /**
     * Reloads the current URL.
     */
    public void reload() {
        Browser.getInstance().reload(this);
    }

    /**
     * Gets the progress for the current page.
     *
     * @return the progress for the current page between 0 and 100
     */
    public int getProgress() {
        return m_currentLoadProgress;
    }

    /**
     * Sets the chrome handler. This is an implementation of WebChromeClient for
     * use in handling JavaScript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     *
     * @param client an implementation of WebChromeClient
     * @see #getWebChromeClient
     */
    public void setWebChromeClient(@Nullable WebChromeClient client) {
        m_chromeClient = client;
    }

    /**
     * Gets the chrome handler.
     *
     * @return the WebChromeClient, or {@code null} if not yet set
     * @see #setWebChromeClient
     */
    @Nullable
    public WebChromeClient getWebChromeClient() {
        return m_chromeClient;
    }
}
