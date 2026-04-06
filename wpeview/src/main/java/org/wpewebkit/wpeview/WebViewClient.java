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
     * Mirrors {@code android.webkit.WebViewClient.doUpdateVisitedHistory}.
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
