package com.wpe.wpe.services;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class WebProcessService extends WPEService {
    private static final String LOGTAG = "WPEWebProcess";
    private boolean m_initialized = false;

    // Bump this version number if you make any changes to the font config
    // or the gstreamer plugins or else they won't be applied.
    private static final String assetsVersion = "web_process_assets_v1";

    @Override
    public void onCreate()
    {
        Log.v(LOGTAG, "onCreate");
        super.onCreate();

        Context context = getApplicationContext();
        if (ServiceUtils.needAssets(context, assetsVersion)) {
            ServiceUtils.copyFileOrDir(context, getAssets(), "gstreamer-1.0");
            ServiceUtils.copyFileOrDir(context, getAssets(), "fontconfig/fonts.conf");
            ServiceUtils.saveAssetsVersion(context, assetsVersion);
        }

        String fontConfigPath = new File(context.getFilesDir(), "fontconfig")
                .getAbsolutePath();
        String gstreamerPath = new File(context.getFilesDir(), "gstreamer-1.0")
                .getAbsolutePath();
        String gioPath = new File(context.getFilesDir(), "gio").getAbsolutePath();
        ApplicationInfo appInfo = context.getApplicationInfo();

        WebProcessGlue.setupEnvironment(
                fontConfigPath,
                gstreamerPath,
                gioPath,
                appInfo.nativeLibraryDir,
                context.getCacheDir().getAbsolutePath(),
                context.getFilesDir().getAbsolutePath()
        );
    }

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {
        Log.v(LOGTAG, "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.v(LOGTAG, " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }

        Log.i(LOGTAG, "about to start main()");
        m_initialized = true;
        WebProcessGlue.initializeMain(fds[0].detachFd(), /* fds[1].detachFd() */ -1);
    }
}
