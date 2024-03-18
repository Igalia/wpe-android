package com.wpe.wpe;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class WKWebContext {

    public interface Client {
        public @Nullable Page createPageForAutomation();
    }

    private static final String LOGTAG = "WKWebContext";

    private final Context context;

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    private Client client;

    private final WKWebsiteDataManager websiteDataManager;

    public WKWebContext(@NonNull Context context, int inspectorPort, boolean automationMode) {
        this.context = context;

        Browser.getInstance().initialize(context, inspectorPort);

        websiteDataManager = new WKWebsiteDataManager(automationMode, context.getDataDir().getAbsolutePath(),
                                                      context.getCacheDir().getAbsolutePath());

        nativePtr = nativeInit(websiteDataManager.getNativePtr(), automationMode);

        websiteDataManager.getCookieManager().setCookieAcceptPolicy(
            WKCookieManager.CookieAcceptPolicy.AcceptNoThirdParty);
    }

    public void destroy() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            websiteDataManager.destroy();
        }
    }

    public @NonNull Context getApplicationContext() { return context.getApplicationContext(); }

    public @NonNull WKWebsiteDataManager getWebsiteDataManager() { return websiteDataManager; }

    public void setClient(@Nullable Client client) { this.client = client; }

    @Keep
    private long createPageForAutomation() {
        if (client != null) {
            Page page = client.createPageForAutomation();
            if (page != null) {
                return page.getNativePtr();
            }
        }
        return 0;
    }

    private native long nativeInit(long nativeWebsiteDataManagerPtr, boolean automationMode);

    private native void nativeDestroy(long nativePtr);
}
