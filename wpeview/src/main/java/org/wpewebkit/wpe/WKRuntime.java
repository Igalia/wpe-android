/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
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

package org.wpewebkit.wpe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import org.wpewebkit.wpe.services.ServiceUtils;
import org.wpewebkit.wpe.services.WPEServiceConnection;
import org.wpewebkit.wpe.services.WPEServiceConnectionListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@UiThread
public final class WKRuntime {
    private static final String LOGTAG = "WKRuntime";

    // Bump this version number if you make any changes to the font config
    // or the gstreamer plugins or else they won't be applied.
    private static final String assetsVersion = "ui_process_assets_2.46.0";

    static { System.loadLibrary("WPEAndroidBrowser"); }

    protected static native void startNativeLooper();
    private static native void setupNativeEnvironment(@NonNull String[] envStringsArray);
    private native void nativeInit();
    private native void nativeShut();

    private static int inspectorPort = 0;
    private static boolean useHttpInspector = true;

    private static final WKRuntime singleton = new WKRuntime();

    public static @NonNull WKRuntime getInstance() { return singleton; }

    private WKRuntime() { Log.v(LOGTAG, "WPE WKRuntime creation"); }

    private Context applicationContext = null;

    public @Nullable Context getApplicationContext() { return applicationContext; }

    private LooperHelperThread looperHelperThread = null;

    public static void enableRemoteInspector(int inspectorPort, boolean useHttpInspector) {
        WKRuntime.inspectorPort = inspectorPort;
        WKRuntime.useHttpInspector = useHttpInspector;
    }

    public void initialize(@NonNull Context context) {
        if (applicationContext == null) {
            applicationContext = context.getApplicationContext();

            if (ServiceUtils.needAssets(applicationContext, assetsVersion)) {
                ServiceUtils.copyFileOrDir(applicationContext, applicationContext.getAssets(), "injected-bundles",
                                           true);
                ServiceUtils.copyFileOrDir(applicationContext, applicationContext.getAssets(), "mime", false);
                ServiceUtils.saveAssetsVersion(applicationContext, assetsVersion);
            }

            ApplicationInfo appInfo = applicationContext.getApplicationInfo();
            List<String> envStrings = new ArrayList<>(46);
            envStrings.add("GIO_EXTRA_MODULES");
            envStrings.add(new File(context.getFilesDir(), "gio").getAbsolutePath());
            envStrings.add("WEBKIT_INJECTED_BUNDLE_PATH");
            envStrings.add(new File(context.getFilesDir(), "injected-bundles").getAbsolutePath());

            String filesPath = context.getFilesDir().getAbsolutePath();
            envStrings.add("XDG_DATA_DIRS");
            envStrings.add(filesPath);

            if (inspectorPort > 0) {
                String inspectorAddress = "127.0.0.1:" + inspectorPort;
                if (useHttpInspector) {
                    envStrings.add("WEBKIT_INSPECTOR_HTTP_SERVER");
                } else {
                    envStrings.add("WEBKIT_INSPECTOR_SERVER");
                }
                envStrings.add(inspectorAddress);
            }
            setupNativeEnvironment(envStrings.toArray(new String[envStrings.size()]));
            nativeInit();
            looperHelperThread = new LooperHelperThread();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        nativeShut();
    }

    protected final AuxiliaryProcessesContainer auxiliaryProcesses = new AuxiliaryProcessesContainer();

    private final WPEServiceConnectionListener serviceConnectionDelegate = new WPEServiceConnectionListener() {
        public void onCleanExit(@NonNull WPEServiceConnection connection) {
            Log.d(LOGTAG, "onCleanExit for process: " + connection.getPid());
            auxiliaryProcesses.unregister(connection.getPid());
        }

        public void onServiceDisconnected(@NonNull WPEServiceConnection connection) {
            Log.d(LOGTAG, "onServiceDisconnected for process: " + connection.getPid());
            // Unbind the service to prevent restarting on crash,
            // wpewebkit will restart the auxiliary process if needed
            auxiliaryProcesses.unregister(connection.getPid());
        }
    };

    /**
     * This method is called directly from WebKit when a new auxiliary process needs to be created.
     * <p>In order to safeguard the rest of the system and allow the application to remain responsive even if the
     * user had loaded web page that infinite loops or otherwise hangs, the modern incarnation of WebKit uses
     * multi-process architecture. Web pages are loaded in its own WebContent process. Multiple WebContent processes
     * can share a browsing session, which lives in a shared network process. In addition to handling all network
     * accesses, this process is also responsible for managing the disk cache and Web APIs that allow websites to
     * store structured data such as Web Storage API and IndexedDB API.</p>
     * <p>Because a WebContent process can Just-in-Time compile arbitrary JavaScript code loaded from the internet,
     * meaning that it can write to memory that gets executed, this process is tightly sandboxed. It does not have
     * access to any file system unless the user grants an access, and it does not have direct access to the
     * underlying operating system’s clipboard, microphone, or video camera even though there are Web APIs that grant
     * access to those features. Instead, UI process brokers such requests.</p>
     * <p>Given that Android forbids the fork syscall on non-rooted devices, we cannot directly spawn child processes
     * . Instead we use Android Services to host the logic of WebKit's auxiliary processes.</p>
     * <p>The life cycle of all WebKit's auxiliary processes is managed by WebKit itself. We only proxy requests to
     * spawn and terminate these processes/services.</p>
     *
     * FIXME: except for the case where Android decides to kill a Service. In that case we need to notify WebKit (and
     * wait for WebKit to spawn the Service again).
     *
     * @param pid The process identifier. This value is generated by WebKit and does not correspond to the actual
     * system pid.
     * @param type The type of service to launch. It can be Web (0) or Network (1).
     * @param fd File descriptor used by WebKit for IPC.
     */
    @Keep
    @WorkerThread
    public void launchProcess(long pid, int type, int fd) {
        WKProcessType processType = WKProcessType.fromValue(type);
        Log.d(LOGTAG, "launchProcess " + processType.name() + " (pid: " + pid + ", fd: " + fd + ")");

        int processSlot = auxiliaryProcesses.getFirstAvailableSlot(processType);
        try {
            Class<?> serviceClass =
                Class.forName("org.wpewebkit.wpe.services.WPEServices$" + processType.name() + "Service" + processSlot);
            ParcelFileDescriptor parcelFd = ParcelFileDescriptor.adoptFd(fd);

            Log.v(LOGTAG, "Launching service: " + processType.name());
            Intent intent = new Intent(applicationContext, serviceClass);

            WPEServiceConnection serviceConnection =
                new WPEServiceConnection(pid, processType, parcelFd, serviceConnectionDelegate);
            applicationContext.bindService(intent, serviceConnection,
                                           Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            auxiliaryProcesses.register(pid, serviceConnection);
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot launch auxiliary process", e);
        }
    }

    /**
     * Terminate the Service hosting the logic for a WebKit auxiliary process that matches the given pid.
     * @param pid The process identifier. This value is generated by WebKit and does not correspond to the actual
     * system pid.
     */
    @Keep
    @WorkerThread
    public void terminateProcess(long pid) {
        auxiliaryProcesses.unregister(pid);
    }

    /**
     * A sideline thread that allows Java-based Looper execution from native code.
     */
    private static final class LooperHelperThread {
        private boolean isInitialized = false;

        private final Thread thread = new Thread(() -> {
            Looper.prepare();
            startNativeLooper();

            synchronized (LooperHelperThread.this) {
                isInitialized = true;
                LooperHelperThread.this.notifyAll();
            }

            Looper.loop();
        });

        LooperHelperThread() {
            thread.start();

            synchronized (this) {
                while (!isInitialized) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Log.d(LOGTAG, "LooperHelperThread has been interrupted");
                    }
                }
            }
        }
    }
}
