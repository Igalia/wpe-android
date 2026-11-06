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

    /**
     * The interface used to answer a request for HTTP authentication. Only the first answer counts. None counts once
     * the request is gone: when its load ended or another one started, as when the page goes elsewhere, or when the
     * view was destroyed.
     */
    public interface HttpAuthHandler {
        /**
         * Sends the credentials. A server that does not accept them asks again, with {@link #isRetry()}.
         * @param username The user name.
         * @param password The password.
         */
        void proceed(@NonNull String username, @NonNull String password);

        /**
         * Goes on without credentials, so that the page shows what the server sent with its request for them, such as
         * its 401 page.
         */
        void cancel();

        /** Whether a proxy asks for the credentials, rather than the site. The host is then the proxy's. */
        boolean isProxy();

        /** Whether the server did not accept the credentials it was given for this load, and asks again. */
        boolean isRetry();

        /**
         * The scheme of the connection that the credentials would go over: "https" when it is encrypted, "http" when it
         * is not.
         */
        @NonNull
        String getScheme();

        /** The port of the server that asks. */
        int getPort();
    }

    /**
     * Notify the host application that a server or a proxy asks for a user name and password with HTTP authentication,
     * such as Basic or Digest. The load waits until the handler is answered. The default answers with
     * {@link HttpAuthHandler#cancel()}, so that the page shows what the server sent, such as its 401 page.
     * @param view The WebView that is initiating the callback.
     * @param handler The host application must call either proceed() or cancel().
     * @param host The host of the server that asks.
     * @param realm The realm that the server gives, which tells what the credentials are for. It may be empty.
     */
    public void onReceivedHttpAuthRequest(@NonNull WebView view, @NonNull HttpAuthHandler handler, @NonNull String host,
                                          @NonNull String realm) {
        handler.cancel();
    }

    /**
     * Notify the host application that a request for HTTP authentication is gone before it was answered, because its
     * load ended or another one started, as when the page goes elsewhere. Any dialog for it should be closed: an answer
     * does nothing now. Not called for the requests of a view that is destroyed.
     * @param view The WebView that is initiating the callback.
     * @param handler The handler that {@link #onReceivedHttpAuthRequest} was given.
     */
    public void onHttpAuthRequestCanceled(@NonNull WebView view, @NonNull HttpAuthHandler handler) {}
}
