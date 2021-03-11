package com.wpe.wpe.page;

import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.wpe.wpe.gfx.View;
import com.wpe.wpe.services.WPEServiceConnection;

import java.util.ArrayList;

@UiThread
public class Page {
    private final String LOGTAG;
    private final PageGlue m_glue;
    private final PageThread m_pageThread;
    private final View m_view;
    private final Context m_context;
    private final ArrayList<WPEServiceConnection> m_services;

    private class PageThread {
        private Thread m_thread;
        private PageGlue m_glueRef;
        private View m_viewRef;

        PageThread() {
            final PageThread self = this;

            m_thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(LOGTAG, "In page thread");
                    while (true) {
                        synchronized (self) {
                            try {
                                while (m_glueRef == null) {
                                    self.wait();
                                }
                            } catch (InterruptedException e) {
                                Log.v(LOGTAG, "Interruption in page thread");
                                break;
                            }
                            Log.d(LOGTAG, "Got page glue " + m_glueRef + " and view " + m_viewRef);
                            m_viewRef.ensureSurfaceTexture();
                            PageGlue.init(m_glueRef, m_viewRef.width(), m_viewRef.height());
                        }
                    }
                }
            });

            m_thread.start();
        }

        public void run(@NonNull PageGlue glue, @NonNull View view) {
            final PageThread self = this;
            Log.i(LOGTAG, "Glue " + glue + " view " + view);
            synchronized(self) {
                m_glueRef = glue;
                m_viewRef = view;
                self.notifyAll();
            }
        }

        public void stop() {
            final PageThread self = this;
            synchronized (self) {
                m_glueRef = null;
                m_viewRef = null;
                PageGlue.deinit();
            }
        }
    }

    public Page(@NonNull Context context, @NonNull String pageId, @NonNull View view) {
        LOGTAG = "WPE page" + pageId;
        Log.v(LOGTAG, "page construction");
        m_context = context;
        m_glue = new PageGlue(this);
        m_view = view;
        m_services = new ArrayList<WPEServiceConnection>();

        m_pageThread = new PageThread();
        m_pageThread.run(m_glue, m_view);
    }

    public void close() {
        Log.v(LOGTAG, "page destruction");
        m_pageThread.stop();
        for (WPEServiceConnection serviceConnection : m_services) {
            m_context.unbindService(serviceConnection);
        }
        m_services.clear();
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    public void launchService(int processType, Parcelable[] fds, Class cls)
    {
        Intent intent = new Intent(m_context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, this, fds);
        m_services.add(serviceConnection);
        m_context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }

    public void dropService(WPEServiceConnection serviceConnection)
    {
        m_context.unbindService(serviceConnection);
        m_services.remove(serviceConnection);
    }

    public View view() {
        return m_view;
    }

    public void loadUrl(@NonNull String url) {
        Log.d(LOGTAG, "Load URL " + url);
        PageGlue.setPageURL(url);
    }
}
