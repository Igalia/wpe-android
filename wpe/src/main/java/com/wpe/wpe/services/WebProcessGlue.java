package com.wpe.wpe.services;

public class WebProcessGlue {

    static {
        System.loadLibrary("WPEBackend-default");
        System.loadLibrary("WPEWebProcessGlue");
    }

    public static native void initializeMain(int fd1, int fd2);
    public static native void setupEnvironment(String fontconfigPath, String gstreamerPath,
                                               String nativeLibsPath, String cachePath,
                                               String filesPath);
}
