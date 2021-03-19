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

import com.wpe.wpe.gfx.View;
import com.wpe.wpe.services.WPEServiceConnection;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

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
    private final BrowserGlue m_glue;
    private final Context m_context;
    private final ArrayList<WPEServiceConnection> m_services;
    private PageThread m_thread;
    private View m_view;
    private long m_webViewRef = 0;
    private String m_pendingLoad;

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
            page.ensureWebView();
        }
    }

    /**
     * This thread is used during the initialization process of each Page instance
     * to get a reference to its associated WebKitWebView from the browser glue and
     * a valid Surface texture from its associated gfx.View.
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
                        try {
                            while (self.m_view == null) {
                                self.wait();
                            }
                        } catch (InterruptedException e) {
                            Log.v(LOGTAG, "Interruption in Page thread");
                        }
                        m_view.ensureSurfaceTexture();
                        Log.v(LOGTAG, "Surface texture ready");
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
    }

    public Page(@NonNull Context context, @NonNull String pageId, @NonNull View view, @NonNull BrowserGlue browserGlue) {
        LOGTAG = "WPE page" + pageId;

        Log.v(LOGTAG, "Page construction " + this);

        m_context = context;
        m_glue = browserGlue;
        m_view = view;
        m_services = new ArrayList<>();

        ensureWebViewAndSurface();
    }

    public void close() {
        Log.v(LOGTAG, "Page destruction");
        for (WPEServiceConnection serviceConnection : m_services) {
            m_context.unbindService(serviceConnection);
        }
        m_services.clear();
        m_view.release();
        BrowserGlue.destroyWebView(m_webViewRef);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    private void ensureWebViewAndSurface() {
        m_thread = new PageThread();
        m_thread.run(m_view, new PageThreadMessageHandler(this));
    }

    private void ensureWebView() {
        assert(m_view.width() > 0);
        assert(m_view.height() > 0);
        if (m_webViewRef != 0) {
            onReady(m_webViewRef);
            return;
        }
        BrowserGlue.newWebView(this, m_view.width(), m_view.height());
    }

    /**
     * Callback triggered when the associated WebKitWebView instance is created.
     * This is called by the JNI layer. See `Java_com_wpe_wpe_BrowserGlue_newWebView`
     * @param webViewRef The reference to the associated WebKitWebView instance.
     */
    @Keep
    public void onReady(long webViewRef) {
       Log.v(LOGTAG, "Page ready");
       m_webViewRef = webViewRef;
       if (m_pendingLoad != null) {
           loadUrlInternal();
       }
    }

    public WPEServiceConnection launchService(int processType, Parcelable[] fds, Class cls) {
        Intent intent = new Intent(m_context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, this, fds);
        m_services.add(serviceConnection);
        m_context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        return serviceConnection;
    }

    public void stopService(WPEServiceConnection serviceConnection) {
        m_context.unbindService(serviceConnection);
        m_services.remove(serviceConnection);
    }

    public View view() {
        return m_view;
    }

    private void loadUrlInternal() {
        BrowserGlue.loadURL(m_webViewRef, m_pendingLoad);
    }

    public View loadUrl(@NonNull Context context, @NonNull String url) {
        Log.d(LOGTAG, "Load URL " + url);
        if (m_webViewRef != 0) {
            // FIXME: If we already have a WebKitWebView reference, we can probably reuse it.
            // However we always need to recreate the gfx View and get a new texture.
            m_view.release();
            m_view = new View(context);
            BrowserGlue.destroyWebView(m_webViewRef);
            m_webViewRef = 0;
        }
        Log.d(LOGTAG, "Ensuring WebView and Surface texture. Need to queue load of url " + url);
        m_pendingLoad = url;
        ensureWebViewAndSurface();
        return m_view;
    }
}
