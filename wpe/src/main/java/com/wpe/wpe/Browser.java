package com.wpe.wpe;

import android.content.Context;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.wpe.wpe.services.WPEServiceConnection;
import com.wpe.wpeview.WPEView;

import java.io.File;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
    private static final String LOGTAG = "WPE Browser";
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
     * List of active Pages.
     * A page corresponds to a tab in regular browser's UI.
     */
    private IdentityHashMap<WPEView, Page> pages = null;

    /**
     * List of pending URL loads.
     * We queue an URL load if a `WPEView.loadURL` call is made while the Page associated to the
     * WPEView instance is not being initialized.
     */
    private IdentityHashMap<WPEView, PendingLoad> pendingLoads = null;

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

        // Create a WebKitWebContext and integrate webkit main loop with Android looper
        BrowserGlue.init(glue);
    }

    public static void initialize(Context context) {
        if (initialized) {
            return;
        }

        String[] envStringsArray = {"GIO_EXTRA_MODULES", new File(context.getFilesDir(), "gio").getAbsolutePath()};
        BrowserGlue.setupEnvironment(envStringsArray);
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

    /**
     * Create a new Page instance.
     * A Page corresponds to a tab in regular browser's UI
     *
     * @param wpeView The WPEView instance this Page is associated with.
     * There is a 1:1 relation between WPEView and Page instances.
     * @param context The Context this Page is created in.
     */
    public void createPage(@NonNull WPEView wpeView, @NonNull Context context) {
        Log.d(LOGTAG, "Create new Page instance for view " + wpeView);
        if (pages == null) {
            pages = new IdentityHashMap<>();
        }
        assert (!pages.containsKey(wpeView));
        Page page = new Page(this, context, wpeView, pages.size());
        pages.put(wpeView, page);
        activeView = wpeView;
        if (webProcess != null) {
            webProcess.setActivePage(page);
        }
        loadPendingUrls(wpeView);
    }

    public void destroyPage(@NonNull WPEView wpeView) {
        Log.d(LOGTAG, "Unregister Page for view");
        assert (pages.containsKey(wpeView));
        Page page = pages.remove(wpeView);
        page.close();
        if (activeView == wpeView) {
            activeView = null;
        }
    }

    public void onVisibilityChanged(@NonNull WPEView wpeView, int visibility) {
        Log.v(LOGTAG, "Visibility changed for " + wpeView + " to " + visibility);
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        if (visibility == android.view.View.VISIBLE) {
            activeView = wpeView;
            webProcess.setActivePage(pages.get(wpeView));
        }
    }

    @WorkerThread
    void launchAuxiliaryProcess(long pid, @NonNull ProcessType processType, int fd) {
        Log.d(LOGTAG, "launch " + processType.name() + ", pid: " + pid + ", fd: " + fd);

        Page page = pages.get(activeView);
        if (page == null) {
            Log.e(LOGTAG, "Cannot launch auxiliary process (no active page)");
            return;
        }

        int processSlot = auxiliaryProcesses.getFirstAvailableSlot(processType);
        Log.v(LOGTAG, "Should launch " + processType.name());

        try {
            Class<?> serviceClass =
                Class.forName("com.wpe.wpe.services.WPEServices$" + processType.name() + "Service" + processSlot);
            ParcelFileDescriptor parcelFd = ParcelFileDescriptor.adoptFd(fd);

            WPEServiceConnection connection = page.launchService(processType, parcelFd, serviceClass);
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

    private void queuePendingLoad(@NonNull WPEView wpeView, @NonNull PendingLoad pendingLoad) {
        Log.v(LOGTAG, "No available page. Queueing " + pendingLoad.url + " for load");
        if (pendingLoads == null) {
            pendingLoads = new IdentityHashMap<>();
        }
        // We only care about the last url.
        pendingLoads.put(wpeView, pendingLoad);
    }

    private void loadPendingUrls(@NonNull WPEView wpeView) {
        if (pendingLoads == null) {
            return;
        }
        PendingLoad load = pendingLoads.remove(wpeView);
        if (load != null) {
            loadUrl(wpeView, load.context, load.url);
        }
    }

    public void loadUrl(@NonNull WPEView wpeView, @NonNull Context context, @NonNull String url) {
        Log.d(LOGTAG, "Load URL " + url);
        if (pages == null || !pages.containsKey(wpeView)) {
            queuePendingLoad(wpeView, new PendingLoad(context, url));
            return;
        }
        pages.get(wpeView).loadUrl(context, url);
    }

    public boolean canGoBack(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return false;
        }
        return pages.get(wpeView).canGoBack();
    }

    public boolean canGoForward(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return false;
        }
        return pages.get(wpeView).canGoForward();
    }

    public void goBack(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).goBack();
    }

    public void goForward(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).goForward();
    }

    public void stopLoading(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).stopLoading();
    }

    public void reload(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).reload();
    }

    public void setInputMethodContent(@NonNull WPEView wpeView, char c) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).setInputMethodContent(c);
    }

    public void deleteInputMethodContent(@NonNull WPEView wpeView, int offset) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).deleteInputMethodContent(offset);
    }

    public void requestExitFullscreenMode(@NonNull WPEView wpeView) {
        if (pages == null || !pages.containsKey(wpeView)) {
            return;
        }
        pages.get(wpeView).requestExitFullscreenMode();
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

            public void terminate() { serviceConnection.getActivePage().stopService(serviceConnection); }
        }
    }

    /**
     * Temporary structure to store the data associated with a pending URL load.
     * We queue an URL load if a `WPEView.loadURL` call is made while the Page associated to the
     * WPEView instance is not being initialized.
     */
    private final class PendingLoad {
        public final String url;
        public final Context context;

        PendingLoad(Context context, String url) {
            this.url = url;
            this.context = context;
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
