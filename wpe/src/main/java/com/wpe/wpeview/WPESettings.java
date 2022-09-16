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
