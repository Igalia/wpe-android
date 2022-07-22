package com.wpe.wpeview;

import com.wpe.wpe.PageSettings;

public class WPESettings {
    private final PageSettings pageSettings = new PageSettings();

    // Internal API
    public PageSettings getPageSettings() { return pageSettings; }

    // Public API

    /**
     * Gets the WPEView's user-agent string.
     *
     * @return the WPEView's user-agent string
     * @see #setUserAgentString
     */
    public String getUserAgentString() { return pageSettings.getUserAgentString(); }

    /**
     * Sets the WPEView's user-agent string. If the string is {@code null} or empty,
     * the system default value will be used.
     *
     * @param ua new user-agent string
     */
    public void setUserAgentString(String ua) { pageSettings.setUserAgentString(ua); }

    /**
     * Gets whether the WPEView requires a user gesture to play media.
     *
     * @return {@code true} if the WPEView requires a user gesture to play media
     * @see #setMediaPlaybackRequiresUserGesture
     */
    public boolean getMediaPlaybackRequiresUserGesture() { return pageSettings.getMediaPlaybackRequiresUserGesture(); }

    /**
     * Sets whether the WPEView requires a user gesture to play media.
     * The default is {@code true}.
     *
     * @param require whether the WPEView requires a user gesture to play media
     */
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        pageSettings.setMediaPlaybackRequiresUserGesture(require);
    }
}
