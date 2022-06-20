package com.wpe.wpe.services;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ServiceUtils
{
    private static final String LOGTAG = "ServiceUtils";

    public static void copyFileOrDir(@NonNull Context context, @NonNull AssetManager assetManager,
                                     @NonNull String path, boolean copyForCurrentABI)
    {
        String srcAsset = null;
        if (copyForCurrentABI) {
            for (String abi : Build.SUPPORTED_ABIS) {
                String testPath = path + File.separator + abi;
                try {
                    if (assetManager.list(testPath).length > 0) {
                        srcAsset = testPath;
                        break;
                    }
                } catch (IOException e) {}
            }

            if (srcAsset == null) {
                Log.e(LOGTAG, "Cannot find " + path + " assets for current ABI");
                return;
            }
        } else
            srcAsset = path;

        File destFile = new File(context.getFilesDir(), path);
        try {
            recCopyFileOrDir(assetManager, srcAsset, destFile);
        } catch (IOException e) {
            Log.e(LOGTAG, "Cannot copy assets from " + srcAsset + " to " + destFile.getAbsolutePath(), e);
        }
    }

    private static void recCopyFileOrDir(@NonNull AssetManager assetManager,
                                         @NonNull String srcAsset, @NonNull File destFile) throws IOException
    {
        String assets[] = assetManager.list(srcAsset);
        if (assets.length == 0)
            copyFile(assetManager, srcAsset, destFile);
        else {
            for (String asset : assets)
                recCopyFileOrDir(assetManager, srcAsset + File.separator + asset, new File(destFile, asset));
        }
    }

    private static void copyFile(@NonNull AssetManager assetManager,
                                 @NonNull String srcAsset, @NonNull File destFile) throws IOException
    {
        destFile.getParentFile().mkdirs();
        try (InputStream in = assetManager.open(srcAsset); OutputStream out = new FileOutputStream(destFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
        }
    }

    // Tells whether we need to move the font config and gstreamer plugins to
    // the files dir. This is done only the first time WPEWebKit is launched or
    // everytime an update on these files is required
    public static boolean needAssets(@NonNull Context context, @NonNull String assetsVersion)
    {
        File file = new File(context.getFilesDir(), assetsVersion);
        return !file.exists();
    }

    public static void saveAssetsVersion(@NonNull Context context, @NonNull String assetsVersion)
    {
        File file = new File(context.getFilesDir(), assetsVersion);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                Log.e(LOGTAG, "Could not save assets version. This will affect boot up performance", e);
            }
        }
    }
}
