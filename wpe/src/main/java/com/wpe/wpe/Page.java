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

@UiThread
public class Page {
    private final String LOGTAG;
    private final BrowserGlue m_glue;
    private final View m_view;
    private final Context m_context;
    private final ArrayList<WPEServiceConnection> m_services;
    private final PageThread m_thread;

    private long m_webViewRef = 0;

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
            page.createWebView();
        }
    }

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
                        // TODO notify calling thread;
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

        m_thread = new PageThread();
        m_thread.run(m_view, new PageThreadMessageHandler(this));

    }

    public void close() {
        Log.v(LOGTAG, "Page destruction");
        for (WPEServiceConnection serviceConnection : m_services) {
            m_context.unbindService(serviceConnection);
        }
        m_services.clear();
        BrowserGlue.destroyWebView(m_webViewRef);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    private void createWebView() {
        assert(m_view.width() > 0);
        assert(m_view.height() > 0);
        BrowserGlue.newWebView(this, m_view.width(), m_view.height());
    }

    @Keep
    public void onReady(long webViewRef) {
       Log.v(LOGTAG, "Page ready");
       m_webViewRef = webViewRef;
    }

    public void launchService(int processType, Parcelable[] fds, Class cls) {
        Intent intent = new Intent(m_context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, this, fds);
        m_services.add(serviceConnection);
        m_context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }

    public void dropService(WPEServiceConnection serviceConnection) {
        m_context.unbindService(serviceConnection);
        m_services.remove(serviceConnection);
    }

    public View view() {
        return m_view;
    }

    public void loadUrl(@NonNull String url) {
        Log.d(LOGTAG, "Load URL " + url);
        if (m_webViewRef == 0) {
            // TODO queue load.
            return;
        }
        BrowserGlue.loadURL(m_webViewRef, url);
    }
}
