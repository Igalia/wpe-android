package com.wpe.wpe.services;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wpe.wpe.IWPEService;

public abstract class WPEService extends Service {
    private static final String LOGTAG = "WPEService";

    private final IWPEService.Stub binder = new IWPEService.Stub() {
        @Override
        public int connect(@NonNull Bundle args) {
            Log.v(LOGTAG, "IWPEService.Stub connect()");
            final ParcelFileDescriptor parcelFd = args.getParcelable("fd");

            new Thread(new Runnable() {
                @Override
                public void run() {
                    WPEService.this.initializeServiceMain(parcelFd);
                }
            }).start();

            return -1;
        }
    };

    protected static native void setupEnvironment(String[] envStringsArray);
    protected static native void initializeMain(int processType, int fd);

    protected abstract void loadNativeLibraries();
    protected abstract void setupServiceEnvironment();
    protected abstract void initializeServiceMain(@NonNull ParcelFileDescriptor parcelFd);

    @Override
    public void onCreate() {
        Log.i(LOGTAG, "onCreate()");
        super.onCreate();
        loadNativeLibraries();
        setupServiceEnvironment();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(LOGTAG, "onBind()");
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.i(LOGTAG, "onDestroy()");
        super.onDestroy();
    }
}
