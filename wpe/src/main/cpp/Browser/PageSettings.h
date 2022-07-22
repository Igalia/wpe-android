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
