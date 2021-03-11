package com.wpe.wpe.page;

import com.wpe.wpe.services.WPEServiceConnection;

import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;

public class PageGlue {
    private static final String LOGTAG = "PageGlue";

    private final Page m_page;

    static {
        System.loadLibrary("WPEBackend-default");
        System.loadLibrary("WPEPageGlue");
    }

    public static native void init(PageGlue self, int width, int height);

    public static native void deinit();

    public static native void setPageURL(String url);

    public static native void frameComplete();

    public static native void touchEvent(long time, int type, float x, float y);

    public PageGlue(Page page) {
        m_page = page;
    }

    public void launchProcess(int processType, int[] fds) {
        Log.v(LOGTAG, "launchProcess()");
        Log.v(LOGTAG, "Got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.v(LOGTAG, "  [" + i + "] " + fds[i]);
        }

        Parcelable[] parcelFds = new Parcelable[ /* fds.length */ 1];
        for (int i = 0; i < parcelFds.length; ++i) {
            parcelFds[i] = ParcelFileDescriptor.adoptFd(fds[i]);
        }

        switch (processType) {
            case WPEServiceConnection.PROCESS_TYPE_WEBPROCESS:
                Log.v(LOGTAG, "Should launch WebProcess");
                m_page.launchService(WPEServiceConnection.PROCESS_TYPE_WEBPROCESS,
                        parcelFds, com.wpe.wpe.services.webprocess.Service.class);
                break;
            case WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS:
                Log.v(LOGTAG, "Should launch NetworkProcess");
                m_page.launchService(WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS,
                        parcelFds, com.wpe.wpe.services.networkprocess.Service.class);
                break;
            default:
                Log.v(LOGTAG, "Invalid process type");
                break;
        }
    }
}
