package com.wpe.wpe.WebProcess;

public class Glue {

    static {
        System.loadLibrary("WPE-backend");
        System.loadLibrary("Glue");
    }

    public static native void initializeXdg(String xdgCachePath);
    public static native void initializeFontconfig(String fontconfigPath);
    public static native void initializeMain(int fd1, int fd2);
}
