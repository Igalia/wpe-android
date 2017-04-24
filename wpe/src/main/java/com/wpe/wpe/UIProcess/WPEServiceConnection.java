package com.wpe.wpe.UIProcess;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import com.wpe.wpe.IWPEService;
import com.wpe.wpe.WPEActivity;
import com.wpe.wpe.external.SurfaceWrapper;

public class WPEServiceConnection implements ServiceConnection {

    private final int m_processType;
    private final WPEActivity m_activity;
    private Parcelable[] m_fds;
    private IWPEService m_service;

    static public final int PROCESS_TYPE_WEBPROCESS = 0;
    static public final int PROCESS_TYPE_NETWORKPROCESS = 1;
    static public final int PROCESS_TYPE_DATABASEPROCESS = 2;

    public WPEServiceConnection(int processType, WPEActivity activity, Parcelable[] fds)
    {
        m_processType = processType;
        m_activity = activity;
        m_fds = fds;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service)
    {
        Log.i("WPEServiceConnection", "onServiceConnected() name " + name.toString() + " m_fds " + m_fds);
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
        m_activity.dropService(this);
    }
}