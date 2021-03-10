package com.wpe.wpe.services.webprocess;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import com.wpe.wpe.services.WPEService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Service extends WPEService {
    private static final String LOGTAG = "WPEWebProcess";

    @Override
    public void onCreate()
    {
        super.onCreate();

        try {
            Context context = getBaseContext();
            Glue.initializeXdg(context.getCacheDir().getAbsolutePath());

            AssetManager assetManager = context.getAssets();

            InputStream is = assetManager.open("fontconfig/fonts.conf");

            File osDir = new File(context.getFilesDir(), "fontconfig");
            osDir.mkdirs();
            Glue.initializeFontconfig(osDir.getAbsolutePath());

            File osFile = new File(osDir, "fonts.conf");
            Log.v(LOGTAG, "Copying fontconfig/fonts.conf to " + osFile.getAbsolutePath());
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
            Log.e(LOGTAG, "Asset load failed: " + e);
        }
    }

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {
        Log.v(LOGTAG, "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.v(LOGTAG, " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }

        Surface surface = null;

        try {
            synchronized (m_serviceProcessThread) {
                while (m_serviceProcessThread.m_surface == null) {
                    m_serviceProcessThread.wait();
                }

                surface = m_serviceProcessThread.m_surface;
                m_serviceProcessThread.m_surface = null;
            }
        } catch (InterruptedException e) {
            Log.i(LOGTAG, "failed to get the Surface object");
        }

        Log.i(LOGTAG, "about to start main(), surface " + surface);
        Glue.provideSurface(surface);
        Glue.initializeMain(fds[0].detachFd(), /* fds[1].detachFd() */ -1);
    }

}