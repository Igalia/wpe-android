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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.IWPEService;
import org.wpewebkit.wpe.IWPEServiceHost;

public abstract class WPEService extends Service {
    private static final String LOGTAG = "WPEService";

    // The app's process that this process serves, for the call from native code below. Each process runs one service.
    // Package-private: set from the binder inner class (avoids a synthetic accessor).
    static volatile @Nullable IWPEServiceHost currentServiceHost = null;

    private final IWPEService.Stub binder = new IWPEService.Stub() {
        private IWPEServiceHost serviceHost = null;

        @Override
        @SuppressWarnings("deprecation")
        public int connect(@NonNull Bundle args, @NonNull IWPEServiceHost serviceHost) {
            Log.d(LOGTAG, "IWPEService.Stub::connect()");
            this.serviceHost = serviceHost;
            currentServiceHost = serviceHost;

            long pid = args.getLong("pid");
            final ParcelFileDescriptor parcelFd;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                parcelFd = args.getParcelable("fd", ParcelFileDescriptor.class);
            } else {
                parcelFd = args.getParcelable("fd");
            }

            new Thread(() -> {
                WPEService.this.initializeServiceMain(pid, parcelFd);

                // Clean exit
                try {
                    serviceHost.notifyCleanExit();
                } catch (RemoteException e) {
                    Log.e(LOGTAG, "Failed to call clean exit callback", e);
                }

                System.exit(0);
            }).start();

            return -1;
        }
    };

    // Called from native code (LocalNetwork.cpp) when Android blocks a connection to the local network, before the
    // connection is ended.
    @Keep
    private static void onLocalNetworkBlocked(@NonNull String host) {
        IWPEServiceHost serviceHost = currentServiceHost;
        if (serviceHost == null)
            return;

        try {
            serviceHost.notifyLocalNetworkBlocked(host);
        } catch (RemoteException e) {
            Log.e(LOGTAG, "Failed to tell that the local network is blocked", e);
        }
    }

    protected static native void setupNativeEnvironment(@NonNull String[] envStringsArray);
    protected static native void initializeNativeMain(long pid, int processType, int fd);

    protected abstract void setupServiceEnvironment();
    protected abstract void initializeServiceMain(long pid, @NonNull ParcelFileDescriptor parcelFd);

    @Override
    public void onCreate() {
        Log.d(LOGTAG, "onCreate()");
        super.onCreate();
        setupServiceEnvironment();
    }

    @Override
    public @NonNull IBinder onBind(Intent intent) {
        Log.d(LOGTAG, "onBind()");
        stopSelf();
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(LOGTAG, "onDestroy()");
        System.exit(0);
    }
}
