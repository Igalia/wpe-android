package com.wpe.wpe.StorageProcess;

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
        Log.i("WPEStorageProcess", "files dir " + context.getFilesDir());
        Log.i("WPEStorageProcess", "cache dir " + context.getExternalCacheDir());
        Glue.initializeXdg(context.getCacheDir().getAbsolutePath());
    }

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {

        Log.i("WPEStorageProcess", "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("WPEStorageProcess", " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }
        Glue.initializeMain(fds[0].detachFd());
    }
}
