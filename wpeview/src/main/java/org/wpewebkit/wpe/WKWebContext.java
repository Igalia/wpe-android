package org.wpewebkit.wpe;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class WKWebContext {

    public interface Client {
        public @Nullable WKWebView createWKWebViewForAutomation();
    }

    private static final String LOGTAG = "WKWebContext";

    private final Context context;

    protected long nativePtr = 0;
    public long getNativePtr() { return nativePtr; }

    private Client client;

    private final WKNetworkSession networkSession;

    public WKWebContext(@NonNull Context context, boolean automationMode) {
        this.context = context;

        WKRuntime.getInstance().initialize(context);

        nativePtr = nativeInit(automationMode);

        networkSession = new WKNetworkSession(this, automationMode, context.getDataDir().getAbsolutePath(),
                                              context.getCacheDir().getAbsolutePath());

        networkSession.getCookieManager().setCookieAcceptPolicy(WKCookieManager.CookieAcceptPolicy.AcceptNoThirdParty);
    }

    public void destroy() {
        if (nativePtr != 0) {
            networkSession.destroy();
            nativeDestroy(nativePtr);
        }
    }

    public @NonNull Context getApplicationContext() { return context.getApplicationContext(); }

    public @NonNull WKNetworkSession getNetworkSession() { return networkSession; }

    public void setClient(@Nullable Client client) { this.client = client; }

    @Keep
    private long createWKWebViewForAutomation() {
        if (client != null) {
            WKWebView wkWebView = client.createWKWebViewForAutomation();
            if (wkWebView != null) {
                return wkWebView.getNativePtr();
            }
        }
        return 0;
    }

    private native long nativeInit(boolean automationMode);

    private native void nativeDestroy(long nativePtr);
}
