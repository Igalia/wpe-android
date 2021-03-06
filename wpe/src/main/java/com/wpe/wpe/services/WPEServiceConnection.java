package com.wpe.wpe.services;

import android.content.ComponentName;
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
    private Page page;
    private ParcelFileDescriptor parcelFd;

    public WPEServiceConnection(@NonNull ProcessType processType, @NonNull Page page,
                                @NonNull ParcelFileDescriptor parcelFd) {
        this.processType = processType;
        this.page = page;
        this.parcelFd = parcelFd;
    }

    public ProcessType getProcessType() { return processType; }

    public Page getActivePage() { return page; }

    /**
     * FIXME: Since we do not support PSON, the auxiliary processes are shared
     * among Page instances. We need to set the Page instance this
     * auxiliary process is working for.
     */
    public void setActivePage(@NonNull Page page) { this.page = page; }

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
        page.stopService(this);
    }
}
