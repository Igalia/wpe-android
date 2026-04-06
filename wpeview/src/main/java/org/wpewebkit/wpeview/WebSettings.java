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

import org.wpewebkit.wpe.WebKitSettings;

/**
 * WebSettings provides control over the configuration of a {@link WebView}.
 */
public class WebSettings {
    private final WebKitSettings settings;

    WebSettings(@NonNull WebKitSettings settings) { this.settings = settings; }

    public @NonNull String getUserAgentString() { return settings.getUserAgentString(); }
    public void setUserAgentString(@NonNull String userAgent) { settings.setUserAgentString(userAgent); }

    public boolean getMediaPlaybackRequiresUserGesture() { return settings.getMediaPlaybackRequiresUserGesture(); }
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        settings.setMediaPlaybackRequiresUserGesture(require);
    }

    public boolean getAllowUniversalAccessFromFileURLs() { return settings.getAllowUniversalAccessFromFileURLs(); }
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        settings.setAllowUniversalAccessFromFileURLs(flag);
    }

    public boolean getAllowFileAccessFromFileURLs() { return settings.getAllowFileAccessFromFileURLs(); }
    public void setAllowFileAccessFromFileURLs(boolean flag) { settings.setAllowFileAccessFromFileURLs(flag); }

    public boolean getDeveloperExtrasEnabled() { return settings.getDeveloperExtrasEnabled(); }
    public void setDeveloperExtrasEnabled(boolean flag) { settings.setDeveloperExtrasEnabled(flag); }

    public boolean getDisableWebSecurity() { return settings.getDisableWebSecurity(); }
    public void setDisableWebSecurity(boolean disable) { settings.setDisableWebSecurity(disable); }
}
