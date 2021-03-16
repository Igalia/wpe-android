package com.wpe.wpe;

import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.wpe.wpe.gfx.View;
import com.wpe.wpe.services.WPEServiceConnection;

import java.util.ArrayList;

@UiThread
public class Page {
    private final String LOGTAG;
    private final BrowserGlue m_glue;
    private final View m_view;
    private final Context m_context;
    private final ArrayList<WPEServiceConnection> m_services;

    private long m_webViewRef = 0;

    public Page(@NonNull Context context, @NonNull String pageId, @NonNull View view, @NonNull BrowserGlue browserGlue) {
        LOGTAG = "WPE page" + pageId;
        Log.v(LOGTAG, "Page construction " + this);
        m_context = context;
        m_view = view;
        m_glue = browserGlue;
        BrowserGlue.newWebView(this, 100, 100);
        m_services = new ArrayList<>();
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

    @Keep
    public void onReady(long webViewRef) {
       Log.v(LOGTAG, "onReady");
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
