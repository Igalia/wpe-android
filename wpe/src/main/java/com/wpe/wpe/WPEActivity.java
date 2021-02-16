package com.wpe.wpe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
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

    private class WPEUIProcessThread {
        private Thread m_thread;

        Glue m_glueObj;
        WPEView m_viewObj;

        WPEUIProcessThread()
        {
            final WPEUIProcessThread thisObj = this;

            m_thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i("WPEUIProcessThread", "in thread");
                    while (true) {
                        synchronized (thisObj) {
                            try {
                                while (m_glueObj == null) {
                                    thisObj.wait();
                                }
                            } catch (InterruptedException e){
                                Log.e("WPEAcitivty", "interruption in WPEUIProcessThread");
                                break;
                            }
                        }

                        Log.i("WPEUIProcessThread", "got glue " + m_glueObj + ", view " + m_viewObj);
                        m_viewObj.ensureSurfaceTexture();

                        Glue.init(m_glueObj, m_viewObj.width(), m_viewObj.height());
                    }
                }
            });
            m_thread.start();
        }

        public void runUIProcess(Glue glue, WPEView view)
        {
            final WPEUIProcessThread thisObj = this;
            Log.i("WPEActivity", "WPEUIProcessThread, glue " + glue + " view " + view);

            synchronized (thisObj) {
                m_glueObj = glue;
                m_viewObj = view;
                thisObj.notifyAll();
            }
        }

        public void stopUIProcess()
        {
            final WPEUIProcessThread thisObj = this;

            synchronized (thisObj) {
                m_glueObj = null;
                m_viewObj = null;

                Glue.deinit();
            }
        }
    }

    static private WPEUIProcessThread m_uiProcessThread;

    @Override protected void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        Log.i("WPE", "Hello there");

        m_glue = new Glue(this);
        m_services = new ArrayList<WPEServiceConnection>();

        Log.i("WPE", "cache dir " + getBaseContext().getCacheDir());

        m_view = new WPEView(getApplication());
        setContentView(m_view);

        String pageURL = "http://www.igalia.com";
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            pageURL = info.metaData.getString("PageURL");
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        m_glue.setPageURL(pageURL);

        if (m_uiProcessThread == null) {
            Log.i("WPE", "creating a new WPEUIProcessThread");
            m_uiProcessThread = new WPEUIProcessThread();
            Log.i("WPE", "created a new WPEUIProcessThread " + m_uiProcessThread);
        }
        m_uiProcessThread.runUIProcess(m_glue, m_view);
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
        m_uiProcessThread.stopUIProcess();

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
