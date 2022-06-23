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
public class WPEView extends FrameLayout implements WPESurfaceViewObserver
{
    private static final String LOGTAG = "WPEView";

    private final Context m_context;

    private WebChromeClient m_chromeClient;
    private WPEViewClient m_wpeViewClient;
    private SurfaceClient m_surfaceClient;
    private int m_currentLoadProgress = 0;
    private String m_title = "about:blank";
    private String m_url = "about:blank";
    private String m_originalUrl = "about:blank";

    private WPESurfaceView m_wpeSurfaceView;
    private FrameLayout m_customView;

    private WPESettings m_settings = new WPESettings();

    public WPEView(final Context context)
    {
        super(context);
        m_context = context;

        Browser.initialize(context);
    }

    public WPEView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
        m_context = context;

        Browser.initialize(context);
    }

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        WPEView self = this;
        // Queue the creation of the Page until view's measure, layout, etc.
        // so we have a known width and height to create the associated WebKitWebView
        // before having the Surface texture.
        post(() -> Browser.getInstance().createPage(self, m_context));
    }

    @Override
    protected void onDetachedFromWindow()
    {
        super.onDetachedFromWindow();
        Browser.getInstance().destroyPage(this);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility)
    {
        super.onWindowVisibilityChanged(visibility);
        Browser.getInstance().onVisibilityChanged(this, visibility);
    }

    @Override
    @WorkerThread
    public void onSurfaceViewCreated(WPESurfaceView view)
    {
        Log.v(LOGTAG, "WPESurfaceView created " + view + " number of views " + getChildCount());
        m_wpeSurfaceView = view;

        post(() -> {
            // Run on the main thread
            try {
                addView(view);
            } catch (Exception e) {
                Log.e(LOGTAG, "Error setting view", e);
            }
        });
    }

    @Override
    @WorkerThread
    public void onSurfaceViewReady(WPESurfaceView view)
    {
        Log.v(LOGTAG, "WPESurfaceView ready " + getChildCount());
        // FIXME: Once PSON is enabled we may want to do something smarter here and not
        //        display the view until this point.
        post(() -> {
            if (m_wpeViewClient != null) {
                m_wpeViewClient.onViewReady(WPEView.this);
            }
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            Browser.getInstance().deleteInputMethodContent(this, -1);
            return true;
        }

        final KeyCharacterMap kmap = KeyCharacterMap.load(event != null ?
            event.getDeviceId() : KeyCharacterMap.VIRTUAL_KEYBOARD);
        Browser.getInstance().setInputMethodContent(this, (char)kmap.get(keyCode, event.getMetaState()));
        return true;
    }

    private void runOnMainThread(Runnable runnable)
    {
        try {
            post(runnable);
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot run main thread", e);
        }
    }

    public void onLoadChanged(int loadEvent)
    {
        WPEView self = this;
        switch (loadEvent) {
        case Page.LOAD_STARTED:
            if (m_wpeViewClient == null) {
                return;
            }
            runOnMainThread(() -> m_wpeViewClient.onPageStarted(self, m_url));
            break;
        case Page.LOAD_FINISHED:
            onLoadProgress(100);
            if (m_wpeViewClient == null) {
                return;
            }
            runOnMainThread(() -> m_wpeViewClient.onPageFinished(self, m_url));
            break;
        }
    }

    public void onLoadProgress(double progress)
    {
        m_currentLoadProgress = (int)(progress * 100);
        if (m_chromeClient == null) {
            return;
        }
        WPEView self = this;
        runOnMainThread(() -> m_chromeClient.onProgressChanged(self, m_currentLoadProgress));
    }

    public void onUriChanged(String uri)
    {
        m_url = uri;
    }

    public void onTitleChanged(String title)
    {
        m_title = title;
        if (m_chromeClient == null) {
            return;
        }
        WPEView self = this;
        runOnMainThread(() -> m_chromeClient.onReceivedTitle(self, m_title));
    }

    public void enterFullScreen()
    {
        runOnMainThread(() -> {
            removeView(m_wpeSurfaceView);

            m_customView = new FrameLayout(m_context);
            m_customView.addView(m_wpeSurfaceView);
            m_customView.setFocusable(true);
            m_customView.setFocusableInTouchMode(true);

            m_chromeClient.onShowCustomView(m_customView, () -> {
                if (m_customView != null) {
                    Browser.getInstance().requestExitFullscreenMode(WPEView.this);
                }
            });
        });
    }

    public void exitFullScreen()
    {
        runOnMainThread(() -> {
            if (m_customView != null)  {
                m_customView.removeView(m_wpeSurfaceView);
                addView(m_wpeSurfaceView);
                m_customView = null;

                m_chromeClient.onHideCustomView();
            }
        });
    }

    /************** PUBLIC WPEView API *******************/

    /**
     * Gets the WPESettings object used to control the settings for this WPEView.
     */
    public WPESettings getSettings()
    {
        return m_settings;
    }

    /**
     * Loads the given URL.
     * @param url The URL of the resource to be loaded.
     */
    public void loadUrl(@NonNull String url)
    {
        m_originalUrl = url;
        Browser.getInstance().loadUrl(this, m_context, url);
    }

    /**
     * Gets whether this WPEView has a back history item.
     *
     * @return true if this WPEView has a back history item.
     */
    public boolean canGoBack()
    {
        return Browser.getInstance().canGoBack(this);
    }

    /**
     * Gets whether this WPEView has a forward history item.
     *
     * @return true if this WPEView has a forward history item.
     */
    public boolean canGoForward()
    {
        return Browser.getInstance().canGoForward(this);
    }

    /**
     * Goes back in the history of this WPEView.
     */
    public void goBack()
    {
        Browser.getInstance().goBack(this);
    }

    /**
     * Goes forward in the history of this WPEView.
     */
    public void goForward()
    {
        Browser.getInstance().goForward(this);
    }

    /**
     * Stop the current load.
     */
    public void stopLoading()
    {
        Browser.getInstance().stopLoading(this);
    }

    /**
     * Reloads the current URL.
     */
    public void reload()
    {
        Browser.getInstance().reload(this);
    }

    /**
     * Gets the progress for the current page.
     *
     * @return the progress for the current page between 0 and 100
     */
    public int getProgress()
    {
        return m_currentLoadProgress;
    }

    /**
     * Gets the title for the current page. This is the title of the current page until
     * WebViewClient.onReceivedTitle is called
     *
     * @return the title for the current page or null
     */
    public String getTitle()
    {
        return m_title;
    }

    /**
     * Get the url for the current page. This is not always the same as the url
     * passed to WebViewClient.onPageStarted because although the load for
     * that url has begun, the current page may not have changed.
     *
     * @return The url for the current page.
     */
    public String getUrl()
    {
        return m_url;
    }

    /**
     * Get the original url for the current page. This is not always the same
     * as the url passed to WebViewClient.onPageStarted because although the
     * load for that url has begun, the current page may not have changed.
     * Also, there may have been redirects resulting in a different url to that
     * originally requested.
     *
     * @return The url that was originally requested for the current page.
     */
    public String getOriginalUrl()
    {
        return m_originalUrl;
    }

    /**
     * Sets the chrome handler. This is an implementation of WebChromeClient for
     * use in handling JavaScript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     *
     * @param client an implementation of WebChromeClient
     * @see #getWebChromeClient
     */
    public void setWebChromeClient(@Nullable WebChromeClient client)
    {
        m_chromeClient = client;
    }

    /**
     * Gets the chrome handler.
     *
     * @return the WebChromeClient, or {@code null} if not yet set
     * @see #setWebChromeClient
     */
    @Nullable
    public WebChromeClient getWebChromeClient()
    {
        return m_chromeClient;
    }

    /**
     * Set the WPEViewClient that will receive various notifications and
     * requests. This will replace the current handler.
     * @param client An implementation of WPEViewClient.
     */
    public void setWPEViewClient(@Nullable WPEViewClient client)
    {
        m_wpeViewClient = client;
    }

    /**
     * Gets the WPEViewClient.
     *
     * @return the WPEViewClient, or {@code null} if not yet set
     * @see #setWPEViewClient
     */
    @Nullable
    public WPEViewClient getWPEViewClient()
    {
        return m_wpeViewClient;
    }

    /**
     * Set the SurfaceClient that will manage the Surface where
     * WPEView renders it's contents to. This will replace the current handler.
     * @param client An implementation of SurfaceClient.
     */
    public void setSurfaceClient(@Nullable SurfaceClient client)
    {
        m_surfaceClient = client;
    }

    /**
     * Gets the SurfaceClient.
     *
     * @return the SurfaceClient, or {@code null} if not yet set
     * @see #setSurfaceClient
     */
    @Nullable
    public SurfaceClient getSurfaceClient()
    {
        return m_surfaceClient;
    }
}
