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

/**
 * Why a page failed to load, as passed to {@link WebPageClient#onReceivedError} and
 * {@link WebViewClient#onReceivedError}.
 */
public class WPEResourceError {
    // The error codes, those of android.webkit.WebViewClient.

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

    private final int errorCode;
    private final String description;

    public WPEResourceError(int errorCode, @NonNull String description) {
        this.errorCode = errorCode;
        this.description = description;
    }

    /**
     * Gets the error code, one of the {@code ERROR_*} constants.
     *
     * @return The error code.
     */
    public int getErrorCode() { return errorCode; }

    /**
     * Gets WebKit's description of the error, in English.
     *
     * @return The description of the error.
     */
    public @NonNull String getDescription() { return description; }
}
