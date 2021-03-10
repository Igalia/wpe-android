package com.wpe.wpe;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.wpe.wpe.gfx.View;
import com.wpe.wpe.session.Session;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Top level Singleton object. Somehow equivalent to WebKit's UIProcess, manages the creation
 * and destruction of Session instances and funnels WPEView API calls to the appropriate
 * Session instance.
 */
@UiThread
class Browser {
    private static final String LOGTAG = "WPE Browser";
    private static Browser m_instance = null;

    private IdentityHashMap<WPEView, Session> m_sessions = null;
    private IdentityHashMap<WPEView, String> m_pendingLoads = null;

    private Browser() {}

    public static Browser getInstance() {
        if (m_instance == null) {
            m_instance = new Browser();
        }
        return m_instance;
    }

    public View createSession(@NonNull WPEView wpeView, @NonNull Context context) {
        Log.d(LOGTAG, "Create new Session instance for view");
        if (m_sessions == null) {
            m_sessions = new IdentityHashMap<>();
        }
        assert(!m_sessions.containsKey(wpeView));
        View view = new View(context);
        m_sessions.put(wpeView, new Session(context, String.valueOf(m_sessions.size()), view));
        loadPendingUrls(wpeView);
        return view;
    }

    public void destroySession(@NonNull WPEView wpeView) {
        Log.d(LOGTAG, "Unregister Session for view");
        assert(m_sessions.containsKey(wpeView));
        m_sessions.remove(wpeView);
    }

    private void queuePendingLoad(@NonNull WPEView wpeView, @NonNull String url) {
        Log.v(LOGTAG, "No available session. Queueing " + url + " for load");
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
        if (m_sessions == null || !m_sessions.containsKey(view)) {
            queuePendingLoad(view, url);
            return;
        }
        m_sessions.get(view).loadUrl(url);
    }
}
