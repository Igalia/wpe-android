package com.wpe.wpe;

import com.wpe.wpe.services.WPEServiceConnection;

import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

@UiThread
public class BrowserGlue {
    private static final String LOGTAG = "BrowserGlue";

    private final Browser m_browser;

    static {
        System.loadLibrary("WPEBackend-default");
        System.loadLibrary("WPEBrowserGlue");
    }

    public static native void init(BrowserGlue self);
    public static native void deinit();

    public static native void newWebView(Page page, int width, int height);
    public static native void destroyWebView(long webView);

    public static native void loadURL(long webView, String url);

    public static native void frameComplete();
    public static native void touchEvent(long time, int type, float x, float y);

    public BrowserGlue(@NonNull Browser browser) {
        m_browser = browser;
    }

    public void launchProcess(int processType, @NonNull int[] fds) {
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
                //m_browser.launchService(WPEServiceConnection.PROCESS_TYPE_WEBPROCESS,
                //                        parcelFds, com.wpe.wpe.services.webprocess.Service.class);
                break;
            case WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS:
                Log.v(LOGTAG, "Should launch NetworkProcess");
                //m_browser.launchService(WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS,
                //                        parcelFds, com.wpe.wpe.services.networkprocess.Service.class);
                break;
            default:
                Log.v(LOGTAG, "Invalid process type");
                break;
        }
    }
}
