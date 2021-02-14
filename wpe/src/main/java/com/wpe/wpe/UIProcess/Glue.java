package com.wpe.wpe.UIProcess;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Log;

import com.wpe.wpe.WPEActivity;

public class Glue {

    static {
        System.loadLibrary("WPEBackend-default");
        System.loadLibrary("WPEUIProcessGlue");
    }

    public static native void init(Glue glueObj, int width, int height,
                                   String webkitExecPath, String ldLibraryPath);
    public static native void deinit();

    public static native void setPageURL(String url);

    public static native void frameComplete();

    public static native void touchEvent(long time, int type, float x, float y);

    private final WPEActivity m_activity;

    public Glue(WPEActivity activity)
    {
        m_activity = activity;
    }

    public void launchProcess(int processType, int[] fds)
    {
        Log.i("Glue", "launchProcess()");
        Log.i("Glue", "got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("Glue", "  [" + i + "] " + fds[i]);
        }

        Parcelable[] parcelFds = new Parcelable[ /* fds.length */ 1];
        for (int i = 0; i < parcelFds.length; ++i) {
            parcelFds[i] = ParcelFileDescriptor.adoptFd(fds[i]);
        }

        switch (processType) {
            case WPEServiceConnection.PROCESS_TYPE_WEBPROCESS:
                Log.i("Glue", "should launch WebProcess");
                m_activity.launchService(WPEServiceConnection.PROCESS_TYPE_WEBPROCESS,
                                         parcelFds, com.wpe.wpe.WebProcess.Service.class);
                break;
            case WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS:
                Log.i("Glue", "should launch NetworkProcess");
                m_activity.launchService(WPEServiceConnection.PROCESS_TYPE_NETWORKPROCESS,
                                         parcelFds, com.wpe.wpe.NetworkProcess.Service.class);
                break;
            case WPEServiceConnection.PROCESS_TYPE_STORAGEPROCESS:
                Log.i("Glue", "should launch StorageProcess");
                m_activity.launchService(WPEServiceConnection.PROCESS_TYPE_STORAGEPROCESS,
                                         parcelFds, com.wpe.wpe.StorageProcess.Service.class);
                break;
            default:
                Log.i("Glue", "invalid process type");
                break;
        }
    }

    private void launchServiceInternal(int processType, Parcelable[] fds, Class cls)
    {
        Context context = m_activity.getBaseContext();
        Intent intent = new Intent(context, cls);

        WPEServiceConnection serviceConnection = new WPEServiceConnection(processType, m_activity, fds);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }
}
