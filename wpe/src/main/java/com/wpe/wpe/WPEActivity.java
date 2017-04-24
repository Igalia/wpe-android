package com.wpe.wpe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import com.wpe.wpe.UIProcess.Glue;
import com.wpe.wpe.UIProcess.WPEServiceConnection;

import java.util.ArrayList;

public class WPEActivity extends Activity {

    private Glue m_glue;
    public WPEView m_view;
    private Thread m_thread;
    ArrayList<WPEServiceConnection> m_services;

    @Override protected void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        Log.i("WPE", "Hello there");

        m_glue = new Glue(this);
        m_services = new ArrayList<WPEServiceConnection>();

        Log.i("WPE", "cache dir " + getBaseContext().getCacheDir());

        m_view = new WPEView(getApplication());
        setContentView(m_view);

        final WPEActivity activity = this;
        m_thread = new Thread(new Runnable() {
            @Override
            public void run()
            {
                Log.i("WPEActivity", "m_thread.run()");
                m_view.ensureSurfaceTexture();

                Glue.init(m_glue, m_view.width(), m_view.height());
            }
        }, "WPEActivityThread");
        m_thread.start();
    }

    @Override protected void onPause()
    {
        super.onPause();
        m_view.onPause();
    }

    @Override protected void onResume()
    {
        super.onResume();
        m_view.onResume();
    }

    @Override protected void onDestroy()
    {
        Context context = getBaseContext();
        for (WPEServiceConnection serviceConnection : m_services) {
            context.unbindService(serviceConnection);
        }
        m_services.clear();

        super.onDestroy();
    }

    public void launchService(int processType, Parcelable[] fds, Class cls)
    {
        Context context = getBaseContext();
        Intent intent = new Intent(context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, this, fds);
        m_services.add(serviceConnection);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }

    public void dropService(WPEServiceConnection serviceConnection)
    {
        getBaseContext().unbindService(serviceConnection);
        m_services.remove(serviceConnection);
    }
}
