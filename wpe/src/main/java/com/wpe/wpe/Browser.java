package com.wpe.wpe;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.wpe.wpe.gfx.View;
import com.wpe.wpeview.WPEView;

import java.util.IdentityHashMap;

/**
 * Top level Singleton object. Somehow equivalent to WebKit's UIProcess, manages the creation
 * and destruction of Page instances and funnels WPEView API calls to the appropriate
 * Page instance.
 */
@UiThread
public class Browser {
    private static final String LOGTAG = "WPE Browser";
    private static Browser m_instance = null;
    private final BrowserGlue m_glue;
    private final UIProcessThread m_uiProcessThread;

    private IdentityHashMap<WPEView, Page> m_pages = null;
    private IdentityHashMap<WPEView, String> m_pendingLoads = null;

    private class UIProcessThread {
        private Thread m_thread;
        private BrowserGlue m_glueRef;

        UIProcessThread() {
            final UIProcessThread self = this;

            m_thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(LOGTAG, "In UIProcess thread");
                    while (true) {
                        try {
                            while (self.m_glueRef == null) {
                                self.wait();
                            }
                        } catch (InterruptedException e) {
                            Log.v(LOGTAG, "Interruption in UIProcess thread");
                        }
                        // Create a WebKitWebContext and run the main loop.
                        BrowserGlue.init(m_glueRef);
                    }
                }
            });

            m_thread.start();
        }

        public void run(@NonNull BrowserGlue glue) {
            final UIProcessThread self = this;
            synchronized (self) {
                m_glueRef = glue;
                self.notifyAll();
            }
        }
    }

    private Browser() {
        Log.v(LOGTAG, "Browser creation");
        m_glue = new BrowserGlue(this);
        m_uiProcessThread = new UIProcessThread();
        m_uiProcessThread.run(m_glue);
    }

    public static Browser getInstance() {
        if (m_instance == null) {
            m_instance = new Browser();
        }
        return m_instance;
    }

    public View createPage(@NonNull WPEView wpeView, @NonNull Context context) {
        Log.d(LOGTAG, "Create new Page instance for view");
        if (m_pages == null) {
            m_pages = new IdentityHashMap<>();
        }
        assert(!m_pages.containsKey(wpeView));
        View view = new View(context);
        m_pages.put(wpeView, new Page(context, String.valueOf(m_pages.size()), view, m_glue));
        loadPendingUrls(wpeView);
        return view;
    }

    public void destroyPage(@NonNull WPEView wpeView) {
        Log.d(LOGTAG, "Unregister Page for view");
        assert(m_pages.containsKey(wpeView));
        m_pages.remove(wpeView);
    }

    private void queuePendingLoad(@NonNull WPEView wpeView, @NonNull String url) {
        Log.v(LOGTAG, "No available page. Queueing " + url + " for load");
        if (m_pendingLoads == null) {
            m_pendingLoads = new IdentityHashMap<>();
        }
        // We only care about the last url.
        m_pendingLoads.put(wpeView, url);
    }

    private void loadPendingUrls(@NonNull WPEView wpeView) {
        if (m_pendingLoads == null) {
            return;
        }
        String url = m_pendingLoads.remove(wpeView);
        if (url != null) {
            loadUrl(wpeView, url);
        }
    }

    public void loadUrl(@NonNull WPEView view, @NonNull String url) {
        Log.d(LOGTAG, "Load URL " + url);
        if (m_pages == null || !m_pages.containsKey(view)) {
            queuePendingLoad(view, url);
            return;
        }
        m_pages.get(view).loadUrl(url);
    }
}
