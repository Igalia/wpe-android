package com.wpe.wpe.services;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ServiceUtils
{
    private static final String LOGTAG = "ServiceUtils";

    public static void copyFileOrDir(Context context, AssetManager assetManager, String path)
    {
        String assets[] = null;
        try {
            assets = assetManager.list(path);
            if (assets.length == 0) {
                copyFile(context, assetManager, path);
            } else {
                String fullPath = context.getFilesDir() + "/" + path;
                File dir = new File(fullPath);
                if (!dir.exists())
                    dir.mkdir();
                for (int i = 0; i < assets.length; ++i) {
                    copyFileOrDir(context, assetManager, path + "/" + assets[i]);
                }
            }
        } catch (IOException ex) {
            Log.e(LOGTAG, "I/O Exception", ex);
        }
    }

    private static void copyFile(Context context, AssetManager assetManager, String filename)
    {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open(filename);
            File newFile = new File(context.getFilesDir(), filename);
            newFile.getParentFile().mkdirs();
            out = new FileOutputStream(newFile, false);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.e(LOGTAG, e.getMessage());
        }
    }

    // Tells whether we need to move the font config and gstreamer plugins to
    // the files dir. This is done only the first time WPEWebKit is launched or
    // everytime an update on these files is required
    public static boolean needAssets(Context context, String assetsVersion)
    {
        File file = new File(context.getFilesDir(), assetsVersion);
        return !file.exists();
    }

    public static void saveAssetsVersion(Context context, String assetsVersion)
    {
        File file = new File(context.getFilesDir(), assetsVersion);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                Log.e(LOGTAG, "Could not save assets version. This will affect boot up performance");
            }
        }
    }
}
