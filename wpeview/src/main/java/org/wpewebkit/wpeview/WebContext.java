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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WPEDisplay;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WebKitCookieManager;
import org.wpewebkit.wpe.WebKitNetworkSession;
import org.wpewebkit.wpe.WebKitWebContext;
import org.wpewebkit.wpe.WebKitWebView;
import org.wpewebkit.wpe.WebKitWebsiteDataManager;

/**
 * WebContext represents the shared engine and session state used by high-level {@link WebView} instances.
 * It manages the lifecycle of the web context, network session and cookies, and holds the defaults each new
 * WebView starts its own settings from. The display belongs to the process: every WebContext shares the one
 * {@link WPERuntime#getDisplay()} returns.
 */
public class WebContext {
    /**
     * Embedder hook for automation sessions. The WebDriver/automation path calls
     * {@link #createWebViewForAutomation()} when a remote client asks for a new browsing context.
     */
    public interface Client {
        @Nullable
        WebView createWebViewForAutomation();
    }

    private final WPEDisplay display;
    private final WebKitWebContext webContext;
    private final WebKitNetworkSession networkSession;
    private final WebKitCookieManager webKitCookieManager;
    private final WebKitWebsiteDataManager websiteDataManager;
    private final String defaultUserAgent;
    private final android.content.Context appContext;
    private CookieManager cookieManager;

    public WebContext(@NonNull android.content.Context context) { this(context, false); }

    public WebContext(@NonNull android.content.Context context, boolean automationMode) {
        this(context, automationMode, shouldUseEphemeralSession());
    }

    public WebContext(@NonNull android.content.Context context, boolean automationMode, boolean ephemeralSession) {
        this.appContext = context.getApplicationContext();
        WPERuntime.getInstance().initialize(appContext);

        this.display = WPERuntime.getInstance().getDisplay();
        this.webContext = new WebKitWebContext(automationMode);

        this.networkSession = new WebKitNetworkSession(webContext, automationMode, ephemeralSession,
                                                       appContext.getFilesDir().getAbsolutePath(),
                                                       appContext.getCacheDir().getAbsolutePath());
        this.webKitCookieManager = new WebKitCookieManager(networkSession);
        this.websiteDataManager = networkSession.getWebsiteDataManager();
        this.webKitCookieManager.setAcceptPolicy(WebKitCookieManager.WEBKIT_COOKIE_POLICY_ACCEPT_NO_THIRD_PARTY);

        this.defaultUserAgent = defaultMobileUserAgent();
    }

    /**
     * WPE's default User-Agent advertises a desktop platform ({@code X11; Linux aarch64}) because
     * {@code WTF::chassisType()} is never {@code Mobile} on Android (it relies on systemd hostnamed,
     * which is absent). Many sites use the User-Agent to decide whether to serve their mobile layout,
     * so without this they serve the desktop site &mdash; e.g. wide navigation bars that overflow a
     * phone-sized viewport and force horizontal scrolling.
     *
     * <p>Advertise an {@code Android} + {@code Mobile} User-Agent, mirroring what WPEPlatform would emit
     * on a mobile chassis. The {@code AppleWebKit/605.1.15} and {@code Version/60.5} tokens match the
     * fixed values WPE's own User-Agent uses (see {@code UserAgentGLib.cpp}), keeping us aligned with
     * the engine's Safari branding rather than impersonating Chrome. The Android version and device
     * model come from {@link android.os.Build}. Embedders can still override this via
     * {@link WebSettings#setUserAgentString(String)}.
     */
    private static String defaultMobileUserAgent() {
        return "Mozilla/5.0 (Linux; Android " + android.os.Build.VERSION.RELEASE + "; " + android.os.Build.MODEL +
            ") AppleWebKit/605.1.15 (KHTML, like Gecko) Version/60.5 Mobile Safari/605.1.15";
    }

    /**
     * Heuristic for picking the default {@code ephemeralSession} on emulators, which typically lack
     * hardware-backed persistent storage and crash when WebKit tries to create mmap-backed cache files
     * on emulated filesystems.
     *
     * <p>FIXME: emulator detection via {@code Build.PRODUCT}/{@code Build.HARDWARE} is fragile;
     * callers that know better should pass an explicit value to the 3-arg constructor.
     */
    public static boolean shouldUseEphemeralSession() {
        return android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("vbox") ||
            android.os.Build.HARDWARE.contains("goldfish") || android.os.Build.HARDWARE.contains("ranchu");
    }

    /**
     * Enable WebKit's remote inspector server. Must be called before any {@link WebContext} is created.
     * Delegates to {@link WPERuntime#enableRemoteInspector(int, boolean)}.
     */
    public static void enableRemoteInspector(int inspectorPort, boolean useHttpInspector) {
        WPERuntime.enableRemoteInspector(inspectorPort, useHttpInspector);
    }

    /**
     * Register an automation {@link Client}. Only meaningful when this context was constructed with
     * {@code automationMode=true}; the callback fires when WebDriver asks for a new browsing context.
     */
    public void setClient(@Nullable Client client) {
        webContext.setClient(client == null ? null : () -> {
            WebView view = client.createWebViewForAutomation();
            return view != null ? view.getInternalWebKitWebView() : null;
        });
    }

    public void destroy() {
        webKitCookieManager.destroy();
        networkSession.destroy();
        webContext.destroy();
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
    WebKitWebContext getWebKitWebContext() {
        return webContext;
    }
    @NonNull
    WebKitNetworkSession getWebKitNetworkSession() {
        return networkSession;
    }
    @NonNull
    String getDefaultUserAgent() {
        return defaultUserAgent;
    }
}
