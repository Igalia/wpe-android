package com.wpe.wpe.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wpe.wpe.IWPEService;
import com.wpe.wpe.Page;
import com.wpe.wpe.ProcessType;

public final class WPEServiceConnection implements ServiceConnection {
    private static final String LOGTAG = "WPEServiceConnection";

    private final ProcessType processType;
    private ParcelFileDescriptor parcelFd;

    public WPEServiceConnection(@NonNull ProcessType processType, @NonNull ParcelFileDescriptor parcelFd) {
        this.processType = processType;
        this.parcelFd = parcelFd;
    }

    public ProcessType getProcessType() { return processType; }

    @Override
    public void onServiceConnected(@NonNull ComponentName name, IBinder service) {
        if (parcelFd == null) {
            Log.w(LOGTAG, "onServiceConnected() name: " + name + " called more than once");
            return;
        }

        Log.i(LOGTAG, "onServiceConnected() name: " + name + ", fd: " + parcelFd.getFd());
        IWPEService wpeService = IWPEService.Stub.asInterface(service);

        Bundle bundle = new Bundle();
        bundle.putParcelable("fd", parcelFd);
        parcelFd = null;

        try {
            wpeService.connect(bundle);
        } catch (android.os.RemoteException e) {
            Log.e(LOGTAG, "Failed to connect to service", e);
        }
    }

    @Override
    public void onServiceDisconnected(@NonNull ComponentName name) {
        Log.i(LOGTAG, "onServiceDisconnected() name: " + name);
        // FIXME: We need to notify WebKit about the Service being killed.
        //        What should WebKit do in this case?
    }
}
