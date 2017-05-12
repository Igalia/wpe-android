package com.wpe.wpe.NetworkProcess;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.wpe.wpe.WPEService;

public class Service extends WPEService {

    @Override
    public void onCreate()
    {
        super.onCreate();

        Context context = getBaseContext();
        Log.i("WPENetworkProcess", "arch " + System.getProperty("os.arch"));
        Log.i("WPENetworkProcess", System.getProperty("java.library.path"));

        ApplicationInfo appInfo = context.getApplicationInfo();
        Log.i("WPENetworkProcess", appInfo.nativeLibraryDir);
        Log.i("WPENetworkProcess", appInfo.sourceDir);

        Log.i("WPENetworkProcess", "files dir " + context.getFilesDir());
        Log.i("WPENetworkProcess", "cache dir " + context.getExternalCacheDir());
        Glue.initializeXdg(context.getCacheDir().getAbsolutePath());
        com.wpe.wpe.NetworkProcess.Glue.initializeGioExtraModulesPath(appInfo.nativeLibraryDir);
    }

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {
        Log.i("WPENetworkProcess", "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("WPENetworkProcess", " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }
        Glue.initializeMain(fds[0].detachFd());
    }

}
