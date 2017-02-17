package com.wpe.wpedemo;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.wpe.wpedemo.IWPEService;

public class WPEService extends Service {

    public final IWPEService.Stub m_binder = new IWPEService.Stub() {
        @Override
        public int connect(Bundle args)
        {
            Log.i("WPEService", "IWPEService.Stub connect()");
            return -1;
        }
    };

    @Override public void onCreate()
    {
        Log.i("WPEService", "onCreate()");
    }

    @Override public IBinder onBind(Intent intent)
    {
        Log.i("WPEService", "onBind()");
        return m_binder;
    }

    @Override public void onDestroy()
    {
        Log.i("WPEService", "onDestroy()");
    }
}
