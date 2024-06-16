package com.wpe.wpe;

import androidx.annotation.NonNull;

public class WKNetworkSession {

    private WKCookieManager cookieManager;

    private WKWebsiteDataManager websiteDataManager;

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    WKNetworkSession(WKWebContext webContext, boolean isAutomationMode, String dataDir, String cacheDir) {
        nativePtr = nativeInit(webContext.getNativePtr(), isAutomationMode, dataDir, cacheDir);
    }

    void destroy() {
        if (nativePtr != 0)
            nativeDestroy(nativePtr);
    }

    public @NonNull WKCookieManager getCookieManager() {
        if (cookieManager == null)
            cookieManager = new WKCookieManager(nativeCookieManager(nativePtr));
        return cookieManager;
    }

    public @NonNull WKWebsiteDataManager getWebsiteDataManager() {
        if (websiteDataManager == null)
            websiteDataManager = new WKWebsiteDataManager(nativeWebsiteDataManager(nativePtr));
        return websiteDataManager;
    }

    private native long nativeInit(long webContextNativePtr, boolean isAutomationMode, @NonNull String dataDir,
                                   @NonNull String cacheDir);
    private native void nativeDestroy(long nativePtr);
    private native long nativeCookieManager(long nativePtr);
    private native long nativeWebsiteDataManager(long nativePtr);
}
