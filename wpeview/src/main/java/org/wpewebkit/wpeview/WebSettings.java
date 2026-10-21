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
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WebKitSettings;

/**
 * WebSettings provides control over the configuration of a {@link WebView}. Once the WebView is destroyed,
 * the settings keep the values they had; they can still be read and changed, but changes have no effect.
 */
public class WebSettings {
    // Null once the WebView is destroyed; the fields then hold the values.
    private @Nullable WebKitSettings settings;
    private @NonNull String userAgent = "";
    private boolean mediaPlaybackRequiresUserGesture;
    private boolean allowUniversalAccessFromFileURLs;
    private boolean allowFileAccessFromFileURLs;
    private boolean developerExtrasEnabled;
    private boolean disableWebSecurity;

    WebSettings(@NonNull WebKitSettings settings) { this.settings = settings; }

    // Called by WebView.destroy() before it destroys the WebKitSettings.
    void detach() {
        if (settings == null)
            return;
        userAgent = settings.getUserAgentString();
        mediaPlaybackRequiresUserGesture = settings.getMediaPlaybackRequiresUserGesture();
        allowUniversalAccessFromFileURLs = settings.getAllowUniversalAccessFromFileURLs();
        allowFileAccessFromFileURLs = settings.getAllowFileAccessFromFileURLs();
        developerExtrasEnabled = settings.getDeveloperExtrasEnabled();
        disableWebSecurity = settings.getDisableWebSecurity();
        settings = null;
    }

    public @NonNull String getUserAgentString() { return settings != null ? settings.getUserAgentString() : userAgent; }
    public void setUserAgentString(@NonNull String userAgent) {
        if (settings != null)
            settings.setUserAgentString(userAgent);
        else
            this.userAgent = userAgent;
    }

    public boolean getMediaPlaybackRequiresUserGesture() {
        return settings != null ? settings.getMediaPlaybackRequiresUserGesture() : mediaPlaybackRequiresUserGesture;
    }
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        if (settings != null)
            settings.setMediaPlaybackRequiresUserGesture(require);
        else
            mediaPlaybackRequiresUserGesture = require;
    }

    public boolean getAllowUniversalAccessFromFileURLs() {
        return settings != null ? settings.getAllowUniversalAccessFromFileURLs() : allowUniversalAccessFromFileURLs;
    }
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        if (settings != null)
            settings.setAllowUniversalAccessFromFileURLs(flag);
        else
            allowUniversalAccessFromFileURLs = flag;
    }

    public boolean getAllowFileAccessFromFileURLs() {
        return settings != null ? settings.getAllowFileAccessFromFileURLs() : allowFileAccessFromFileURLs;
    }
    public void setAllowFileAccessFromFileURLs(boolean flag) {
        if (settings != null)
            settings.setAllowFileAccessFromFileURLs(flag);
        else
            allowFileAccessFromFileURLs = flag;
    }

    public boolean getDeveloperExtrasEnabled() {
        return settings != null ? settings.getDeveloperExtrasEnabled() : developerExtrasEnabled;
    }
    public void setDeveloperExtrasEnabled(boolean flag) {
        if (settings != null)
            settings.setDeveloperExtrasEnabled(flag);
        else
            developerExtrasEnabled = flag;
    }

    public boolean getDisableWebSecurity() {
        return settings != null ? settings.getDisableWebSecurity() : disableWebSecurity;
    }
    public void setDisableWebSecurity(boolean disable) {
        if (settings != null)
            settings.setDisableWebSecurity(disable);
        else
            disableWebSecurity = disable;
    }
}
