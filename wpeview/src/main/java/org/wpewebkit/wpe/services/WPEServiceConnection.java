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
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.IWPEService;
import org.wpewebkit.wpe.IWPEServiceHost;
import org.wpewebkit.wpe.LocalNetworkAccess;
import org.wpewebkit.wpe.WPEProcessType;

import java.io.IOException;

public final class WPEServiceConnection implements ServiceConnection {
    private static final String LOGTAG = "WPEServiceConnection";

    private final long pid;
    private final WPEProcessType processType;
    private ParcelFileDescriptor parcelFd;
    // Whether the service connected, so that its process is watched, and whether that process ended. The binder is
    // kept until then, as Android drops the death link of a binder nobody holds.
    private boolean connected;
    private boolean processGone;
    private IBinder serviceBinder;

    protected final WPEServiceConnectionListener listener;
    protected final Handler handler = new Handler(Looper.myLooper());

    public WPEServiceConnection(long pid, @NonNull WPEProcessType processType, @NonNull ParcelFileDescriptor parcelFd,
                                @NonNull WPEServiceConnectionListener listener) {
        this.pid = pid;
        this.processType = processType;
        this.parcelFd = parcelFd;
        this.listener = listener;
    }

    public long getPid() { return pid; }
    public @NonNull WPEProcessType getProcessType() { return processType; }

    /**
     * Whether the process of the service may still be running: the service connected, and its process has not ended
     * yet. Android starts the service again in a new process only once that one has ended.
     */
    public boolean mayHaveProcess() { return connected && !processGone; }

    /** Whether the service connected, from when the end of its process is followed. */
    public boolean isConnected() { return connected; }

    @Override
    public void onServiceConnected(@NonNull ComponentName name, IBinder service) {
        if (connected) {
            Log.w(LOGTAG, "onServiceConnected() for " + name + " called more than once");
            return;
        }

        connected = true;
        try {
            // Called on a binder thread.
            service.linkToDeath(() -> handler.post(this::onProcessGone), 0);
            serviceBinder = service;
        } catch (RemoteException e) {
            // The process is already gone.
            processGone = true;
        }

        if (parcelFd == null) {
            Log.d(LOGTAG, "onServiceConnected() for " + name + " (pid: " + pid + ") after dispose()");
            listener.onDisposedServiceConnected(this);
            return;
        }

        Log.d(LOGTAG, "onServiceConnected() for " + name + " (pid: " + pid + ", fd: " + parcelFd.getFd() + ")");
        IWPEService wpeService = IWPEService.Stub.asInterface(service);
        ParcelFileDescriptor fd = parcelFd;
        Bundle bundle = new Bundle();
        bundle.putLong("pid", pid);
        bundle.putParcelable("fd", fd);
        parcelFd = null;

        IWPEServiceHost serviceHost = new IWPEServiceHost.Stub() {
            @Override
            public void notifyCleanExit() {
                if (handler.getLooper() == Looper.myLooper())
                    listener.onCleanExit(WPEServiceConnection.this);
                else
                    handler.post(() -> listener.onCleanExit(WPEServiceConnection.this));
            }

            @Override
            public void notifyLocalNetworkBlocked(String host) {
                LocalNetworkAccess.getInstance().onBlocked(host);
            }
        };

        try {
            wpeService.connect(bundle, serviceHost);
        } catch (android.os.RemoteException e) {
            Log.e(LOGTAG, "Failed to connect to service", e);
        } finally {
            // The service has its own copy of the socket now. Keeping this one open would keep the connection open
            // after the service's process ends, as when Android ends it, and WebKit would not know that it is gone.
            closeSocket(fd);
        }
    }

    // Package-private: called from the DeathRecipient lambda (avoids a synthetic accessor).
    void onProcessGone() {
        if (processGone)
            return;
        processGone = true;
        serviceBinder = null;
        Log.d(LOGTAG, "The process of the service ended (pid: " + pid + ")");
        listener.onProcessGone(this);
    }

    /**
     * Lets go of the socket of a service that is no longer needed before it connected. If it connects later, the
     * listener is told so with onDisposedServiceConnected(), and the service gets no socket.
     */
    public void dispose() {
        if (parcelFd != null) {
            closeSocket(parcelFd);
            parcelFd = null;
        }
    }

    private static void closeSocket(@NonNull ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException e) {
            Log.w(LOGTAG, "Cannot close the socket of the service", e);
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
