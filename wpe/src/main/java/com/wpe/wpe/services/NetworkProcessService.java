package com.wpe.wpe.services;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wpe.wpe.ProcessType;

import java.io.File;

public class NetworkProcessService extends WPEService
{
    private static final String LOGTAG = "WPENetworkProcess";

    // Bump this version number if you make any changes to the gio
    // modules or else they won't be applied.
    private static final String assetsVersion = "network_process_assets_v1";

    @Override
    protected void setupServiceEnvironment()
    {
        Context context = getApplicationContext();
        if (ServiceUtils.needAssets(context, assetsVersion)) {
            ServiceUtils.copyFileOrDir(context, getAssets(), "gio");
            ServiceUtils.saveAssetsVersion(context, assetsVersion);
        }

        String[] envStringsArray = {
            "XDG_RUNTIME_DIR", context.getCacheDir().getAbsolutePath(),
            "GIO_EXTRA_MODULES", new File(context.getFilesDir(), "gio").getAbsolutePath()
        };
        NetworkProcessGlue.setupEnvironment(envStringsArray);
    }

    @Override
    protected void initializeServiceMain(@NonNull ParcelFileDescriptor parcelFd)
    {
        Log.v(LOGTAG, "initializeServiceMain() fd: " + parcelFd + ", native value: " + parcelFd.getFd());
        NetworkProcessGlue.initializeMain(ProcessType.NetworkProcess.getValue(), parcelFd.detachFd());
    }
}
