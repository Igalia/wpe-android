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

#pragma once

#include <string>

/**
 * For now this is just a container to pass settings data around but
 * eventually this will have 1:1 relation to webkit_web_view_set_settings
 * and also weak ref to java side object
 */
class PageSettings {
public:
    void setUserAgent(const std::string& ua) { m_userAgent = ua; }
    std::string userAgent() const { return m_userAgent; }

    void setMediaPlayerRequiresUserGesture(bool require) { m_mediaPlayerRequiresUserGesture = require; }
    bool mediaPlayerRequiresUserGesture() const { return m_mediaPlayerRequiresUserGesture; }

private:
    std::string m_userAgent;
    bool m_mediaPlayerRequiresUserGesture = false;
};
