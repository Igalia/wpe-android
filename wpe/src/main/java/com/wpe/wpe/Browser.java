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

package com.wpe.wpe;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.wpe.wpe.services.WPEServiceConnection;
import com.wpe.wpe.services.WPEServiceConnectionDelegate;
import com.wpe.wpeview.WPEView;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Top level Singleton object. Somehow equivalent to WebKit's UIProcess. Among other duties it:
 * <p>
 * - manages the creation and destruction of Page instances.
 * - funnels WPEView API calls to the appropriate Page instance.
 * - manages the Android Services equivalent to WebKit's auxiliary processes.
 */
@UiThread
public final class Browser {
    private static final String LOGTAG = "WPEBrowser";
    // FIXME: There is no real fixed limitation on the number of services an app can spawn on
    //        Android or the number of auxiliary processes WebKit spawns. However we have a
    //        limitation imposed by the way Android requires Services to be defined in the
    //        AndroidManifest. We have to generate the manifest at build time adding an independent
    //        entry for each Service we expect to launch. This magic number is taken from GeckoView,
    //        which uses a similar approach.
    private static final int MAX_AUX_PROCESSES = 40;

    private static Browser instance = null;

    /**
     * This is process-wide initialization flag that prevents multiple trips into
     * the native glue code where extra initialization is needed. It is not tied
     * to the Browser instance lifetime in any way.
     */
    private static boolean initialized = false;

    /**
     * Instance of the glue code exposing the JNI API to communicate with WebKit.
     */
    private final BrowserGlue glue;

    /**
     * Under normal circumstances there is only one application context in a process, so it's safe
     * to treat this as a global.
     */
    private Context applicationContext;

    /**
     * A sideline thread that enables tying into Java-based Looper execution from native code.
     */
    private final LooperHelperThread looperHelperThread;

    /**
     * The list of active auxiliary processes.
     *
     * @see AuxiliaryProcesses.AuxiliaryProcess
     */
    private final AuxiliaryProcesses auxiliaryProcesses = new AuxiliaryProcesses();

    /**
     * References to the single web and web processes.
     *
     * FIXME: Since we do not support PSON fully yet, we cannot tie the auxiliary
     * processes life cycle to the Page life cycle.
     * We need to keep the single instance of the Web and Network processes
     * at the Browser level.
     * Once PSON is fully supported, we'll need to move this into Page.
     */
    private WPEServiceConnection webProcess;
    private WPEServiceConnection networkProcess;

    private WPEServiceConnectionDelegate serviceConnectionDelegate = new WPEServiceConnectionDelegate() {
        @Override
        public void onCleanExit(WPEServiceConnection connection) {
            Log.i(LOGTAG, "onCleanExit");
            auxiliaryProcesses.unregister(connection.getPid());
        }

        @Override
        public void onServiceDisconnected(WPEServiceConnection connection) {
            Log.i(LOGTAG, "onServiceDisconnected");
            // Unbinds service which prevents restart on crash.
            // Let wpe restart auxiliary processes if necessary
            auxiliaryProcesses.unregister(connection.getPid());
        }
    };

    /**
     * The active view is the last view that changed its visibility to VISIBLE.
     * <p>
     * We use this to know which auxiliary processes belongs to which WPEView/Page instance.
     * <p>
     * FIXME: Find a better way to do this match. There are cases where this might not be
     * true (i.e. a non-visible tab triggering the creation of a new WebProcess)
     */
    private WPEView activeView = null;

    private Browser() {
        Log.v(LOGTAG, "Browser creation");
        glue = new BrowserGlue(this);
        looperHelperThread = new LooperHelperThread();
    }

    public void initialize(Context context) {
        if (initialized) {
            return;
        }

        applicationContext = context;

        String[] envStringsArray = {"GIO_EXTRA_MODULES", new File(context.getFilesDir(), "gio").getAbsolutePath()};
        BrowserGlue.setupEnvironment(envStringsArray);

        // Create a WebKitWebContext and integrate webkit main loop with Android looper
        BrowserGlue.init(glue, context.getDataDir().getAbsolutePath(), context.getCacheDir().getAbsolutePath());
        initialized = true;
    }

    public static Browser getInstance() {
        if (instance == null) {
            instance = new Browser();
        }
        return instance;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        BrowserGlue.shut();
    }

    @WorkerThread
    void launchAuxiliaryProcess(long pid, @NonNull ProcessType processType, int fd) {
        Log.d(LOGTAG, "launch " + processType.name() + ", pid: " + pid + ", fd: " + fd);

        int processSlot = auxiliaryProcesses.getFirstAvailableSlot(processType);
        Log.v(LOGTAG, "Should launch " + processType.name());

        try {
            Class<?> serviceClass =
                Class.forName("com.wpe.wpe.services.WPEServices$" + processType.name() + "Service" + processSlot);
            ParcelFileDescriptor parcelFd = ParcelFileDescriptor.adoptFd(fd);

            WPEServiceConnection connection = launchService(pid, processType, parcelFd, serviceClass);
            auxiliaryProcesses.register(pid, connection);
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot launch auxiliary process", e);
        }
    }

    @WorkerThread
    void terminateAuxiliaryProcess(long pid) {
        auxiliaryProcesses.unregister(pid);
    }

    @WorkerThread
    public void setWebProcess(WPEServiceConnection process) {
        webProcess = process;
    }

    @WorkerThread
    public void setNetworkProcess(WPEServiceConnection process) {
        networkProcess = process;
    }

    @WorkerThread
    private WPEServiceConnection launchService(long pid, @NonNull ProcessType processType,
                                               @NonNull ParcelFileDescriptor parcelFd, @NonNull Class<?> serviceClass) {
        Log.v(LOGTAG, "launchService type: " + processType.name());
        Intent intent = new Intent(applicationContext, serviceClass);

        WPEServiceConnection serviceConnection =
            new WPEServiceConnection(pid, processType, parcelFd, serviceConnectionDelegate);
        switch (processType) {
        case WebProcess:
            // FIXME: we probably want to kill the current web process here if any exists when PSON is enabled.
            setWebProcess(serviceConnection);
            break;

        case NetworkProcess:
            setNetworkProcess(serviceConnection);
            break;

        default:
            throw new IllegalArgumentException("Unknown process type");
        }

        applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        return serviceConnection;
    }

    private void stopService(@NonNull WPEServiceConnection serviceConnection) {
        applicationContext.unbindService(serviceConnection);
    }

    /**
     * In order to safeguard the rest of the system and allow the application to remain responsive
     * even if the user had loaded web page that infinite loops or otherwise hangs, the modern
     * incarnation of WebKit uses multi-process architecture. Web pages are loaded in its own
     * WebContent process. Multiple WebContent processes can share a browsing session, which lives
     * in a shared network process. In addition to handling all network accesses, this process is
     * also responsible for managing the disk cache and Web APIs that allow websites to store
     * structured data such as Web Storage API and IndexedDB API.
     * <p>
     * Because a WebContent process can Just-in-Time compile arbitrary JavaScript code loaded from
     * the internet, meaning that it can write to memory that gets executed, this process is
     * tightly sandboxed. It does not have access to any file system unless the user grants an
     * access, and it does not have direct access to the underlying operating system’s clipboard,
     * microphone, or video camera even though there are Web APIs that grant access to those
     * features. Instead, UI process brokers such requests.
     * <p>
     * Given that Android forbids the fork syscall on non-rooted devices, we cannot directly spawn
     * child processes. Instead we use Android Services to host the logic of WebKit's auxiliary
     * processes.
     * <p>
     * The life cycle of all WebKit's auxiliary processes is managed by WebKit itself. We only proxy
     * requests to spawn and terminate these processes/services.
     * FIXME: except for the case where Android decides to kill a Service. In that case we need to
     * notify WebKit. And? wait for WebKit to spawn the Service again?
     */
    private static final class AuxiliaryProcesses {
        private final AuxiliaryProcess[][] processes =
            new AuxiliaryProcess[ProcessType.values().length][MAX_AUX_PROCESSES];
        private final Map<Long, AuxiliaryProcess> pidToProcessMap = new HashMap<>();
        private final int[] firstAvailableSlot = new int[ProcessType.values().length];

        public int getFirstAvailableSlot(@NonNull ProcessType processType) {
            return firstAvailableSlot[processType.getValue()];
        }

        public void register(long pid, @NonNull WPEServiceConnection connection) {
            int typeIdx = connection.getProcessType().getValue();
            int slot = firstAvailableSlot[typeIdx];
            if (slot >= MAX_AUX_PROCESSES)
                throw new IllegalStateException("Limit exceeded spawning a new auxiliary process for " +
                                                connection.getProcessType().name());

            assert (processes[typeIdx][slot] == null);
            processes[typeIdx][slot] = new AuxiliaryProcess(slot, connection);
            pidToProcessMap.put(pid, processes[typeIdx][slot]);

            while (++firstAvailableSlot[typeIdx] < MAX_AUX_PROCESSES) {
                if (processes[typeIdx][firstAvailableSlot[typeIdx]] == null)
                    break;
            }
        }

        public void unregister(long pid) {
            AuxiliaryProcess process = pidToProcessMap.remove(pid);
            if (process == null)
                return;

            process.terminate();

            int typeIdx = process.getProcessType().getValue();
            processes[typeIdx][process.getProcessSlot()] = null;
            firstAvailableSlot[typeIdx] = Math.min(process.getProcessSlot(), firstAvailableSlot[typeIdx]);
        }

        private static final class AuxiliaryProcess {
            private final int processSlot;
            private final WPEServiceConnection serviceConnection;

            public AuxiliaryProcess(int processSlot, @NonNull WPEServiceConnection connection) {
                this.processSlot = processSlot;
                serviceConnection = connection;
            }

            public int getProcessSlot() { return processSlot; }

            public ProcessType getProcessType() { return serviceConnection.getProcessType(); }

            public void terminate() { Browser.getInstance().stopService(serviceConnection); }
        }
    }

    /**
     * A sideline thread that enables tying into Java-based Looper execution from native code.
     */
    private final class LooperHelperThread {
        private final Thread thread;
        boolean initialized;

        LooperHelperThread() {
            final LooperHelperThread self = this;
            initialized = false;

            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Looper.prepare();

                    BrowserGlue.initLooperHelper();

                    synchronized (self) {
                        initialized = true;
                        self.notifyAll();
                    }

                    Looper.loop();
                }
            });

            thread.start();

            synchronized (self) {
                try {
                    while (!initialized)
                        self.wait();
                } catch (InterruptedException e) {
                    Log.v(LOGTAG, "Interruption in LooperHelperThread");
                }
            }
        }
    }
}
