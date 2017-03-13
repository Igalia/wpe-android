package com.wpe.wpe.NetworkProcess;

public class Glue {

    static {
        System.loadLibrary("WPE-backend");
        System.loadLibrary("WPEWebKit_0");
        System.loadLibrary("Glue");
    }

    public static native void initializeMain(int fd);
}
