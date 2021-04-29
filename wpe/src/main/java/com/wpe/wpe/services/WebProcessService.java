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
    private static final String assetsVersion = "v1";

    private final void copyFileOrDir(String path) {
        AssetManager assetManager = this.getAssets();
        String assets[] = null;
        try {
            assets = assetManager.list(path);
            if (assets.length == 0) {
                copyFile(path);
            } else {
                String fullPath = getApplicationContext().getFilesDir() + "/" + path;
                File dir = new File(fullPath);
                if (!dir.exists())
                    dir.mkdir();
                for (int i = 0; i < assets.length; ++i) {
                    copyFileOrDir(path + "/" + assets[i]);
                }
            }
        } catch (IOException ex) {
            Log.e("tag", "I/O Exception", ex);
        }
    }

    private final void copyFile(String filename) {
        AssetManager assetManager = this.getAssets();

        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open(filename);
            String newFileName = getApplicationContext().getFilesDir() + "/" + filename;
            out = new FileOutputStream(newFileName);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.e("tag", e.getMessage());
        }
    }

    // Tells whether we need to move the font config and gstreamer plugins to
    // the files dir. This is done only the first time WPEWebKit is launched or
    // everytime an update on these files is required
    private final boolean needAssets() {
        File file = new File(getApplicationContext().getFilesDir(), assetsVersion);
        return !file.exists();
    }

    private final void saveAssetsVersion() {
        File file = new File(getApplicationContext().getFilesDir(), assetsVersion);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch(IOException exception) {
                Log.e(LOGTAG, "Could not save assets version. This will affect boot up performance");
            }
        }
    }

    @Override
    public void onCreate()
    {
        Log.v(LOGTAG, "onCreate");
        super.onCreate();

        if (needAssets()) {
            copyFileOrDir("gstreamer-1.0");
            copyFileOrDir("fontconfig/fonts.conf");
            saveAssetsVersion();
        }

        Context context = getApplicationContext();
        String fontConfigPath = new File(context.getFilesDir(), "fontconfig")
                .getAbsolutePath();
        String gstreamerPath = new File(context.getFilesDir(), "gstreamer-1.0")
                .getAbsolutePath();
        ApplicationInfo appInfo = context.getApplicationInfo();

        WebProcessGlue.setupEnvironment(
                fontConfigPath,
                gstreamerPath,
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
