package com.wpe.wpe.DatabaseProcess;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.wpe.wpe.WPEService;

public class Service extends WPEService {

    @Override
    public void onCreate()
    {
        super.onCreate();

        Context context = getBaseContext();
        Log.i("WPEDatabaseProcess", "files dir " + context.getFilesDir());
        Log.i("WPEDatabaseProcess", "cache dir " + context.getExternalCacheDir());
        Glue.initializeXdg(context.getCacheDir().getAbsolutePath());
    }

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {

        Log.i("WPEDatabaseProcess", "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("WPEDatabaseProcess", " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }
        Glue.initializeMain(fds[0].detachFd());
    }
}
