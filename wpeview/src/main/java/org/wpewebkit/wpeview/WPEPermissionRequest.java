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

import androidx.annotation.NonNull;

/**
 * A page's request for something that needs the user's consent, such as the location of the device, given to
 * {@link WebPageChromeClient#onPermissionRequest} or {@link WebChromeClient#onPermissionRequest}. As with
 * android.webkit.PermissionRequest, the app grants or denies it once, now or later, from any thread: the answer
 * reaches the page on the main thread, and only the first one counts. Until then the page waits, as a call to
 * {@code navigator.geolocation.getCurrentPosition()} does.
 *
 * <p>WPEView remembers no answer: the app keeps those it wants to keep, for example per origin, and answers the next
 * requests from them. WebKit keeps the answer for the rest of the document that asked, so a refused page asks again
 * only once it loads again.
 *
 * <p>The request is for the origin of the page the user sees, its main document. A frame of another site can only ask
 * when the page lets it, with an {@code allow} attribute such as {@code allow="geolocation"}. A page whose address is
 * not http or https, such as about:blank or a data: address, is refused without asking the app.
 *
 * <p>A request ends without an answer when the document that asked goes away: when the page loads another document,
 * or when its web process ends. The page is refused, and {@link WebPageChromeClient#onPermissionRequestCanceled} tells
 * the app to take its question down. Destroying the page refuses its requests without a word. Answers to a request
 * that ended do nothing. A request of a frame that loads another document, or is removed, stays until it is answered,
 * and its answer goes nowhere, as WebKit does not tell of it.
 */
public abstract class WPEPermissionRequest {
    /**
     * The location of the device, for {@code navigator.geolocation}. The positions come from the
     * {@link WPELocationSource} of the {@link WebContext}, which by default needs the app to hold Android's
     * {@code ACCESS_COARSE_LOCATION} or {@code ACCESS_FINE_LOCATION} permission.
     */
    public static final String RESOURCE_LOCATION = "org.wpewebkit.resource.LOCATION";

    // Only WPEView makes requests.
    WPEPermissionRequest() {}

    /** The origin of the page that asks, its scheme, host and port, such as {@code https://example.com}. */
    public abstract @NonNull Uri getOrigin();

    /** What the page asks for, as {@code RESOURCE_*} constants, all of which the request grants or denies together. */
    public abstract @NonNull String[] getResources();

    /** Grants everything the request asks for. */
    public abstract void grant();

    /**
     * Grants the request if {@code resources} has everything it asks for, as android.webkit.PermissionRequest does, and
     * denies it otherwise: WebKit grants or denies a request as a whole.
     */
    public abstract void grant(@NonNull String[] resources);

    /** Denies the request. */
    public abstract void deny();
}
