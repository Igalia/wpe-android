package com.wpe.wpe.services;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wpe.wpe.IWPEService;

public abstract class WPEService extends Service
{
    private static final String LOGTAG = "WPEService";

    protected abstract void setupServiceEnvironment();
    protected abstract void initializeServiceMain(@NonNull ParcelFileDescriptor parcelFd);

    private final IWPEService.Stub m_binder = new IWPEService.Stub()
    {
        @Override
        public int connect(@NonNull Bundle args)
        {
            Log.v(LOGTAG, "IWPEService.Stub connect()");
            final ParcelFileDescriptor parcelFd = args.getParcelable("fd");

            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    WPEService.this.initializeServiceMain(parcelFd);
                }
            }).start();

            return -1;
        }
    };

    @Override
    public void onCreate()
    {
        Log.i(LOGTAG, "onCreate()");
        super.onCreate();
        setupServiceEnvironment();
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        Log.i(LOGTAG, "onBind()");
        return m_binder;
    }

    @Override
    public void onDestroy()
    {
        Log.i(LOGTAG, "onDestroy()");
        super.onDestroy();
    }
}
