package com.wpe.wpe.WebProcess;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

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
            Log.i("WPEWebProcess", "files dir " + context.getFilesDir());
            Log.i("WPEWebProcess", "cache dir " + context.getExternalCacheDir());
            Glue.initializeXdg(context.getCacheDir().getAbsolutePath());

            AssetManager assetManager = context.getAssets();

            InputStream is = assetManager.open("fontconfig/fonts.conf");

            File osDir = new File(context.getFilesDir(), "fonconfig");
            osDir.mkdirs();
            Glue.initializeFontconfig(osDir.getAbsolutePath());

            File osFile = new File(osDir, "fonts.conf");
            Log.i("WPEAssets", "copying fontconfig/fonts.conf to " + osFile.getAbsolutePath());
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
        //android.os.Debug.waitForDebugger();
        Log.i("WPEWebProcess", "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("WPEWebProcess", " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }

        Surface surface = null;

        try {
            synchronized (m_thread) {
                while (m_surface == null) {
                    m_thread.wait();
                }

                surface = m_surface;
                m_surface = null;
            }
        } catch (InterruptedException e) {
            Log.i("WPEWebProcess", "failed to get the Surface object");
        }

        {
            Log.i("WPEWebProcess", "about to start main(), surface " + surface);
            Glue.provideSurface(surface);
        }
        Glue.initializeMain(fds[0].detachFd(), /* fds[1].detachFd() */ -1);
    }

}