/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wpe.wpe.ProcessType;

import java.io.File;

public class NetworkProcessService extends WPEService {
    private static final String LOGTAG = "WPENetworkProcess";

    // Bump this version number if you make any changes to the gio
    // modules or else they won't be applied.
    private static final String assetsVersion = "network_process_assets_v1";

    @Override
    protected void loadNativeLibraries() {
        // To debug the sub-process with Android Studio (Java and native code), you must:
        // 1- Uncomment the following instruction to wait for the debugger before loading native code.
        // 2- Force the dual debugger (Java + Native) in Run/Debug configuration (the automatic detection won't work).
        // 3- Launch the application (:tools:minibrowser for example).
        // 4- Click on "Attach Debugger to Android Process" and select this service process from the list.

        // android.os.Debug.waitForDebugger();

        System.loadLibrary("WPENetworkProcessGlue");
    }

    @Override
    protected void setupServiceEnvironment() {
        Context context = getApplicationContext();
        if (ServiceUtils.needAssets(context, assetsVersion)) {
            ServiceUtils.copyFileOrDir(context, getAssets(), "gio", true);
            ServiceUtils.saveAssetsVersion(context, assetsVersion);
        }

        String[] envStringsArray = {"XDG_RUNTIME_DIR", context.getCacheDir().getAbsolutePath(), "GIO_EXTRA_MODULES",
                                    new File(context.getFilesDir(), "gio").getAbsolutePath()};

        setupEnvironment(envStringsArray);
    }

    @Override
    protected void initializeServiceMain(long pid, @NonNull ParcelFileDescriptor parcelFd) {
        Log.v(LOGTAG,
              "initializeServiceMain() pid: " + pid + ", fd: " + parcelFd + ", native value: " + parcelFd.getFd());
        initializeMain(pid, ProcessType.NetworkProcess.getValue(), parcelFd.detachFd());
    }
}
