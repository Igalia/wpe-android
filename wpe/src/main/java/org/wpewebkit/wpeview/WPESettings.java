package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WKSettings;

public class WPESettings {

    private final WKSettings wkSettings;

    WPESettings(WKSettings wkSettings) { this.wkSettings = wkSettings; }

    /**
     * Gets the WPEView's user-agent string.
     * @return the page user-agent string
     * @see #setUserAgentString
     */
    public @NonNull String getUserAgentString() { return wkSettings.getUserAgentString(); }

    /**
     * Sets the user-agent string. If the string is {@code null} or empty,
     * the system default value will be used.
     * @param str the new user-agent string
     */
    public void setUserAgentString(@Nullable String str) { wkSettings.setUserAgentString(str); }

    /**
     * Gets whether the WPEView requires a user gesture to play media.
     * @return {@code true} if the WPEView requires a user gesture to play media, or false otherwise.
     * @see #setMediaPlaybackRequiresUserGesture
     */
    public boolean getMediaPlaybackRequiresUserGesture() { return wkSettings.getMediaPlaybackRequiresUserGesture(); }

    /**
     * Sets whether the WPEView requires a user gesture to play media.
     * The default is {@code true}.
     * @param require whether the WPEView requires a user gesture to play media, or not.
     */
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        wkSettings.setMediaPlaybackRequiresUserGesture(require);
    }

    /**
     * Gets whether JavaScript running in the context of a file scheme URL can
     * access content from any origin. This includes access to content from
     * other file scheme URLs.
     *
     * @return whether JavaScript running in the context of a file scheme URL
     *         can access content from any origin
     * @see #setAllowUniversalAccessFromFileURLs
     */
    public boolean getAllowUniversalAccessFromFileURLs() { return wkSettings.getAllowUniversalAccessFromFileURLs(); }

    /**
     * Sets whether JavaScript running in the context of a file scheme URL
     * should be allowed to access content from any origin. This includes
     * access to content from other file scheme URLs. See
     * {@link #setAllowFileAccessFromFileURLs}. To enable the most restrictive,
     * and therefore secure policy, this setting should be disabled.
     * Note that this setting affects only JavaScript access to file scheme
     * resources. Other access to such resources, for example, from image HTML
     * elements, is unaffected.
     * <p>
     * The default value is false.
     *
     * @param flag whether JavaScript running in the context of a file scheme
     *             URL should be allowed to access contenrt from any origin
     */
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        wkSettings.setAllowUniversalAccessFromFileURLs(flag);
    }

    /**
     * Gets whether JavaScript running in the context of a file scheme URL can
     * access content from other file scheme URLs.
     *
     * @return whether JavaScript running in the context of a file scheme URL
     *         can access content from other file scheme URLs
     * @see #setAllowFileAccessFromFileURLs
     */
    public boolean getAllowFileAccessFromFileURLs() { return wkSettings.getAllowFileAccessFromFileURLs(); }

    /**
     * Sets whether JavaScript running in the context of a file scheme URL
     * should be allowed to access content from other file scheme URLs. To
     * enable the most restrictive, and therefore secure policy, this setting
     * should be disabled. Note that the value of this setting is ignored if
     * the value of {@link #getAllowUniversalAccessFromFileURLs} is true.
     * Note too, that this setting affects only JavaScript access to file scheme
     * resources. Other access to such resources, for example, from image HTML
     * elements, is unaffected.
     * <p>
     * The default value is false.
     *
     * @param flag whether JavaScript running in the context of a file scheme
     *             URL should be allowed to access content from other file
     *             scheme URLs
     */
    public void setAllowFileAccessFromFileURLs(boolean flag) { wkSettings.setAllowFileAccessFromFileURLs(flag); }

    /**
     * Gets whether developer tools, such as the Web Inspector, are enabled.
     * @return whether developer tools, such as the Web Inspector, are enabled
     * @see #setDeveloperExtrasEnabled
     */
    public boolean getDeveloperExtrasEnabled() { return wkSettings.getDeveloperExtrasEnabled(); }

    /**
     * Sets whether developer tools, such as the Web Inspector, are enabled.
     * <p>
     * The default value is false.
     * @param flag whether developer tools, such as the Web Inspector, are enabled
     */
    public void setDeveloperExtrasEnabled(boolean flag) { wkSettings.setDeveloperExtrasEnabled(flag); }
}
