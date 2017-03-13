package com.wpe.wpe.NetworkProcess;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.wpe.wpe.WPEService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Service extends WPEService {

    @Override
    public void onCreate()
    {
        super.onCreate();

        try {
            Context context = getBaseContext();
            Log.i("WPENetworkProcess", "files dir " + context.getFilesDir());
            Log.i("WPENetworkProcess", "cache dir " + context.getExternalCacheDir());

            AssetManager assetManager = context.getAssets();

            InputStream is = assetManager.open("giognutls/libgiognutls.so");

            File osDir = new File(context.getFilesDir(), "giognutls");
            osDir.mkdirs();
            com.wpe.wpe.NetworkProcess.Glue.initializeGioExtraModulesPath(osDir.getAbsolutePath());

            File osFile = new File(osDir, "libgiognutls.so");
            Log.i("WPEAssets", "copying giognutls/libgiognutls.so to " + osFile.getAbsolutePath());
            OutputStream os = new FileOutputStream(osFile);

            int read;
            byte[] buffer = new byte[1024];
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }

            is.close();
            os.flush();
            os.close();
        } catch (IOException e) {
            Log.i("WPEAssets", "asset load failed: " + e);
        }
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
