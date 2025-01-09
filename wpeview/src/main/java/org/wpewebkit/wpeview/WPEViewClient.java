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
