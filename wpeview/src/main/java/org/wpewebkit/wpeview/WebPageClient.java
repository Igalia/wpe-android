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

import org.wpewebkit.wpe.WebKitWebView;

/**
 * Receives the navigation, load and network events of a {@link WebPage}, set with {@link WebPage#setClient}. The
 * page may have no view, so each method gets the page, not a {@link WebView}. Each one works as the
 * {@link WebViewClient} method of the same name, which a view gets for the page it made itself. The error codes are
 * the constants of {@link WPEResourceError}.
 */
public class WebPageClient {
    // The reasons of onWebProcessTerminated.

    /**
     * The web process crashed, or Android ended it, as it does to free memory, for example while the app is in the
     * background.
     */
    public static final int WEB_PROCESS_CRASHED = WebKitWebView.WEB_PROCESS_CRASHED;
    /** WebKit ended the web process, because it used more memory than WebKit allows. */
    public static final int WEB_PROCESS_EXCEEDED_MEMORY_LIMIT = WebKitWebView.WEB_PROCESS_EXCEEDED_MEMORY_LIMIT;
    /** The app ended the web process with {@link WebPage#terminateWebProcess()}. */
    public static final int WEB_PROCESS_TERMINATED_BY_API = WebKitWebView.WEB_PROCESS_TERMINATED_BY_API;

    /**
     * The interface used to answer a request for HTTP authentication. Only the first answer counts. None counts once
     * the request is gone: when its load ended or another one started, as when the page goes elsewhere, when the page
     * was destroyed, or when the WebView that made the page let go of it. It can be answered from any thread, as after
     * the app looked the credentials up: the answer reaches WebKit on the main thread.
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
         * The scheme of the address that is loaded: "https" when the connection to the site is encrypted, "http" when
         * it is not. When a proxy asks, it is still the scheme of that address, as WebKit does not tell how the proxy
         * is reached. A proxy reached over HTTP gets the credentials over a connection that is not encrypted, also for
         * an "https" address.
         */
        @NonNull
        String getScheme();

        /** The port of the server that asks. */
        int getPort();
    }

    /**
     * Gives the app a chance to take over a navigation before it starts, as
     * {@link WebViewClient#shouldOverrideUrlLoading}.
     *
     * @return {@code true} to cancel the navigation, which the app then takes care of, or {@code false} to let the
     *     page go on with it.
     */
    public boolean shouldOverrideUrlLoading(@NonNull WebPage page, @NonNull WPEResourceRequest request) {
        return false;
    }

    /**
     * Tells the app that a response is to be saved to a file instead of shown, as {@link
     * WebViewClient#onDownloadStart}. The app starts or cancels the download, now or later; destroying the page cancels
     * it. By default it is cancelled.
     */
    public void onDownloadStart(@NonNull WebPage page, @NonNull WPEDownload download) { download.cancel(); }

    /** Tells the app that a page has started loading. */
    public void onPageStarted(@NonNull WebPage page, @NonNull String url) {}

    /** Tells the app that a page has finished loading. */
    public void onPageFinished(@NonNull WebPage page, @NonNull String url) {}

    /**
     * Tells the app that a navigation is done, as {@link WebViewClient#doUpdateVisitedHistory}.
     * {@link WebPage#canGoBack()} and {@link WebPage#canGoForward()} then return the new state.
     */
    public void doUpdateVisitedHistory(@NonNull WebPage page, @NonNull String url, boolean isReload) {}

    /** Tells the app that the server answered a request with an HTTP error, a status of 400 or more. */
    public void onReceivedHttpError(@NonNull WebPage page, @NonNull WPEResourceRequest request,
                                    @NonNull WPEResourceResponse errorResponse) {}

    /**
     * Tells the app that the page in the main frame failed to load, as {@link WebViewClient#onReceivedError}, also
     * while the page has no view. {@link WPEResourceError#ERROR_LOCAL_NETWORK_ACCESS_DENIED} needs no activity either:
     * the app can request the permission when it shows the page.
     */
    public void onReceivedError(@NonNull WebPage page, @NonNull WPEResourceRequest request,
                                @NonNull WPEResourceError error) {}

    /**
     * Tells the app that the web process that ran the page ended, as {@link WebViewClient#onWebProcessTerminated}. The
     * page stays usable: {@link WebPage#reload()} loads it again in a new web process. A view that shows the page has
     * let go of the input method and left fullscreen before this call.
     *
     * @param reason {@link #WEB_PROCESS_CRASHED}, {@link #WEB_PROCESS_EXCEEDED_MEMORY_LIMIT} or
     *     {@link #WEB_PROCESS_TERMINATED_BY_API}.
     */
    public void onWebProcessTerminated(@NonNull WebPage page, int reason) {}

    /**
     * Tells the app that a server or a proxy asks for a user name and password, as
     * {@link WebViewClient#onReceivedHttpAuthRequest}. The load waits until the handler is answered. The default
     * answers with {@link HttpAuthHandler#cancel()}.
     */
    public void onReceivedHttpAuthRequest(@NonNull WebPage page, @NonNull HttpAuthHandler handler, @NonNull String host,
                                          @NonNull String realm) {
        handler.cancel();
    }

    /**
     * Tells the app that a request for HTTP authentication is gone before it was answered, as
     * {@link WebViewClient#onHttpAuthRequestCanceled}. Not called for the requests of a page that is destroyed.
     */
    public void onHttpAuthRequestCanceled(@NonNull WebPage page, @NonNull HttpAuthHandler handler) {}
}
