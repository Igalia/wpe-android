package com.wpe.wpe.services;

public final class WebProcessGlue
{
    static {
        // To debug the sub-process with Android Studio (Java and native code), you must:
        // 1- Uncomment the following instruction to wait for the debugger when class is loaded.
        // 2- Force the dual debugger (Java + Native) in Run/Debug configuration (the automatic detection won't work).
        // 3- Launch the application (:tools:minibrowser for example).
        // 4- Click on "Attach Debugger to Android Process" and select this service process from the list.

        // android.os.Debug.waitForDebugger();

        System.loadLibrary("WPEBackend-default");
        System.loadLibrary("WPEWebProcessGlue");
    }

    public static native void setupEnvironment(String[] envStringsArray);
    public static native void initializeMain(int processType, int fd);
}
