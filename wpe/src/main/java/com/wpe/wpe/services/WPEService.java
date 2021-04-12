package com.wpe.wpe.services;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;
import android.view.Surface;

import com.wpe.wpe.IWPEService;
import com.wpe.wpe.external.SurfaceWrapper;

public class WPEService extends Service {
    private static final String LOGTAG = "WPEService";

    public final IWPEService.Stub m_binder = new IWPEService.Stub() {
        @Override
        public int connect(Bundle args) {
            Log.v(LOGTAG, "IWPEService.Stub connect()");

            Parcelable[] fdParcelables = args.getParcelableArray("fds");
            ParcelFileDescriptor[] fds = new ParcelFileDescriptor[fdParcelables.length];
            System.arraycopy(fdParcelables, 0, fds, 0, fdParcelables.length);

            provideServiceFDs(fds);
            return -1;
        }

        @Override
        public void provideSurface(SurfaceWrapper surfaceWrapper) {
            Log.v(LOGTAG, "IWPEService.Stub.provideSurface(), surface " + surfaceWrapper.getSurface());
            provideServiceSurface(surfaceWrapper.getSurface());
        }
    };

    protected class WPEServiceProcessThread {
        private static final String LOGTAG = "WPEServiceProcessThread";
        private Thread m_thread;
        private WPEService m_service;
        private ParcelFileDescriptor[] m_serviceFds = null;
        public Surface m_surface = null;

        WPEServiceProcessThread() {
            final WPEServiceProcessThread thisObj = this;
            m_thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.v(LOGTAG, "Running");

                    while (true) {
                        ParcelFileDescriptor[] fds = null;

                        synchronized (thisObj) {
                            try {
                                while (m_service == null) {
                                    thisObj.wait();
                                }
                                while (m_serviceFds == null) {
                                    thisObj.wait();
                                }
                            } catch (InterruptedException e) {
                                Log.i(LOGTAG, "Interruption in WPEServiceProcessThread");
                                break;
                            }

                            fds = m_serviceFds;
                            m_serviceFds = null;
                        }

                        m_service.initializeService(fds);
                    }
                }
            });
            m_thread.start();
        }
    }

    static protected WPEServiceProcessThread m_serviceProcessThread;

    @Override
    public void onCreate() {
        Log.i(LOGTAG, "onCreate()");
        super.onCreate();

        if (m_serviceProcessThread == null) {
            m_serviceProcessThread = new WPEServiceProcessThread();
        }

        synchronized (m_serviceProcessThread) {
            m_serviceProcessThread.m_service = this;
            m_serviceProcessThread.notifyAll();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(LOGTAG, "onBind()");
        return m_binder;
    }

    @Override
    public void onDestroy() {
        Log.i(LOGTAG, "onDestroy()");
        super.onDestroy();
    }

    private void provideServiceFDs(ParcelFileDescriptor[] fds) {
        synchronized (m_serviceProcessThread) {
            m_serviceProcessThread.m_serviceFds = fds;
            m_serviceProcessThread.notifyAll();
        }
    }

    private void provideServiceSurface(Surface surface) {
        provideSurfaceIfAlreadyInitialized(surface);
        synchronized (m_serviceProcessThread) {
            m_serviceProcessThread.m_surface = surface;
            m_serviceProcessThread.notifyAll();
        }
    }

    protected void initializeService(ParcelFileDescriptor[] fds) {}

    protected void provideSurfaceIfAlreadyInitialized(Surface surface) {}
}
