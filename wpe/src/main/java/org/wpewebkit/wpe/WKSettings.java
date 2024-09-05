/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

package org.wpewebkit.wpe;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WKSettings {
    private static final int DEFAULT_MAJOR_VERSION = 12;
    private static final int DEFAULT_MINOR_VERSION = 0;
    private static final int DEFAULT_BUGFIX_VERSION = 0;

    private static final Pattern versionPattern = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    private static String getOsVersion() {
        try {
            String releaseVersion = android.os.Build.VERSION.RELEASE;

            Matcher matcher = versionPattern.matcher(releaseVersion);
            String majorVersionString = matcher.group(1);
            if ((majorVersionString != null) && !majorVersionString.isEmpty())
                return releaseVersion;
        } catch (Exception e) {
        }

        return String.format(Locale.ROOT, "%d.%d.%d", DEFAULT_MAJOR_VERSION, DEFAULT_MINOR_VERSION,
                             DEFAULT_BUGFIX_VERSION);
    }

    private static final String DEFAUlT_USER_AGENT = "Mozilla/5.0 (Linux; Android " + getOsVersion() +
                                                     ") AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 "
                                                     + "Mobile Safari/605.1.15";
    private final WKWebView wkWebView;

    public @NonNull WKWebView getWKWebView() { return wkWebView; }

    private native void setNativeUserAgent(long nativePtr, String userAgent);
    private native void setNativeMediaPlaybackRequiresUserGesture(long nativePtr, boolean requires);
    private native void setNativeAllowFileUrls(long nativePtr, boolean allow);

    public WKSettings(@NonNull WKWebView wkWebView) {
        this.wkWebView = wkWebView;
        setUserAgent(null);
        setMediaPlaybackRequiresUserGesture(true);
    }

    private String userAgent = "";

    /**
     * Gets the user-agent string.
     * @return the page user-agent string
     * @see #setUserAgent
     */
    public @NonNull String getUserAgent() { return userAgent; }

    /**
     * Sets the user-agent string. If the string is {@code null} or empty,
     * the system default value will be used.
     * @param str the new user-agent string
     */
    public void setUserAgent(@Nullable String str) {
        if ((str == null) || str.isEmpty())
            str = DEFAUlT_USER_AGENT;

        if (!str.equals(userAgent)) {
            userAgent = str;
            setNativeUserAgent(wkWebView.getNativePtr(), userAgent);
        }
    }

    private boolean mediaPlaybackRequiresUserGesture = false;

    /**
     * Gets whether the page requires a user gesture to play media.
     * @return {@code true} if the page requires a user gesture to play media, or false otherwise.
     * @see #setMediaPlaybackRequiresUserGesture
     */
    public boolean getMediaPlaybackRequiresUserGesture() { return mediaPlaybackRequiresUserGesture; }

    /**
     * Sets whether the page requires a user gesture to play media.
     * The default is {@code true}.
     * @param requires whether the page requires a user gesture to play media, or not.
     */
    public void setMediaPlaybackRequiresUserGesture(boolean requires) {
        if (requires != mediaPlaybackRequiresUserGesture) {
            mediaPlaybackRequiresUserGesture = requires;
            setNativeMediaPlaybackRequiresUserGesture(wkWebView.getNativePtr(), mediaPlaybackRequiresUserGesture);
        }
    }

    private boolean allowFileUrls = false;

    /**
     * Gets whether the page allows file Urls.
     * @return {@code true} if the page allows file Urls, or false otherwise.
     * @see #setAllowFileUrls
     */
    public boolean getAllowFileUrls() { return allowFileUrls; }

    /**
     * Sets whether the page allows file Urls.
     * The default is {@code false}.
     * @param allow whether the page allows file Urls, or not.
     */
    public void setAllowFileUrls(boolean allow) {
        if (allow != allowFileUrls) {
            allowFileUrls = allow;
            setNativeAllowFileUrls(wkWebView.getNativePtr(), allowFileUrls);
        }
    }
}
