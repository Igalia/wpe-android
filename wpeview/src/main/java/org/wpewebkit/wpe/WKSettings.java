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

    private String userAgent = "";
    private boolean mediaPlaybackRequiresUserGesture = false;
    private boolean allowUniversalAccessFromFileUrls = false;
    private boolean allowFileAccessFromFileUrls = false;
    private boolean developerExtrasEnabled = false;

    public WKSettings(@NonNull WKWebView wkWebView) {
        this.wkWebView = wkWebView;

        setUserAgentString(null);
        setMediaPlaybackRequiresUserGesture(true);
    }

    public @NonNull String getUserAgentString() { return userAgent; }

    public void setUserAgentString(@Nullable String str) {
        if ((str == null) || str.isEmpty())
            str = DEFAUlT_USER_AGENT;

        if (!str.equals(userAgent)) {
            userAgent = str;
            nativeSetUserAgentString(wkWebView.getNativePtr(), userAgent);
        }
    }

    public boolean getMediaPlaybackRequiresUserGesture() { return mediaPlaybackRequiresUserGesture; }

    public void setMediaPlaybackRequiresUserGesture(boolean requires) {
        if (requires != mediaPlaybackRequiresUserGesture) {
            mediaPlaybackRequiresUserGesture = requires;
            nativeSetMediaPlaybackRequiresUserGesture(wkWebView.getNativePtr(), mediaPlaybackRequiresUserGesture);
        }
    }

    public boolean getAllowUniversalAccessFromFileURLs() { return allowUniversalAccessFromFileUrls; }

    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        if (flag != allowUniversalAccessFromFileUrls) {
            allowUniversalAccessFromFileUrls = flag;
            nativeSetAllowUniversalAccessFromFileURLs(wkWebView.getNativePtr(), flag);
        }
    }

    public boolean getAllowFileAccessFromFileURLs() { return allowFileAccessFromFileUrls; }

    public void setAllowFileAccessFromFileURLs(boolean flag) {
        if (flag != allowFileAccessFromFileUrls) {
            allowFileAccessFromFileUrls = flag;
            nativeSetAllowFileAccessFromFileURLs(wkWebView.getNativePtr(), flag);
        }
    }

    public boolean getDeveloperExtrasEnabled() { return developerExtrasEnabled; }

    public void setDeveloperExtrasEnabled(boolean flag) {
        if (flag != developerExtrasEnabled) {
            developerExtrasEnabled = flag;
            nativeSetDeveloperExtrasEnabled(wkWebView.getNativePtr(), flag);
        }
    }

    private native void nativeSetUserAgentString(long nativePtr, String userAgent);
    private native void nativeSetMediaPlaybackRequiresUserGesture(long nativePtr, boolean requires);
    private native void nativeSetAllowFileAccessFromFileURLs(long nativePtr, boolean flag);
    private native void nativeSetAllowUniversalAccessFromFileURLs(long nativePtr, boolean flag);
    private native void nativeSetDeveloperExtrasEnabled(long nativePtr, boolean flag);
}
