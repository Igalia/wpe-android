package com.wpe.wpedemo;

import android.util.Log;

public class WPEUIProcessGlue {

    static {
        System.loadLibrary("WPE-backend");
        System.loadLibrary("WPEUIProcessGlue");
    }

    public static native void init(WPEUIProcessGlue glueObj);
    public static native void deinit();

    public void launchProcess(int processType)
    {
        Log.i("WPEUIProcessGlue", "launchProcess()");
        switch (processType) {
            case 0:
                Log.i("WPEUIProcessGlue", "should launch WebProcess");
                break;
            case 1:
                Log.i("WPEUIProcessGlue", "should launch NetworkProcess");
                break;
            case 2:
                Log.i("WPEUIProcessGlue", "should launch DatabaseProcess");
                break;
            default:
                Log.i("WPEUIProcessGlue", "invalid process type");
                break;
        }
    }
}
