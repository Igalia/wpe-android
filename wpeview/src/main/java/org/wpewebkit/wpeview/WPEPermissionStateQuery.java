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

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WebKitWebView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A page asks, with {@code navigator.permissions.query()}, what a request of it would get, given to
 * {@link WebPageChromeClient#onQueryPermissionState} or {@link WebChromeClient#onQueryPermissionState}. The app that
 * takes the query answers it once, now or later, from any thread, with what a request would get now: granted, denied,
 * or prompt if the app would ask. Only the first answer counts, and none counts once the page loads another document.
 *
 * <p>WebKit adjusts the answer for the location: denied reads as prompt until the document has asked for the location,
 * so that a page cannot tell a refused site from a new one, and once it has, prompt reads as what it was answered.
 */
public abstract class WPEPermissionStateQuery {
    /** A request would be granted without asking. */
    public static final int STATE_GRANTED = WebKitWebView.PERMISSION_STATE_GRANTED;
    /** A request would be denied without asking. */
    public static final int STATE_DENIED = WebKitWebView.PERMISSION_STATE_DENIED;
    /** A request would ask the user. */
    public static final int STATE_PROMPT = WebKitWebView.PERMISSION_STATE_PROMPT;

    @IntDef({STATE_GRANTED, STATE_DENIED, STATE_PROMPT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    // Only WPEView makes queries.
    WPEPermissionStateQuery() {}

    /** The origin of the page that asks, its scheme, host and port, such as {@code https://example.com}. */
    public abstract @NonNull Uri getOrigin();

    /** What the page asks about, as a {@code RESOURCE_*} constant of {@link WPEPermissionRequest}. */
    public abstract @NonNull String getResource();

    /**
     * Answers the query.
     *
     * @throws IllegalArgumentException if the state is not one of the {@code STATE_*} constants.
     */
    public abstract void answer(@State int state);
}
