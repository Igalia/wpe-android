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
public final class Browser {
    private static final String LOGTAG = "WPE Browser";
    private static Browser m_instance = null;
    private final BrowserGlue m_glue;
    private final UIProcessThread m_uiProcessThread;

    private IdentityHashMap<WPEView, Page> m_pages = null;
    private IdentityHashMap<WPEView, PendingLoad> m_pendingLoads = null;

    // FIXME This needs to be reworked to use something different to flag which
    //       exact processes/services are up and which are available for launch
    public int m_webProcessCount = 0;
    public int m_networkProcessCount = 0;

    private WPEView m_activeView = null;

    private final class PendingLoad {
        public final String m_url;
        public final Context m_context;

        PendingLoad(Context context, String url) {
            m_url = url;
            m_context = context;
        }
    }

    private final class UIProcessThread {
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
        Log.d(LOGTAG, "Create new Page instance for view " + wpeView);
        if (m_pages == null) {
            m_pages = new IdentityHashMap<>();
        }
        assert(!m_pages.containsKey(wpeView));
        View view = new View(context);
        m_pages.put(wpeView, new Page(context, String.valueOf(m_pages.size()), view, m_glue));
        m_activeView = wpeView;
        loadPendingUrls(wpeView);
        return view;
    }

    public void destroyPage(@NonNull WPEView wpeView) {
        Log.d(LOGTAG, "Unregister Page for view");
        assert(m_pages.containsKey(wpeView));
        m_pages.remove(wpeView);
        if (m_activeView == wpeView) {
            m_activeView = null;
        }
    }

    public void onVisibilityChanged(@NonNull WPEView wpeView, int visibility) {
        Log.v(LOGTAG, "Visibility changed for " + wpeView + " to " + visibility);
        assert(m_pages.containsKey(wpeView));
        if (visibility == android.view.View.VISIBLE) {
            m_activeView = wpeView;
        }
    }

    public Page getActivePage() {
         return m_pages.get(m_activeView);
    }

    private void queuePendingLoad(@NonNull WPEView wpeView, @NonNull PendingLoad pendingLoad) {
        Log.v(LOGTAG, "No available page. Queueing " + pendingLoad.m_url + " for load");
        if (m_pendingLoads == null) {
            m_pendingLoads = new IdentityHashMap<>();
        }
        // We only care about the last url.
        m_pendingLoads.put(wpeView, pendingLoad);
    }

    private void loadPendingUrls(@NonNull WPEView wpeView) {
        if (m_pendingLoads == null) {
            return;
        }
        PendingLoad load = m_pendingLoads.remove(wpeView);
        if (load != null) {
            loadUrl(wpeView, load.m_context, load.m_url);
        }
    }

    public View loadUrl(@NonNull WPEView wpeView, @NonNull Context context, @NonNull String url) {
        Log.d(LOGTAG, "Load URL " + url);
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            queuePendingLoad(wpeView, new PendingLoad(context, url));
            return null;
        }
        return m_pages.get(wpeView).loadUrl(context, url);
    }
}
