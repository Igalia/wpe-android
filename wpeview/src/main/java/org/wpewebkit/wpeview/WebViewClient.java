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

import android.net.http.SslError;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WebKitWebView;

/**
 * WebViewClient provides the high-level navigation and resource callbacks for {@link WebView}.
 */
public class WebViewClient {
    // The error codes of onReceivedError, those of android.webkit.WebViewClient.

    /** Generic error. */
    public static final int ERROR_UNKNOWN = android.webkit.WebViewClient.ERROR_UNKNOWN;
    /** Server or proxy hostname lookup failed. */
    public static final int ERROR_HOST_LOOKUP = android.webkit.WebViewClient.ERROR_HOST_LOOKUP;
    /** Failed to connect to the server. */
    public static final int ERROR_CONNECT = android.webkit.WebViewClient.ERROR_CONNECT;
    /** Failed to read or write to the server. */
    public static final int ERROR_IO = android.webkit.WebViewClient.ERROR_IO;
    /** Connection timed out. */
    public static final int ERROR_TIMEOUT = android.webkit.WebViewClient.ERROR_TIMEOUT;
    /** Too many redirects. */
    public static final int ERROR_REDIRECT_LOOP = android.webkit.WebViewClient.ERROR_REDIRECT_LOOP;
    /** Unsupported URI scheme. */
    public static final int ERROR_UNSUPPORTED_SCHEME = android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME;
    /** Failed to perform SSL handshake. */
    public static final int ERROR_FAILED_SSL_HANDSHAKE = android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE;
    /** File not found. */
    public static final int ERROR_FILE_NOT_FOUND = android.webkit.WebViewClient.ERROR_FILE_NOT_FOUND;
    /**
     * Android blocked the connection to the local network, because the app does not have the local network permission
     * ({@code android.permission.ACCESS_LOCAL_NETWORK}, shown as "Nearby devices", Android 17 and later). Not in
     * android.webkit.WebViewClient. The app can request the permission, which it must declare in its manifest, and load
     * the page again once it is granted. Only pages report it: a blocked subresource just fails to load.
     */
    public static final int ERROR_LOCAL_NETWORK_ACCESS_DENIED = -100;

    // The reasons of onWebProcessTerminated.

    /**
     * The web process crashed, or Android ended it, as it does to free memory, for example while the app is in the
     * background.
     */
    public static final int WEB_PROCESS_CRASHED = WebKitWebView.WEB_PROCESS_CRASHED;
    /** WebKit ended the web process, because it used more memory than WebKit allows. */
    public static final int WEB_PROCESS_EXCEEDED_MEMORY_LIMIT = WebKitWebView.WEB_PROCESS_EXCEEDED_MEMORY_LIMIT;
    /** The app ended the web process with {@link WebView#terminateWebProcess()}. */
    public static final int WEB_PROCESS_TERMINATED_BY_API = WebKitWebView.WEB_PROCESS_TERMINATED_BY_API;

    /**
     * Give the host application a chance to take over a navigation before it starts, mirroring
     * {@code android.webkit.WebViewClient.shouldOverrideUrlLoading}. Called for the navigations of the page and of its
     * frames, such as a link, a form or a script that changes the address, also when they follow a redirect from the
     * server, and for a link or a script that opens a new window, before the window is created. Not called for the
     * navigations the app starts itself, with {@link WebView#loadUrl}, {@link WebView#loadHtml},
     * {@link WebView#reload}, {@link WebView#goBack} or {@link WebView#goForward}, nor for POST requests.
     *
     * <p>The WebView loads http, https, file, data, blob and about addresses. Others, such as {@code mailto:} or
     * {@code intent:}, fail to load with {@link #ERROR_UNSUPPORTED_SCHEME} unless the app takes them, for example to
     * open them with another app. {@link WPEResourceRequest#hasGesture()} tells whether the user asked for the
     * navigation, or whether the page started it by itself.
     *
     * @param view The WebView that is initiating the callback.
     * @param request The request of the navigation.
     * @return {@code true} to cancel the navigation, which the app then takes care of, or {@code false} to let the
     *     WebView go on with it.
     */
    public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WPEResourceRequest request) {
        return false;
    }

    /**
     * Notify the host application that a page has started loading.
     * @param view The WebView that is initiating the callback.
     * @param url The url to be loaded.
     */
    public void onPageStarted(@NonNull WebView view, @NonNull String url) {}

    /**
     * Notify the host application that a page has finished loading.
     * @param view The WebView that is initiating the callback.
     * @param url The url of the page.
     */
    public void onPageFinished(@NonNull WebView view, @NonNull String url) {}

    /**
     * Notify the host application that the current page's visited URL has been updated.
     * Mirrors {@code android.webkit.WebViewClient.doUpdateVisitedHistory}. Called once for each navigation that is
     * done: when a page commits, also for a reload, or when a navigation within the page changes the address or the
     * Back/Forward state. {@link WebView#canGoBack()} and {@link WebView#canGoForward()} then return the new state.
     * @param view The WebView that is initiating the callback.
     * @param url The URL of the visited page.
     * @param isReload True if this URL was reached as the result of a reload.
     */
    // FIXME: plumb the isReload flag through WebKitWebView.Listener; currently always false.
    public void doUpdateVisitedHistory(@NonNull WebView view, @NonNull String url, boolean isReload) {}

    /**
     * Notify the host application that the internal SurfaceView has been created
     * and is ready to render to its surface.
     * @param view The WebView that is initiating the callback.
     */
    public void onViewReady(@NonNull WebView view) {}

    /**
     * Notify the host application that an HTTP error has been received from the server while
     * loading a resource. HTTP errors have status codes >= 400.
     * @param view The WebView that is initiating the callback.
     * @param request The originating request.
     * @param errorResponse Information about the error occurred.
     */
    public void onReceivedHttpError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                    @NonNull WPEResourceResponse errorResponse) {}

    /**
     * Notify the host application that the page in the main frame failed to load, for example because the server
     * could not be reached. Loads that are cancelled, for example by loading another page, are not reported. The load
     * then ends with {@link #onPageFinished} for the address that failed, and WebKit shows its error page, which comes
     * with its own {@link #onPageStarted} and {@link #onPageFinished}.
     * @param view The WebView that is initiating the callback.
     * @param request The request of the page that failed to load.
     * @param error Why it failed: an {@code ERROR_*} code and a description.
     */
    public void onReceivedError(@NonNull WebView view, @NonNull WPEResourceRequest request,
                                @NonNull WPEResourceError error) {}

    /**
     * Notify the host application that the web process that ran the page ended. The page is gone: the view keeps
     * showing its last picture, which no longer changes, and a load in progress ends without {@link #onPageFinished}.
     * The view keeps the address, the title and the Back/Forward list of the page, and stays usable:
     * {@link WebView#reload()} loads the page again in a new web process, as any other load or navigation does. The app
     * may show a message with a way to reload, or reload a page that the user did not see stop. Unlike
     * {@code android.webkit.WebViewClient.onRenderProcessGone}, nothing needs to be returned, and the view does not
     * need to be destroyed.
     * @param view The WebView that is initiating the callback.
     * @param reason Why the web process ended: {@link #WEB_PROCESS_CRASHED},
     *     {@link #WEB_PROCESS_EXCEEDED_MEMORY_LIMIT} or {@link #WEB_PROCESS_TERMINATED_BY_API}.
     */
    public void onWebProcessTerminated(@NonNull WebView view, int reason) {}

    /**
     * The interface used to accept or reject an invalid SSL certificate.
     */
    public interface SslErrorHandler {
        /** Call this method to accept an invalid SSL certificate. */
        void proceed();

        /** Call this method to reject an invalid SSL certificate. */
        void cancel();
    }

    /**
     * Notify the host application that an HTTPS resource failed to load due to SSL errors.
     * @param view The WebView that is initiating the callback.
     * @param handler The host application must call either proceed() or cancel().
     * @param error The SSL error(s) which happened.
     */
    // FIXME: wire webkit_web_view tls-errors signal to invoke this callback.
    public void onReceivedSslError(@NonNull WebView view, @NonNull SslErrorHandler handler, @NonNull SslError error) {}
}
