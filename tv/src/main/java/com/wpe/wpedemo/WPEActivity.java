package com.wpe.wpedemo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class WPEActivity extends Activity {

    private class WPEServiceConnection implements ServiceConnection {

        private final Context m_context;

        WPEServiceConnection(Context context)
        {
            m_context = context;
        }

        void bind(Intent intent)
        {
            Log.i("WPEServiceConnection", "bind()");
            m_context.bindService(intent, this, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        }

        void unbind()
        {
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            Log.i("WPEServiceConnection", "onServiceConnected()");
            m_service = IWPEService.Stub.asInterface(service);

            connectToService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name)
        {
            Log.i("WPEServiceConnection", "onServiceDisconnected()");
        }
    }

    private WPEServiceConnection m_serviceConnection;
    private IWPEService m_service;
    private Thread m_thread;
    private WPEUIProcessGlue m_glueObj;

    @Override protected void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        Log.i("WPE", "Hello there");

        Context context = getBaseContext();
        Intent intent = new Intent(context, WPEService.class);

        m_serviceConnection = new WPEServiceConnection(context);
        m_serviceConnection.bind(intent);

        m_thread = new Thread(new Runnable() {
            @Override
            public void run()
            {
                Log.i("WPEActivity", "m_thread.run()");
                m_glueObj = new WPEUIProcessGlue();
                WPEUIProcessGlue.init(m_glueObj);
            }
        }, "WPEThread");
        m_thread.start();
    }

    @Override protected void onPause()
    {
        super.onPause();
    }

    @Override protected void onResume()
    {
        super.onResume();
    }

    @Override protected void onDestroy()
    {
        if (m_serviceConnection != null)
            m_serviceConnection = null;

        WPEUIProcessGlue.deinit();

        super.onDestroy();
    }

    private void connectToService()
    {
        Bundle bundle = new Bundle();
        try {
            m_service.connect(bundle);
        } catch (android.os.RemoteException e) {
            Log.e("WPEActivity", "Failed to connect to service", e);
        }
    }
}
