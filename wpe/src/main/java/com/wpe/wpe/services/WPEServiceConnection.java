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
