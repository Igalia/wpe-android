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
