/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpeview;

import android.util.DisplayMetrics;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WKRuntime;
import org.wpewebkit.wpe.WPEDisplay;
import org.wpewebkit.wpe.WPEScreen;
import org.wpewebkit.wpe.WebKitCookieManager;
import org.wpewebkit.wpe.WebKitNetworkSession;
import org.wpewebkit.wpe.WebKitSettings;
import org.wpewebkit.wpe.WebKitWebContext;
import org.wpewebkit.wpe.WebKitWebsiteDataManager;

/**
 * WebContext represents the shared state of the WPE browser engine.
 * It manages the lifecycle of the Display, Web Context, and Network Session.
 */
public class WebContext {
    private final WPEDisplay display;
    private final WPEScreen screen;
    private final WebKitWebContext webContext;
    private final WebKitNetworkSession networkSession;
    private final WebKitCookieManager webKitCookieManager;
    private final WebKitWebsiteDataManager websiteDataManager;
    private final WebKitSettings settings;
    private final android.content.Context appContext;
    private final boolean owned;
    private CookieManager cookieManager;

    public WebContext(@NonNull android.content.Context context) { this(context, false); }

    public WebContext(@NonNull android.content.Context context, boolean automationMode) {
        this.appContext = context.getApplicationContext();
        WKRuntime.getInstance().initialize(appContext);

        this.display = new WPEDisplay();
        this.screen = display.getScreen();
        DisplayMetrics displayMetrics = appContext.getResources().getDisplayMetrics();
        this.screen.setScale(displayMetrics.density);
        this.webContext = new WebKitWebContext(automationMode);

        // Logic for ephemeral sessions (e.g., for emulators) stays here in Layer 1.
        boolean isEmulator = android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("vbox") ||
                             android.os.Build.HARDWARE.contains("goldfish") ||
                             android.os.Build.HARDWARE.contains("ranchu");

        this.networkSession =
            new WebKitNetworkSession(webContext, automationMode, isEmulator, appContext.getFilesDir().getAbsolutePath(),
                                     appContext.getCacheDir().getAbsolutePath());
        this.webKitCookieManager = new WebKitCookieManager(networkSession);
        this.websiteDataManager = networkSession.getWebsiteDataManager();
        this.webKitCookieManager.setAcceptPolicy(WebKitCookieManager.WEBKIT_COOKIE_POLICY_ACCEPT_NO_THIRD_PARTY);

        this.settings = new WebKitSettings();
        this.owned = true;
    }

    public void destroy() {
        if (owned) {
            settings.destroy();
            webKitCookieManager.destroy();
            networkSession.destroy();
            webContext.destroy();
            display.destroy();
        }
    }

    @NonNull
    public android.content.Context getApplicationContext() {
        return appContext;
    }

    @NonNull
    public CookieManager getCookieManager() {
        if (cookieManager == null) {
            cookieManager = new CookieManager(webKitCookieManager, websiteDataManager);
        }
        return cookieManager;
    }

    // Internal getters for WebView to access proxies.
    @NonNull
    WPEDisplay getWPEDisplay() {
        return display;
    }
    @NonNull
    WPEScreen getWPEScreen() {
        return screen;
    }
    @NonNull
    WebKitWebContext getWebKitWebContext() {
        return webContext;
    }
    @NonNull
    WebKitNetworkSession getWebKitNetworkSession() {
        return networkSession;
    }
    @NonNull
    WebKitSettings getWebKitSettings() {
        return settings;
    }
}
