/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpe.services;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.IWPEService;
import org.wpewebkit.wpe.IWPEServiceHost;
import org.wpewebkit.wpe.WKProcessType;

public final class WPEServiceConnection implements ServiceConnection {
    private static final String LOGTAG = "WPEServiceConnection";

    private final long pid;
    private final WKProcessType processType;
    private ParcelFileDescriptor parcelFd;

    protected final WPEServiceConnectionListener listener;
    protected final Handler handler = new Handler(Looper.myLooper());

    public WPEServiceConnection(long pid, @NonNull WKProcessType processType, @NonNull ParcelFileDescriptor parcelFd,
                                @NonNull WPEServiceConnectionListener listener) {
        this.pid = pid;
        this.processType = processType;
        this.parcelFd = parcelFd;
        this.listener = listener;
    }

    public long getPid() { return pid; }
    public @NonNull WKProcessType getProcessType() { return processType; }

    @Override
    public void onServiceConnected(@NonNull ComponentName name, IBinder service) {
        if (parcelFd == null) {
            Log.w(LOGTAG, "onServiceConnected() for " + name + " called more than once");
            return;
        }

        Log.d(LOGTAG, "onServiceConnected() for " + name + " (pid: " + pid + ", fd: " + parcelFd.getFd() + ")");
        IWPEService wpeService = IWPEService.Stub.asInterface(service);

        Bundle bundle = new Bundle();
        bundle.putLong("pid", pid);
        bundle.putParcelable("fd", parcelFd);
        parcelFd = null;

        IWPEServiceHost serviceHost = new IWPEServiceHost.Stub() {
            @Override
            public void notifyCleanExit() {
                if (handler.getLooper() == Looper.myLooper())
                    listener.onCleanExit(WPEServiceConnection.this);
                else
                    handler.post(() -> listener.onCleanExit(WPEServiceConnection.this));
            }
        };

        try {
            wpeService.connect(bundle, serviceHost);
        } catch (android.os.RemoteException e) {
            Log.e(LOGTAG, "Failed to connect to service", e);
        }
    }

    @Override
    public void onServiceDisconnected(@NonNull ComponentName name) {
        Log.d(LOGTAG, "onServiceDisconnected() for " + name);
        if (handler.getLooper() == Looper.myLooper())
            listener.onServiceDisconnected(this);
        else
            handler.post(() -> listener.onServiceDisconnected(this));
    }
}
