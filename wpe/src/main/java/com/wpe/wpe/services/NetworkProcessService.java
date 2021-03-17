package com.wpe.wpe.services;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.wpe.wpe.services.NetworkProcessGlue;
import com.wpe.wpe.services.WPEService;

public class NetworkProcessService extends WPEService {
    private static final String LOGTAG = "WPENetworkProcess";

    @Override
    public void onCreate()
    {
        super.onCreate();

        Context context = getBaseContext();
        ApplicationInfo appInfo = context.getApplicationInfo();
        NetworkProcessGlue.initializeXdg(context.getCacheDir().getAbsolutePath());
        NetworkProcessGlue.initializeGioExtraModulesPath(appInfo.nativeLibraryDir);
    }

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {
        Log.v(LOGTAG, "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.v(LOGTAG, " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }
        NetworkProcessGlue.initializeMain(fds[0].detachFd());
    }

}
