package org.wpewebkit.wpeview;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WKWebContext;

public class WPEContext {

    public interface Client {
        public @Nullable WPEView createWPEViewForAutomation();
    }

    private final WKWebContext context;

    private WPECookieManager cookieManager;

    private WPEContext.Client client;

    public WPEContext(@NonNull Context context) { this(context, false); }

    public WPEContext(@NonNull Context context, boolean automationMode) {
        this.context = new WKWebContext(context, automationMode);
    }

    public void destroy() { context.destroy(); }

    public @NonNull Context getApplicationContext() { return context.getApplicationContext(); }
    public @NonNull WPECookieManager getCookieManager() {
        if (cookieManager == null)
            cookieManager = new WPECookieManager(this.context.getNetworkSession());
        return cookieManager;
    }

    public void setClient(@Nullable WPEContext.Client client) {
        this.client = client;

        if (client != null) {
            this.context.setClient(() -> {
                WPEView view = this.client.createWPEViewForAutomation();
                if (view != null)
                    return view.getWKWebView();
                return null;
            });
        } else {
            this.context.setClient(null);
        }
    }
    WKWebContext getWebContext() { return context; }
}
