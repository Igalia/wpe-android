package com.wpe.wpe.UIProcess;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;

import com.wpe.wpe.IWPEService;
import com.wpe.wpe.WPEActivity;
import com.wpe.wpe.WebProcess.Service;
import com.wpe.wpe.external.SurfaceWrapper;

public class Glue {

    static {
        System.loadLibrary("WPE-backend");
        System.loadLibrary("Glue");
    }

    public static native void init(Glue glueObj);
    public static native void deinit();

    private final int PROCESS_TYPE_WEBPROCESS = 0;
    private final int PROCESS_TYPE_NETWORKPROCESS = 1;
    private final int PROCESS_TYPE_DATABASEPROCESS = 2;

    private class WPEServiceConnection implements ServiceConnection {

        private final int m_processType;
        private final WPEActivity m_activity;
        private Parcelable[] m_fds;

        WPEServiceConnection(int processType, WPEActivity activity, Parcelable[] fds)
        {
            m_processType = processType;
            m_activity = activity;
            m_fds = fds;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            Log.i("WPEServiceConnection", "onServiceConnected() name " + name.toString());
            m_service = IWPEService.Stub.asInterface(service);

            Bundle bundle = new Bundle();
            bundle.putParcelableArray("fds", m_fds);
            m_fds = null;

            try {
                m_service.connect(bundle);
                if (m_processType == PROCESS_TYPE_WEBPROCESS) {
                    m_service.provideSurface(new SurfaceWrapper(m_activity.m_view.createSurface()));
                }
            } catch (android.os.RemoteException e) {
                Log.e("WPEActivity", "Failed to connect to service", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name)
        {
            Log.i("WPEServiceConnection", "onServiceDisconnected()");
        }
    }

    private final WPEActivity m_activity;
    private IWPEService m_service;

    Glue(WPEActivity activity)
    {
        m_activity = activity;
    }

    public void launchProcess(int processType, int[] fds)
    {
        Log.i("Glue", "launchProcess()");
        Log.i("Glue", "got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("Glue", "  [" + i + "] " + fds[i]);
        }

        Parcelable[] parcelFds = new Parcelable[ /* fds.length */ 1];
        for (int i = 0; i < parcelFds.length; ++i) {
            parcelFds[i] = ParcelFileDescriptor.adoptFd(fds[i]);
        }

        switch (processType) {
            case PROCESS_TYPE_WEBPROCESS:
                Log.i("Glue", "should launch WebProcess");
                launchServiceInternal(PROCESS_TYPE_WEBPROCESS, parcelFds, Service.class);
                break;
            case PROCESS_TYPE_NETWORKPROCESS:
                Log.i("Glue", "should launch NetworkProcess");
                launchServiceInternal(PROCESS_TYPE_NETWORKPROCESS, parcelFds, com.wpe.wpe.NetworkProcess.Service.class);
                break;
            case PROCESS_TYPE_DATABASEPROCESS:
                Log.i("Glue", "should launch DatabaseProcess");
                break;
            default:
                Log.i("Glue", "invalid process type");
                break;
        }
    }

    private void launchServiceInternal(int processType, Parcelable[] fds, Class cls)
    {
        Context context = m_activity.getBaseContext();
        Intent intent = new Intent(context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, m_activity, fds);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }
}
