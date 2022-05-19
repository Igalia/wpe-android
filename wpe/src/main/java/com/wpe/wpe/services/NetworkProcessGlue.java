package com.wpe.wpe.services;

public class NetworkProcessGlue
{

    static {
        System.loadLibrary("WPENetworkProcessGlue");
    }

    public static native void initializeMain(int fd);
    public static native void setupEnvironment(String cachePath, String extraModulesPath);
}
