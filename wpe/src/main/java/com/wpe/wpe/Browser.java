package com.wpe.wpe;

import android.content.Context;
import android.os.LimitExceededException;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

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
public final class Browser
{
    private static final String LOGTAG = "WPE Browser";

    private static Browser m_instance = null;

    /**
     * This is process-wide initialization flag that prevents multiple trips into
     * the native glue code where extra initialization is needed. It is not tied
     * to the Browser instance lifetime in any way.
     */
    private static boolean m_initialized = false;

    /**
     * Instance of the glue code exposing the JNI API to communicate with WebKit.
     */
    private final BrowserGlue m_glue;

    /**
     * A sideline thread that enables tying into Java-based Looper execution from native code.
     */
    private final LooperHelperThread m_looperHelperThread;

    /**
     * Thread where the actual WebKit's UIProcess logic runs.
     * It hosts an instance of WebKitWebContext and runs the main loop.
     */
    private final UIProcessThread m_uiProcessThread;

    /**
     * List of active Pages.
     * A page corresponds to a tab in regular browser's UI.
     */
    private IdentityHashMap<WPEView, Page> m_pages = null;

    /**
     * List of pending URL loads.
     * We queue an URL load if a `WPEView.loadURL` call is made while the Page associated to the
     * WPEView instance is not being initialized.
     */
    private IdentityHashMap<WPEView, PendingLoad> m_pendingLoads = null;

    /**
     * The list of active auxiliary processes.
     *
     * @see AuxiliaryProcesses.AuxiliaryProcess
     */
    private final AuxiliaryProcesses m_auxiliaryProcesses = new AuxiliaryProcesses();

    /*
     * References to the single web and web processes.
     *
     * FIXME: Since we do not support PSON fully yet, we cannot tie the auxiliary
     *        processes life cycle to the Page life cycle.
     *        We need to keep the single instance of the Web and Network processes
     *        at the Browser level.
     *        Once PSON is fully supported, we'll need to move this into Page.
     */
    private WPEServiceConnection m_webProcess;
    private WPEServiceConnection m_networkProcess;

    /**
     * The active view is the last view that changed its visibility to VISIBLE.
     * <p>
     * We use this to know which auxiliary processes belongs to which WPEView/Page instance.
     * <p>
     * FIXME: Find a better way to do this match. There are cases where this might not be
     * true (i.e. a non-visible tab triggering the creation of a new WebProcess)
     */
    private WPEView m_activeView = null;

    // FIXME: There is no real fixed limitation on the number of services an app can spawn on
    //        Android or the number of auxiliary processes WebKit spawns. However we have a
    //        limitation imposed by the way Android requires Services to be defined in the
    //        AndroidManifest. We have to generate the manifest at build time adding an independent
    //        entry for each Service we expect to launch. This magic number is taken from GeckoView,
    //        which uses a similar approach.
    private static final int MAX_AUX_PROCESSES = 40;

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
    private final class AuxiliaryProcesses
    {
        private final class AuxiliaryProcess
        {
            public final long m_pid;

            private final Page m_page;
            private final WPEServiceConnection m_serviceConnection;

            AuxiliaryProcess(long pid, @NonNull Page page, @NonNull WPEServiceConnection connection)
            {
                m_pid = pid;
                m_page = page;
                m_serviceConnection = connection;
            }

            void terminate()
            {
                m_page.stopService(m_serviceConnection);
            }
        }

        private final AuxiliaryProcess[] m_processes = new AuxiliaryProcess[MAX_AUX_PROCESSES];
        private final Map<Long, Integer> m_processIndexes = new HashMap<>();
        private int m_firstAvailableIndex = 0;

        void register(long pid, @NonNull Page page, @NonNull WPEServiceConnection connection)
        {
            if (m_firstAvailableIndex >= MAX_AUX_PROCESSES) {
                throw new LimitExceededException("Limit exceeded spawning a new auxiliary process");
            }
            assert (m_processes[m_firstAvailableIndex] == null);
            m_processes[m_firstAvailableIndex] = new AuxiliaryProcess(pid, page, connection);
            m_processIndexes.put(pid, m_firstAvailableIndex);
            m_firstAvailableIndex++;
            while (m_firstAvailableIndex < MAX_AUX_PROCESSES) {
                if (m_processes[m_firstAvailableIndex] == null) {
                    break;
                }
                m_firstAvailableIndex++;
            }
        }

        void unregister(long pid)
        {
            if (!m_processIndexes.containsKey(pid)) {
                Log.w(LOGTAG, "Cannot unregister unregistered process " + pid);
                return;
            }
            int index = m_processIndexes.get(pid);
            if (m_processes[index] == null) {
                return;
            }
            m_processes[index].terminate();
            m_processes[index] = null;
            m_firstAvailableIndex = Math.min(index, m_firstAvailableIndex);
        }

        public int getFirstAvailableIndex()
        {
            return m_firstAvailableIndex;
        }
    }

    /**
     * Temporary structure to store the data associated with a pending URL load.
     * We queue an URL load if a `WPEView.loadURL` call is made while the Page associated to the
     * WPEView instance is not being initialized.
     */
    private final class PendingLoad
    {
        public final String m_url;
        public final Context m_context;

        PendingLoad(Context context, String url)
        {
            m_url = url;
            m_context = context;
        }
    }

    /**
     * A sideline thread that enables tying into Java-based Looper execution from native code.
     */
    private final class LooperHelperThread
    {
        private final Thread m_thread;
        boolean m_initialized;

        LooperHelperThread()
        {
            final LooperHelperThread self = this;
            m_initialized = false;

            m_thread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    Looper.prepare();

                    BrowserGlue.initLooperHelper();

                    synchronized (self) {
                        m_initialized = true;
                        self.notifyAll();
                    }

                    Looper.loop();
                }
            });

            m_thread.start();

            synchronized (self) {
                try {
                    while (!m_initialized)
                        self.wait();
                } catch (InterruptedException e) {
                    Log.v(LOGTAG, "Interruption in LooperHelperThread");
                }
            }
        }
    }

    /**
     * Thread where the actual WebKit's UIProcess logic runs.
     * It hosts an instance of WebKitWebContext and runs the main loop.
     */
    private final class UIProcessThread
    {
        private final Thread m_thread;
        private BrowserGlue m_glueRef;

        UIProcessThread()
        {
            final UIProcessThread self = this;

            m_thread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    Log.i(LOGTAG, "In UIProcess thread");
                    while (true) {
                        try {
                            while (self.m_glueRef == null) {
                                self.wait();
                            }
                        } catch (InterruptedException e) {
                            Log.v(LOGTAG, "Interruption in UIProcess thread");
                        }
                        // Create a WebKitWebContext and run the main loop.
                        BrowserGlue.init(m_glueRef);
                    }
                }
            });

            m_thread.start();
        }

        public void run(@NonNull BrowserGlue glue)
        {
            final UIProcessThread self = this;
            synchronized (self) {
                m_glueRef = glue;
                self.notifyAll();
            }
        }
    }

    private Browser()
    {
        Log.v(LOGTAG, "Browser creation");
        m_glue = new BrowserGlue(this);
        m_looperHelperThread = new LooperHelperThread();
        m_uiProcessThread = new UIProcessThread();
        m_uiProcessThread.run(m_glue);
    }

    public static void initialize(Context context)
    {
        if (m_initialized) {
            return;
        }

        String[] envStringsArray = {
            "GIO_EXTRA_MODULES", new File(context.getFilesDir(), "gio").getAbsolutePath()
        };
        BrowserGlue.setupEnvironment(envStringsArray);
        m_initialized = true;
    }

    public static Browser getInstance()
    {
        if (m_instance == null) {
            m_instance = new Browser();
        }
        return m_instance;
    }

    @Override
    protected void finalize() throws Throwable
    {
        super.finalize();
        m_glue.shut();
    }

    /**
     * Create a new Page instance.
     * A Page corresponds to a tab in regular browser's UI
     *
     * @param wpeView The WPEView instance this Page is associated with.
     *                There is a 1:1 relation between WPEView and Page instances.
     * @param context The Context this Page is created in.
     */
    public void createPage(@NonNull WPEView wpeView, @NonNull Context context)
    {
        Log.d(LOGTAG, "Create new Page instance for view " + wpeView);
        if (m_pages == null) {
            m_pages = new IdentityHashMap<>();
        }
        assert (!m_pages.containsKey(wpeView));
        Page page = new Page(this, context, wpeView, m_pages.size());
        m_pages.put(wpeView, page);
        m_activeView = wpeView;
        if (m_webProcess != null) {
            m_webProcess.setActivePage(page);
        }
        loadPendingUrls(wpeView);
    }

    public void destroyPage(@NonNull WPEView wpeView)
    {
        Log.d(LOGTAG, "Unregister Page for view");
        assert (m_pages.containsKey(wpeView));
        Page page = m_pages.remove(wpeView);
        page.close();
        if (m_activeView == wpeView) {
            m_activeView = null;
        }
    }

    public void onVisibilityChanged(@NonNull WPEView wpeView, int visibility)
    {
        Log.v(LOGTAG, "Visibility changed for " + wpeView + " to " + visibility);
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        if (visibility == android.view.View.VISIBLE) {
            m_activeView = wpeView;
            m_webProcess.setActivePage(m_pages.get(wpeView));
        }
    }

    void launchAuxiliaryProcess(long pid, int processType, @NonNull int[] fds)
    {
        Log.d(LOGTAG, "launchProcess of type " + processType + " pid " + pid);
        Log.v(LOGTAG, "Got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.v(LOGTAG, "  [" + i + "] " + fds[i]);
        }

        int processSlot = m_auxiliaryProcesses.getFirstAvailableIndex();
        Class cls;

        try {
            switch (processType) {
            case WPEServiceConnection.PROCESS_TYPE_WEBPROCESS:
                Log.v(LOGTAG, "Should launch WebProcess");
                cls = Class.forName("com.wpe.wpe.services.WPEServices$WebProcessService" + processSlot);
                break;
            case WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS:
                Log.v(LOGTAG, "Should launch NetworkProcess");
                cls = Class.forName("com.wpe.wpe.services.WPEServices$NetworkProcessService" + processSlot);
                break;
            default:
                Log.v(LOGTAG, "Invalid process type");
                return;
            }
        } catch (ClassNotFoundException e) {
            Log.e(LOGTAG, "Could not launch auxiliary process", e);
            return;
        }

        Parcelable[] parcelFds = new Parcelable[ /* fds.length */ 1];
        for (int i = 0; i < parcelFds.length; ++i) {
            parcelFds[i] = ParcelFileDescriptor.adoptFd(fds[i]);
        }

        Page page = m_pages.get(m_activeView);
        if (page == null) {
            Log.e(LOGTAG, "No active page. Cannot launch auxiliary process");
            return;
        }

        WPEServiceConnection connection = page.launchService(processType, parcelFds, cls);
        m_auxiliaryProcesses.register(pid, page, connection);
    }

    void terminateAuxiliaryProcess(long pid)
    {
        m_auxiliaryProcesses.unregister(pid);
    }

    public void setWebProcess(WPEServiceConnection process)
    {
        m_webProcess = process;
    }

    public void setNetworkProcess(WPEServiceConnection process)
    {
        m_networkProcess = process;
    }

    private void queuePendingLoad(@NonNull WPEView wpeView, @NonNull PendingLoad pendingLoad)
    {
        Log.v(LOGTAG, "No available page. Queueing " + pendingLoad.m_url + " for load");
        if (m_pendingLoads == null) {
            m_pendingLoads = new IdentityHashMap<>();
        }
        // We only care about the last url.
        m_pendingLoads.put(wpeView, pendingLoad);
    }

    private void loadPendingUrls(@NonNull WPEView wpeView)
    {
        if (m_pendingLoads == null) {
            return;
        }
        PendingLoad load = m_pendingLoads.remove(wpeView);
        if (load != null) {
            loadUrl(wpeView, load.m_context, load.m_url);
        }
    }

    public void loadUrl(@NonNull WPEView wpeView, @NonNull Context context, @NonNull String url)
    {
        Log.d(LOGTAG, "Load URL " + url);
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            queuePendingLoad(wpeView, new PendingLoad(context, url));
            return;
        }
        m_pages.get(wpeView).loadUrl(context, url);
    }

    public boolean canGoBack(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return false;
        }
        return m_pages.get(wpeView).canGoBack();
    }

    public boolean canGoForward(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return false;
        }
        return m_pages.get(wpeView).canGoForward();
    }

    public void goBack(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).goBack();
    }

    public void goForward(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).goForward();
    }

    public void stopLoading(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).stopLoading();
    }

    public void reload(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).reload();
    }

    public void setInputMethodContent(@NonNull WPEView wpeView, char c)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).setInputMethodContent(c);
    }

    public void deleteInputMethodContent(@NonNull WPEView wpeView, int offset)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).deleteInputMethodContent(offset);
    }

    public void requestExitFullscreenMode(@NonNull WPEView wpeView)
    {
        if (m_pages == null || !m_pages.containsKey(wpeView)) {
            return;
        }
        m_pages.get(wpeView).requestExitFullscreenMode();
    }
}
