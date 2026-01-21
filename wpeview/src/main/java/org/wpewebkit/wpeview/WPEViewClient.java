/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

public class WPEViewClient {
    /**
     * Give the host application a chance to take control when a URL is about to be loaded
     * in the current WPEView. If a WPEViewClient is not provided, by default WPEView will
     * allow loading any URL.
     *
     * <p><strong>Note:</strong> Do not call {@link WPEView#loadUrl(String)} with the same URL
     * from within this method. Doing so triggers an infinite loop.
     *
     * @param view The WPEView that is initiating the callback.
     * @param url The URL to be loaded.
     * @param isRedirect {@code true} if the navigation was triggered by a redirect.
     * @param isUserGesture {@code true} if the navigation was triggered by a user gesture.
     * @return {@code true} to cancel the current load, {@code false} to continue.
     */
    public boolean shouldOverrideUrlLoading(@NonNull WPEView view, @NonNull String url, boolean isRedirect,
                                            boolean isUserGesture) {
        return false;
    }

    /**
     * Notify the host application that a page has started loading. This method
     * is called once for each main frame load so a page with iframes or
     * framesets will call onPageStarted one time for the main frame. This also
     * means that onPageStarted will not be called when the contents of an
     * embedded frame changes, i.e. clicking a link whose target is an iframe.
     * @param view The WPEView that is initiating the callback.
     * @param url The url to be loaded.
     */
    public void onPageStarted(@NonNull WPEView view, @NonNull String url) {}

    /**
     * Notify the host application that a page has finished loading. This method
     * is called only for main frame.
     * @param view The WPEView that is initiating the callback.
     * @param url The url of the page.
     */
    public void onPageFinished(@NonNull WPEView view, @NonNull String url) {}

    /**
     * Notify the host application that the loading state has changed.
     * This is called whenever the WebView starts or stops loading content.
     * @param view The WPEView that is initiating the callback.
     * @param isLoading {@code true} if the WebView is currently loading, {@code false} otherwise.
     */
    public void onLoadingStateChanged(@NonNull WPEView view, boolean isLoading) {}

    /**
     * Notify the host application that a page failed to load.
     * <p>
     * This callback is invoked when an error prevents a page from loading.
     * Common errors include network failures, DNS resolution failures,
     * and policy errors.
     * <p>
     * Note that {@link #onPageFinished} will still be called after this callback.
     *
     * @param view The WPEView that is initiating the callback.
     * @param failingUri The URI that failed to load.
     * @param errorCode The error code from WebKit.
     * @param errorDomain The error domain (e.g., "WebKitNetworkError", "WebKitPolicyError").
     * @param errorMessage A human-readable description of the error.
     * @return {@code true} if the error was handled and the default error page
     *         should not be shown; {@code false} to show the default error page.
     */
    public boolean onLoadFailed(@NonNull WPEView view, @NonNull String failingUri, int errorCode,
                                @NonNull String errorDomain, @NonNull String errorMessage) {
        return false;
    }

    /**
     * Notify the host application that the renderer process has exited.
     * <p>
     * Multiple {@link WPEView} instances may be associated with a single render process.
     * The application should handle this by destroying all associated WebViews.
     *
     * @param view The WPEView that initiated the callback.
     * @param terminationReason The reason for the termination. One of:
     *        <ul>
     *        <li>{@code 0} - WEB_PROCESS_CRASHED: The web process crashed.</li>
     *        <li>{@code 1} - WEB_PROCESS_EXCEEDED_MEMORY_LIMIT: The web process exceeded memory limits.</li>
     *        <li>{@code 2} - WEB_PROCESS_TERMINATED_BY_API: The web process was terminated by API call.</li>
     *        </ul>
     */
    public void onRenderProcessGone(@NonNull WPEView view, int terminationReason) {}

    /**
     * Notify the host application that the internal SurfaceView has been created
     * and it's ready to render to it's surface.
     */
    public void onViewReady(@NonNull WPEView view) {}

    /**
     * Notify the host application that an HTTP error has been received from the server while
     * loading a resource.  HTTP errors have status codes &gt;= 400.  This callback will be called
     * for any resource (iframe, image, etc.), not just for the main page. Thus, it is recommended
     * to perform minimum required work in this callback. Note that the content of the server
     * response may not be provided within the {@code errorResponse} parameter.
     * @param view The WPEView that is initiating the callback.
     * @param request The originating request.
     * @param errorResponse Information about the error occurred.
     */
    public void onReceivedHttpError(@NonNull WPEView view, @NonNull WPEResourceRequest request,
                                    @NonNull WPEResourceResponse errorResponse) {}

    /**
     * The interface used to accept or reject an invalid SSL certificate.
     *
     * @see #onReceivedSslError
     */
    public interface SslErrorHandler {
        /**
         * Call this method to accept an invalid SSL certificate.
         *
         * @see #onReceivedSslError
         */
        void proceed();

        /**
         * Call this method to reject an invalid SSL certificate.
         *
         * @see #onReceivedSslError
         */
        void cancel();
    }

    /**
     * Notify the host application that the loading of an HTTPS resource has failed because of error(s) with the SSL
     * certificate.
     *
     * @param view The WPEView that is initiating the callback.
     * @param handler The host application must call either {@code SslErrorHandler.cancel()} or {@code SslErrorHandler
     * .proceed()} to reject the invalid SSL certificate (default behavior) or to accept it (in this case it will be
     * accepted for all further calls to the same host with the same certificate). Accepting an invalid certificate
     * will automatically reload the web page right after registering the certificate.
     * @param error The SSL error(s) which happened.
     */
    public void onReceivedSslError(@NonNull WPEView view, @NonNull SslErrorHandler handler, @NonNull SslError error) {}
}
