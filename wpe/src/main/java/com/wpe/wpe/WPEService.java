package com.wpe.wpe;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;
import android.view.Surface;

import com.wpe.wpe.external.SurfaceWrapper;

public class WPEService extends Service {

    protected Thread m_thread;
    private ParcelFileDescriptor[] m_serviceFds = null;
    protected Surface m_surface = null;

    public final IWPEService.Stub m_binder = new IWPEService.Stub() {
        @Override
        public int connect(Bundle args)
        {
            Log.i("WPEService", "IWPEService.Stub connect()");

            Parcelable[] fdParcelables = args.getParcelableArray("fds");
            ParcelFileDescriptor[] fds = new ParcelFileDescriptor[fdParcelables.length];
            System.arraycopy(fdParcelables, 0, fds, 0, fdParcelables.length);

            provideServiceFDs(fds);
            return -1;
        }

        @Override
        public void provideSurface(SurfaceWrapper surfaceWrapper)
        {
            Log.i("WPEService", "IWPEService.Stub.provideSurface(), surface " + surfaceWrapper.getSurface());
            provideServiceSurface(surfaceWrapper.getSurface());
        }
    };

    @Override public void onCreate()
    {
        super.onCreate();
        Log.i("WPEService", "onCreate()");

        m_thread = new Thread(new Runnable() {
            @Override
            public void run()
            {
                Log.i("WPEService", "m_thread.run()");

                ParcelFileDescriptor[] fds = null;
                try {
                    synchronized (m_thread) {
                        while (m_serviceFds == null) {
                            m_thread.wait();
                        }
                    }

                    fds = m_serviceFds;
                    m_serviceFds = null;
                } catch (InterruptedException e) {
                    Log.e("WPEService", "thread startup failed", e);
                }

                initializeService(fds);
            }
        }, "WPEServiceThread");
        m_thread.start();
    }

    @Override public IBinder onBind(Intent intent)
    {
        Log.i("WPEService", "onBind()");
        return m_binder;
    }

    @Override public void onDestroy()
    {
        super.onDestroy();
        Log.i("WPEService", "onDestroy()");
    }

    private void provideServiceFDs(ParcelFileDescriptor[] fds)
    {
        synchronized (m_thread) {
            m_serviceFds = fds;
            m_thread.notifyAll();
        }
    }

    private void provideServiceSurface(Surface surface)
    {
        synchronized (m_thread) {
            m_surface = surface;
            m_thread.notifyAll();
        }
    }

    protected void initializeService(ParcelFileDescriptor[] fds)
    {
    }
}
