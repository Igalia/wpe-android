package com.wpe.wpe.NetworkProcess;

public class Glue {

    static {
        System.loadLibrary("WPENetworkProcessGlue");
    }

    public static native void initializeGioExtraModulesPath(String extraModulesPath);
    public static native void initializeMain(int fd);
}
