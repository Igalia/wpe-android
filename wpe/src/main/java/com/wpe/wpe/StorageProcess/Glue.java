package com.wpe.wpe.StorageProcess;

public class Glue {

    static {
        System.loadLibrary("WPEStorageProcessGlue");
    }

    public static native void initializeXdg(String xdgCachePath);
    public static native void initializeMain(int fd);
}
