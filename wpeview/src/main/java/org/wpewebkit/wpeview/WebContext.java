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

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WPEDisplay;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WebKitCookieManager;
import org.wpewebkit.wpe.WebKitNetworkSession;
import org.wpewebkit.wpe.WebKitWebContext;
import org.wpewebkit.wpe.WebKitWebView;
import org.wpewebkit.wpe.WebKitWebsiteDataManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * WebContext represents the shared engine and session state used by high-level {@link WebView} instances.
 * It manages the lifecycle of the web context, network session and cookies, and holds the defaults each new
 * WebView starts its own settings from. The display belongs to the process: every WebContext shares the one
 * {@link WPERuntime#getDisplay()} returns.
 */
public class WebContext {
    /**
     * Embedder hook for automation sessions. The WebDriver/automation path calls
     * {@link #createWebViewForAutomation()} when a remote client asks for a new browsing context, and
     * {@link #onAutomationSessionEnded()} when its session ends. The browsing context is the page that the returned
     * WebView shows: returning null, or a WebView that shows no page, opens none.
     */
    public interface Client {
        @Nullable
        WebView createWebViewForAutomation();

        /**
         * Called on the main thread once the WebDriver session has ended: WebDriver deleted it or disconnected, or the
         * network process ended. It comes after WebKit ended the session, to the client set then, and not once this
         * context is destroyed.
         * <p>
         * WebDriver leaves the pages of the session open, as it does with a browser that it did not start, and
         * WebDriver on Android connects to the running app: an app that does not keep them for a later session
         * destroys their WebViews here. Until then, the next session also finds them.
         */
        default void onAutomationSessionEnded() {}
    }

    private final WPEDisplay display;
    private final WebKitWebContext webContext;
    private final WebKitNetworkSession networkSession;
    private final WebKitCookieManager webKitCookieManager;
    private final WebKitWebsiteDataManager websiteDataManager;
    private final String defaultUserAgent;
    private final android.content.Context appContext;
    private CookieManager cookieManager;
    private final ContextLocation location;
    // The pages made with the context and not destroyed yet, which destroy() destroys first.
    private final ArrayList<WebPage> pages = new ArrayList<>();
    private boolean destroyed;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Whether the context ends when the last page made with it is destroyed, as the one that a WebView makes for itself
    // does, since the app did not make it, and its pages may outlive the view.
    boolean endsWithItsPages;

    public WebContext(@NonNull android.content.Context context) { this(context, false); }

    /**
     * Makes a context whose pages WebDriver controls if {@code automationMode} is true. WebDriver then answers the
     * JavaScript dialogs of these pages, and neither {@link WebChromeClient} nor {@link WebPageChromeClient} is asked
     * about them.
     */
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
        this.location = new ContextLocation(webContext, appContext);
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
     * {@code automationMode=true}; {@link Client#createWebViewForAutomation()} is called when WebDriver asks for a new
     * browsing context, and {@link Client#onAutomationSessionEnded()} when its session ends.
     */
    public void setClient(@Nullable Client client) {
        webContext.setClient(client == null ? null : new WebKitWebContext.Client() {
            @Override
            public @Nullable WebKitWebView createWebKitWebViewForAutomation() {
                WebView view = client.createWebViewForAutomation();
                return view != null ? view.getInternalWebKitWebView() : null;
            }

            @Override
            public void onAutomationSessionEnded() {
                client.onAutomationSessionEnded();
            }
        });
    }

    /**
     * Destroys the context, after the pages made with it that are not destroyed yet, including those of the WebViews
     * made with it. Does nothing if it is already destroyed.
     */
    public void destroy() {
        if (destroyed)
            return;
        // Its pages are destroyed before anything else, and those that the app made, on the main thread.
        for (WebPage page : pages) {
            if (!page.madeByView)
                WebPage.checkMainThread();
        }
        destroyed = true;
        while (!pages.isEmpty())
            pages.get(pages.size() - 1).destroy();
        location.destroy();
        webKitCookieManager.destroy();
        networkSession.destroy();
        webContext.destroy();
    }

    boolean isDestroyed() { return destroyed; }

    // Called by each page when it is made, and when it is destroyed.
    void addPage(@NonNull WebPage page) { pages.add(page); }

    void removePage(@NonNull WebPage page) { pages.remove(page); }

    boolean hasPages() { return !pages.isEmpty(); }

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

    /**
     * Clears the data of the given {@code types} that WebKit keeps for every site that the pages of this context
     * loaded, and gives {@code callback} whether it is cleared, on the main thread, never before this returns. For
     * example, a browser clears {@link WebsiteDataType#Cache} when the user asks to clear the cached images and files,
     * and every type to clear all the data of the sites. The callback is not called if the context is destroyed first.
     *
     * <p>This does not clear the icons of pages nor what tracking prevention noted about sites, which are not website
     * data: {@link #clearFavicons} and {@link #clearTrackingPreventionData} clear them. A browser that clears all the
     * browsing data calls all three.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void clearWebsiteData(@NonNull Set<WebsiteDataType> types, @Nullable WPECallback<Boolean> callback) {
        checkUsable();
        websiteDataManager.clear(WebsiteDataType.toWebKit(types), whileAlive(callback));
    }

    /**
     * Clears the data of the given {@code types} that WebKit keeps for the site of {@code url}, as
     * {@link #clearWebsiteData} does for every site, for example when the user asks to clear the data of the site of
     * the page shown. Other sites keep theirs. The site is what WebKit files the data under: the registrable domain of
     * the host, for both http and https. For {@code https://www.example.com/page} it is {@code example.com}, so the
     * data of {@code mail.example.com} goes too. The site of an IPv4 address or of {@code localhost} is the host alone.
     *
     * <p>The cache goes by the address of each resource, not by the page that loaded it: clearing the cache of a site
     * clears the copies of its resources that pages of other sites loaded, and keeps those of the other sites, as
     * images from another domain, that its pages loaded. The pages of the site that are open keep running, and may
     * store data again.
     *
     * <p>WebKit keeps the data of some hosts under no site: an IPv6 address, a public suffix itself, and a host of a
     * single label other than {@code localhost}, such as {@code http://intranet/}. This clears nothing for them, and
     * {@code callback} gets false: only {@link #clearWebsiteData} clears their data.
     *
     * @throws IllegalArgumentException if {@code url} is not an http or https address with a host.
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void clearWebsiteDataForSite(@NonNull String url, @NonNull Set<WebsiteDataType> types,
                                        @Nullable WPECallback<Boolean> callback) {
        checkUsable();
        if (!isWebAddress(Uri.parse(url)))
            throw new IllegalArgumentException("Not an http or https address: " + url);
        websiteDataManager.removeForSite(WebsiteDataType.toWebKit(types), getWebsiteDataSite(url),
                                         whileAlive(callback));
    }

    /**
     * The site that WebKit files the website data of {@code url} under, which {@link #clearWebsiteDataForSite} clears:
     * {@code example.com} for {@code https://www.example.com/page}, or the host itself for an IPv4 address or
     * {@code localhost}. An app can name it, for example in a button that clears the data of the site of the page
     * shown. An international name comes in its ASCII form, as {@code xn--mnchen-3ya.example}, and lowercase.
     *
     * <p>It is empty for an address that is not http or https. It is empty as well for a host that WebKit files under
     * no site, such as an IPv6 address or {@code http://intranet/}: {@link #clearWebsiteDataForSite} cannot clear the
     * data of such a host, and only {@link #clearWebsiteData} does.
     */
    @NonNull
    public static String getWebsiteDataSite(@NonNull String url) {
        if (!isWebAddress(Uri.parse(url)))
            return "";
        return WebKitNetworkSession.getWebsiteDataName(TrackingPrevention.hostOf(url));
    }

    // The callback for an answer of WebKit, which the app gets unless the context is destroyed first.
    @Nullable
    private WebKitWebsiteDataManager.Callback whileAlive(@Nullable WPECallback<Boolean> callback) {
        if (callback == null)
            return null;
        return result -> {
            if (!destroyed)
                callback.onResult(result);
        };
    }

    /**
     * Turns tracking prevention on or off for the pages of this context. It is off unless turned on. While it is on,
     * WebKit keeps sites from following the user across the sites that embed them:
     * <ul>
     * <li>A request that a page makes to another site, as for a frame, an image or a script of that site, sends none
     * of that site's cookies and sets none, and a frame of another site sees none, whatever their {@code SameSite}
     * attribute says. Frames cannot get them back with the Storage Access API yet: WebKit would ask the app, which
     * WPEView does not pass on, so their requests are refused.</li>
     * <li>A cookie that a script sets, with {@code document.cookie}, lasts 7 days at most.</li>
     * <li>WebKit notes which sites load resources from, or send the user to, which others, and tells the trackers among
     * them. It deletes the website data of a tracker, and the data that scripts keep for any other site, as local
     * storage and databases, unless the user tapped or typed in a page of that site in the last 30 days that the
     * browser was used.</li>
     * </ul>
     * A change applies at once, to the pages already open too: their next requests and scripts follow it.
     *
     * <p>While it is on, it decides about the cookies of other sites itself: a cookie policy of
     * {@link CookieManager.CookieAcceptPolicy#AcceptNoThirdParty}, the default, then counts as
     * {@link CookieManager.CookieAcceptPolicy#AcceptAlways}, and {@link CookieManager#getCookieAcceptPolicy} reports
     * it so. The policy that was set comes back once it is off.
     *
     * <p>A context whose session keeps its data keeps what WebKit notes about sites beside its cookies, in the folder
     * {@code itp} of the app's files folder, for the next run: {@link #getTrackingPreventionSummary} lists it, and
     * {@link #clearTrackingPreventionData} clears it. An ephemeral session, as the one of a context in automation mode,
     * notes no sites, so it tells no trackers apart and deletes no data, but it keeps the cookies of other sites from
     * them all the same. It keeps the Storage Access that frames are given in memory, and it ends with the context.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void setTrackingPreventionEnabled(boolean enabled) {
        checkUsable();
        networkSession.setITPEnabled(enabled);
    }

    /**
     * Whether tracking prevention is on for the pages of this context. See {@link #setTrackingPreventionEnabled}.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public boolean isTrackingPreventionEnabled() {
        checkUsable();
        return networkSession.isITPEnabled();
    }

    /**
     * Whether the session of this context is ephemeral: it keeps cookies, cache and website data in memory only, and
     * they end with the context. That of a context in automation mode always is.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public boolean isEphemeralSession() {
        checkUsable();
        return networkSession.isEphemeral();
    }

    /**
     * Gives {@code callback} the other sites that tracking prevention saw in the pages of each site of this context:
     * those that the pages loaded frames, images or scripts from, with the sites they were seen on. It adds up the
     * visits of earlier runs too, for a session that keeps its data, until {@link #clearTrackingPreventionData}. The
     * list is empty while tracking prevention is off, and the sites noted before come back once it is on again. It is
     * always empty for an ephemeral session, which notes no sites: see {@link #isEphemeralSession}.
     *
     * <p>The callback is called once, on the main thread, never before this returns, with what the pages loaded up to
     * this call. It is not called if the context is destroyed first. The order of the list is not defined.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void getTrackingPreventionSummary(@NonNull WPECallback<List<TrackingPrevention.ThirdParty>> callback) {
        checkUsable();
        networkSession.getITPSummary(thirdParties -> {
            if (!destroyed)
                callback.onResult(TrackingPrevention.fromWebKit(thirdParties));
        });
    }

    /**
     * Gives {@code callback} the site of {@code url} and the other sites that tracking prevention saw in its pages,
     * as {@link #getTrackingPreventionSummary} does for all the sites. The site is what WebKit files the pages of the
     * address's host under: see {@link TrackingPrevention}. Usually {@code url} is the address of the page shown, and
     * the result covers all the visits to its site, not that page alone. WebKit also lists a site that redirected a
     * request to this one, even if its pages did not load it.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void getTrackingPreventionSiteSummary(@NonNull String url,
                                                 @NonNull WPECallback<TrackingPrevention.SiteSummary> callback) {
        checkUsable();
        String site = TrackingPrevention.siteOf(url);
        networkSession.getITPSummary(thirdParties -> {
            if (!destroyed)
                callback.onResult(TrackingPrevention.siteSummary(site, thirdParties));
        });
    }

    /**
     * Clears what tracking prevention noted about sites, which {@link #getTrackingPreventionSummary} lists, and gives
     * {@code callback} whether they are cleared, on the main thread, never before this returns. WebKit clears them only
     * while tracking prevention is on: while it is off, a session that keeps its data keeps them for when it is on
     * again, and {@code callback} gets false. The callback is not called if the context is destroyed first.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void clearTrackingPreventionData(@Nullable WPECallback<Boolean> callback) {
        checkUsable();
        // WebKit would say that it cleared them.
        if (!networkSession.isITPEnabled() && !networkSession.isEphemeral()) {
            if (callback != null)
                mainHandler.post(() -> {
                    if (!destroyed)
                        callback.onResult(false);
                });
            return;
        }
        websiteDataManager.clear(EnumSet.of(WebKitWebsiteDataManager.WebsiteDataType.ITP), cleared -> {
            if (callback != null && !destroyed)
                callback.onResult(cleared);
        });
    }

    /**
     * Clears the icons that the pages of this context loaded. WebKit keeps them for the next loads of the same pages,
     * which then show their icon from the start: see {@link WebPage#getFavicon}. With them, it keeps the address of
     * each page that had an icon, which tells what the user visited, so an app that clears its browsing history clears
     * these too. The pages that are open keep their icon, and the icons of the pages that each showed last, for going
     * back to them, and pages load theirs again as they load.
     *
     * <p>A session that keeps its data keeps them in WebKit's icon database, a file in the folder {@code icondatabase}
     * of the app's cache folder: Android may delete it to make room, and leaves it out of backups. WebKit itself
     * deletes an icon that no page loaded in 30 days, with the addresses of its pages. All the contexts of the app that
     * keep their data share that file, and this clears it for all of them, but another context that runs keeps in
     * memory the addresses that it read when it was made, and gets no icon for those pages until it is made again: an
     * app that clears its icons is best served by one context that keeps its data, rather than, for example, several
     * WebViews made without a context, which make one each. An ephemeral session keeps the icons in memory only, and
     * only for a short while once they were last used, and a context in automation mode keeps none.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void clearFavicons() {
        checkUsable();
        websiteDataManager.clearFavicons();
    }

    /**
     * Sets where the pages of this context get the location of the device from, once the app grants them
     * {@link WPEPermissionRequest#RESOURCE_LOCATION}, or with null, Android's location services, the default, as
     * {@link WPELocationSource} tells. A source that runs is stopped, and the new one starts in its place.
     *
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void setLocationSource(@Nullable WPELocationSource source) {
        checkUsable();
        location.setSource(source);
    }

    /**
     * Sets the origins whose pages may show notifications, and those whose pages may not, as the app answered their
     * requests for {@link WPEPermissionRequest#RESOURCE_NOTIFICATIONS}: these lists replace the ones set before. Each
     * web process of this context that starts from now on takes them, for {@code Notification.permission} and
     * {@code new Notification()} in its pages, which do not ask the app; a refused origin reads "default" there until
     * its page asks. The web processes that run keep what they had, so the app sets the lists before the first page
     * loads, and again after each change. Every web process of the context gets the whole lists.
     *
     * <p>An origin is given as {@link WPEPermissionRequest#getOrigin()} gives it; any address of the origin will do.
     *
     * @throws IllegalArgumentException if an address is not http or https, or has no host.
     * @throws IllegalStateException if called on a thread other than the main one, or once the context is destroyed.
     */
    public void setNotificationPermissions(@NonNull Collection<Uri> allowed, @NonNull Collection<Uri> denied) {
        checkUsable();
        webContext.setNotificationPermissions(notificationOrigins(allowed), notificationOrigins(denied));
    }

    @NonNull
    private static String[] notificationOrigins(@NonNull Collection<Uri> origins) {
        String[] addresses = new String[origins.size()];
        int i = 0;
        for (Uri origin : origins) {
            if (!isWebAddress(origin))
                throw new IllegalArgumentException("Not the origin of an http or https page: " + origin);
            addresses[i++] = origin.toString();
        }
        return addresses;
    }

    // Whether the address is an http or https one with a host.
    private static boolean isWebAddress(@NonNull Uri address) {
        String scheme = address.getScheme();
        String host = address.getHost();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) &&
            host != null && !host.isEmpty();
    }

    private void checkUsable() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("A WebContext must be used on the main thread");
        if (destroyed)
            throw new IllegalStateException("The WebContext is destroyed");
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
