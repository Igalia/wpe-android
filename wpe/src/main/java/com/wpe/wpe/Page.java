package com.wpe.wpe;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.wpe.wpe.gfx.View;
import com.wpe.wpe.services.WPEServiceConnection;
import com.wpe.wpeview.WPEView;

import java.lang.ref.WeakReference;

/**
 * A Page roughly corresponds with a tab in a regular browser UI.
 * There is a 1:1 relationship between WPEView and Page.
 * Each Page instance has its own wpe.wpe.gfx.View and WebKitWebView instances associated.
 * It also keeps references to the Services that host the logic of WebKit's auxiliary
 * processes (WebProcess and NetworkProcess).
 */
@UiThread
public class Page {
    private final String LOGTAG;

    static public final int LOAD_STARTED = 0;
    static public final int LOAD_REDIRECTED = 1;
    static public final int LOAD_COMMITTED = 2;
    static public final int LOAD_FINISHED = 3;

    private final Browser m_browser;

    private final Context m_context;

    private final WPEView m_wpeView;

    private boolean m_closed = false;

    private final int m_width;
    private final int m_height;

    private final PageThreadMessageHandler m_handler;
    private PageThread m_thread;

    private View m_view;
    private boolean m_viewReady = false;

    private long m_webViewRef = 0;

    private String m_pendingLoad;

    private boolean m_canGoBack = true;
    private boolean m_canGoForward = true;

    private static class PageThreadMessageHandler extends Handler {
        private final WeakReference<Page> m_page;

        PageThreadMessageHandler(Page page) {
            m_page = new WeakReference<>(page);
        }

        @Override
        public void handleMessage(Message msg) {
            Page page = m_page.get();
            if (page == null) {
                return;
            }
            page.onViewReady();
        }
    }

    /**
     * This thread is used to get a valid Surface texture from its associated gfx.View.
     */
    private class PageThread {
        private Thread m_thread;
        private View m_view;
        private PageThreadMessageHandler m_handler;

        PageThread() {
            final PageThread self = this;
            m_thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(LOGTAG, "In Page thread");
                    while (true) {
                        synchronized (self) {
                            try {
                                while (self.m_view == null) {
                                    Log.i(LOGTAG, "Waiting");
                                    self.wait();
                                }
                            } catch (InterruptedException e) {
                                Log.v(LOGTAG, "Interruption in Page thread");
                            }
                        }
                        Log.v(LOGTAG, "ensureSurfaceTexture");
                        m_view.ensureSurfaceTexture();
                        Log.v(LOGTAG, "Surface texture ready");
                        // Go back to the main thread.
                        m_handler.sendEmptyMessage(0);
                        break;
                    }
                }
            });
            m_thread.start();
        }

        public void run(@NonNull View view, @NonNull PageThreadMessageHandler handler) {
            final PageThread self = this;
            synchronized (self) {
                m_view = view;
                m_handler = handler;
                self.notifyAll();
            }
        }

        public void stop() {
            m_thread.interrupt();
        }
    }

    public Page(@NonNull Browser browser, @NonNull Context context, @NonNull WPEView wpeView, @NonNull String pageId) {
        LOGTAG = "WPE page" + pageId;

        Log.v(LOGTAG, "Page construction " + this);

        m_browser = browser;

        m_context = context;

        m_wpeView = wpeView;

        m_width =  wpeView.getMeasuredWidth();
        m_height = wpeView.getMeasuredHeight();

        m_handler = new PageThreadMessageHandler(this);

        m_view = new View(m_context);
        m_wpeView.onViewCreated(m_view);
        ensureSurface();

        ensureWebView();
    }

    public void close() {
        if (m_closed) {
            return;
        }
        m_closed = true;
        Log.v(LOGTAG, "Page destruction");
        m_view.releaseTexture();
        BrowserGlue.destroyWebView(m_webViewRef);
        m_webViewRef = 0;
    }

    /**
     * Callback triggered when the associated WebKitWebView instance is created.
     * This is called by the JNI layer. See `Java_com_wpe_wpe_BrowserGlue_newWebView`
     * @param webViewRef The reference to the associated WebKitWebView instance.
     */
    @Keep
    public void onWebViewReady(long webViewRef) {
        Log.v(LOGTAG, "WebKitWebView ready");
        m_webViewRef = webViewRef;
        if (m_viewReady) {
            loadUrlInternal();
        }
    }

    private void ensureWebView() {
        if (m_webViewRef != 0) {
            onWebViewReady(m_webViewRef);
            return;
        }
        // Requests the creation of a new WebKitWebView. On creation, the `onWebViewReady` callback
        // is triggered.
        BrowserGlue.newWebView(this, m_width, m_height);
    }

    public void onViewReady() {
        Log.d(LOGTAG, "onViewReady");
        m_wpeView.onViewReady(m_view);
        m_browser.provideSurface();
        m_viewReady = true;
        if (m_webViewRef != 0) {
            loadUrlInternal();
        }
    }

    private void ensureSurface() {
        if (m_thread != null) {
            m_thread.stop();
            m_thread = null;
        }
        m_thread = new PageThread();
        m_thread.run(m_view, m_handler);
    }

    @WorkerThread
    public WPEServiceConnection launchService(int processType, Parcelable[] fds, Class cls) {
        // This runs in the UIProcess thread.
        Log.v(LOGTAG, "launchService type: " + processType);
        Intent intent = new Intent(m_context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, this, fds);
        switch (processType) {
            case WPEServiceConnection.PROCESS_TYPE_WEBPROCESS:
                // FIXME: we probably want to kill the current web process here if any exists when
                //        PSON is enabled.
                m_browser.setWebProcess(serviceConnection);
                break;
            case WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS:
                m_browser.setNetworkProcess(serviceConnection);
                break;
            default:
                throw new IllegalArgumentException("Unknown process type");
        }
        m_context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        return serviceConnection;
    }

    @WorkerThread
    public void stopService(WPEServiceConnection serviceConnection) {
        Log.d(LOGTAG, "stopService type: " + serviceConnection.processType());
        // This runs in the UIProcess thread.
        // FIXME: Until we fully support PSON, we won't do anything here.
    }

    public View view() {
        return m_view;
    }

    private void loadUrlInternal() {
        if (m_pendingLoad == null) {
            return;
        }
        BrowserGlue.loadURL(m_webViewRef, m_pendingLoad);
        m_pendingLoad = null;
    }

    public void loadUrl(@NonNull Context context, @NonNull String url) {
        Log.d(LOGTAG, "Queue URL load " + url);
        m_pendingLoad = url;
        ensureWebView();
    }

    public void onLoadChanged(int loadEvent) {
        m_wpeView.onLoadChanged(loadEvent);
    }

    public void onLoadProgress(double progress) {
        m_wpeView.onLoadProgress(progress);
    }

    public void onUriChanged(String uri) {
        m_wpeView.onUriChanged(uri);
    }

    public void onTitleChanged(String title) {
        m_wpeView.onTitleChanged(title);
    }

    public boolean canGoBack() {
        // FIXME this value need to be properly fetched from BrowserGlue and cached locally
        return m_canGoBack;
    }

    public boolean canGoForward() {
        // FIXME this value need to be properly fetched from BrowserGlue and cached locally
        return m_canGoForward;
    }

    public void goBack() {
        if (m_webViewRef != 0) {
            BrowserGlue.goBack(m_webViewRef);
        }
    }

    public void goForward() {
        if (m_webViewRef != 0) {
            BrowserGlue.goForward(m_webViewRef);
        }
    }

    public void reload() {
        if (m_webViewRef != 0) {
            BrowserGlue.reload(m_webViewRef);
        }
    }
}
