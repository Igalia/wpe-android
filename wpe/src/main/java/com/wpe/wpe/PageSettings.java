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

package com.wpe.wpe;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PageSettings {
    private static final int DEFAULT_MAJOR_VERSION = 12;
    private static final int DEFAULT_MINOR_VERSION = 0;
    private static final int DEFAULT_BUGFIX_VERSION = 0;

    private static String OS_VERSION;
    private static final String DEFAUlT_USER_AGENT = String.format(
        "Mozilla/5.0 (Linux; Android %s) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile Safari/605.1.15",
        getOsVersion());

    private String userAgent = DEFAUlT_USER_AGENT;
    private boolean mediaPlaybackRequiresUserGesture = true;
    private Page page;

    private static String getOsVersion() {
        if (OS_VERSION == null) {
            String releaseVersion = android.os.Build.VERSION.RELEASE;
            boolean validReleaseVersion = false;
            try {
                Pattern versionPattern = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
                Matcher matcher = versionPattern.matcher(releaseVersion);
                // Check if major version is found
                String majorVersionString = matcher.group(1);
                if (majorVersionString != null && !majorVersionString.isEmpty()) {
                    validReleaseVersion = true;
                }
            } catch (Exception ignored) {
            }

            if (validReleaseVersion) {
                OS_VERSION = releaseVersion;
            } else {
                OS_VERSION = String.format(Locale.ROOT, "%d.%d.%d", DEFAULT_MAJOR_VERSION, DEFAULT_MINOR_VERSION,
                                           DEFAULT_BUGFIX_VERSION);
            }
        }
        return OS_VERSION;
    }

    void setPage(Page page) { this.page = page; }

    public String getUserAgentString() { return userAgent; }

    public void setUserAgentString(String userAgent) {
        final String oldUserAgent = userAgent;
        if (userAgent == null || userAgent.length() == 0) {
            this.userAgent = DEFAUlT_USER_AGENT;
        } else {
            this.userAgent = userAgent;
        }

        if (!oldUserAgent.equals(this.userAgent)) {
            if (page != null) {
                page.updateAllSettings();
            }
        }
    }

    public boolean getMediaPlaybackRequiresUserGesture() { return mediaPlaybackRequiresUserGesture; }

    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        if (mediaPlaybackRequiresUserGesture != require) {
            mediaPlaybackRequiresUserGesture = require;
            if (page != null) {
                page.updateAllSettings();
            }
        }
    }
}
