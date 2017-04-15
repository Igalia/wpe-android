package com.wpe.wpe.DatabaseProcess;

public class Glue {

    static {
        System.loadLibrary("WPEDatabaseProcessGlue");
    }

    public static native void initializeXdg(String xdgCachePath);
    public static native void initializeMain(int fd);
}
