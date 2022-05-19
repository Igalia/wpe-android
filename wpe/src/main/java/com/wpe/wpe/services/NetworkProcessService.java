package com.wpe.wpe.services;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;

public class NetworkProcessService extends WPEService
{
    private static final String LOGTAG = "WPENetworkProcess";

    // Bump this version number if you make any changes to the gio
    // modules or else they won't be applied.
    private static final String assetsVersion = "network_process_assets_v1";

    @Override
    public void onCreate()
    {
        super.onCreate();

        Context context = getApplicationContext();

        if (ServiceUtils.needAssets(context, assetsVersion)) {
            ServiceUtils.copyFileOrDir(context, getAssets(), "gio");
            ServiceUtils.saveAssetsVersion(context, assetsVersion);
        }

        String gioPath = new File(context.getFilesDir(), "gio").getAbsolutePath();
        NetworkProcessGlue.setupEnvironment(context.getCacheDir().getAbsolutePath(), gioPath);
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
