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
import androidx.annotation.Nullable;

/**
 * A notification that a page shows with {@code new Notification()}, given to
 * {@link WebPageChromeClient#onShowNotification} or {@link WebChromeClient#onShowNotification} for the app to show the
 * user, for example as an Android notification. Only pages that the app granted
 * {@link WPEPermissionRequest#RESOURCE_NOTIFICATIONS} show them, and only while they are open: WPEView shows no
 * notifications of service workers, whose {@code registration.showNotification()} pages find missing, and no push
 * messages.
 *
 * <p>The app tells the page what the user does with it: {@link #click()} when the user taps it, and {@link #close()}
 * when the user dismisses it, or when the app does not show it. Both may be called from any thread, and do nothing
 * once the notification is closed. {@link WebPageChromeClient#onCloseNotification} tells the app when a notification
 * closes otherwise: when the page closes it, and when the page goes into the back forward cache or loads another
 * document, as no page would hear of it any more. A notification with the tag of one that the page showed before takes
 * the place of that one, which {@link #getReplaced()} gives, and which is not reported closed.
 *
 * <p>When the web process of the page ends, its notifications stay with the app, which may keep them as a way back to
 * the page, but {@link #click()} and {@link #close()} do nothing more. Destroying the page ends its notifications too.
 * The chrome client of a {@link WebView} that made the page, and goes on, hears that each one closes, as when the view
 * lets go of the page otherwise; other clients hear nothing.
 */
public abstract class WPENotification {
    /**
     * The most characters of a title, a body or a tag, counted as Unicode characters: Android shows no more of a title
     * or a body, so the rest is left out.
     */
    public static final int MAX_TEXT_LENGTH = 1024;

    /** The most notifications of a page that wait for a chrome client (see WebPageChromeClient#onShowNotification). */
    public static final int MAX_WAITING = 5;

    // Only WPEView makes notifications.
    WPENotification() {}

    /**
     * The origin of the page that shows it, its scheme, host and port, such as {@code https://example.com}: the one
     * that the app granted. A frame of another site that the app granted on its own shows its notifications under the
     * origin of the page, as WebKit does not tell which frame showed one.
     */
    public abstract @NonNull Uri getOrigin();

    /** The title, at most {@link #MAX_TEXT_LENGTH} characters. */
    public abstract @NonNull String getTitle();

    /** The text under the title, empty if the page gave none, at most {@link #MAX_TEXT_LENGTH} characters. */
    public abstract @NonNull String getBody();

    /** The tag that the page gave it, or null. A later notification of the page with the same tag takes its place. */
    public abstract @Nullable String getTag();

    /**
     * The notification of the page whose place this one takes, as it has the same tag, or null. The app shows this one
     * instead of that one, which is closed for the page and not reported closed. That one gives null from then on, so
     * that a page that keeps updating a notification does not keep every earlier one.
     *
     * <p>Only a notification of the same page is replaced this way. WebKit also closes a notification with the same tag
     * that another page of the {@link WebContext} shows, whatever its site: that page gets its close event, and its
     * chrome client hears that it closed.
     */
    public abstract @Nullable WPENotification getReplaced();

    /** Tells the page that the user tapped the notification: it gets a click event, and may focus its window. */
    public abstract void click();

    /** Closes the notification, as the user dismissed it or the app does not show it: the page gets a close event. */
    public abstract void close();
}
