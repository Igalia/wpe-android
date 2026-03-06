/**
 * Copyright (C) 2026 Igalia S.L. <info@igalia.com>
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

#include "CAPI.h"
#include "JNI/JNI.h"
#include "Logging.h"

#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitSettings, "org/wpewebkit/wpe/WebKitSettings");

namespace WebKit {

class JNIWebKitSettingsCache final : public JNI::TypedClass<JNIWebKitSettings> {
public:
    JNIWebKitSettingsCache()
        : JNI::TypedClass<JNIWebKitSettings>(true)
    {
        registerNativeMethods(JNI::NativeMethod<jlong()>("nativeInit", JNIWebKitSettingsCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWebKitSettingsCache::nativeDestroy),
            JNI::NativeMethod<jstring(jlong)>(
                "nativeGetUserAgentString", JNIWebKitSettingsCache::nativeGetUserAgentString),
            JNI::NativeMethod<void(jlong, jstring)>(
                "nativeSetUserAgentString", JNIWebKitSettingsCache::nativeSetUserAgentString),
            JNI::NativeMethod<jboolean(jlong)>("nativeGetMediaPlaybackRequiresUserGesture",
                JNIWebKitSettingsCache::nativeGetMediaPlaybackRequiresUserGesture),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetMediaPlaybackRequiresUserGesture",
                JNIWebKitSettingsCache::nativeSetMediaPlaybackRequiresUserGesture),
            JNI::NativeMethod<jboolean(jlong)>("nativeGetAllowUniversalAccessFromFileURLs",
                JNIWebKitSettingsCache::nativeGetAllowUniversalAccessFromFileURLs),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetAllowUniversalAccessFromFileURLs",
                JNIWebKitSettingsCache::nativeSetAllowUniversalAccessFromFileURLs),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeGetAllowFileAccessFromFileURLs", JNIWebKitSettingsCache::nativeGetAllowFileAccessFromFileURLs),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetAllowFileAccessFromFileURLs", JNIWebKitSettingsCache::nativeSetAllowFileAccessFromFileURLs),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeGetDeveloperExtrasEnabled", JNIWebKitSettingsCache::nativeGetDeveloperExtrasEnabled),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetDeveloperExtrasEnabled", JNIWebKitSettingsCache::nativeSetDeveloperExtrasEnabled),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeGetDisableWebSecurity", JNIWebKitSettingsCache::nativeGetDisableWebSecurity),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetDisableWebSecurity", JNIWebKitSettingsCache::nativeSetDisableWebSecurity));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject)
    {
        return reinterpret_cast<jlong>(webkit_settings_new());
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            g_object_unref(settings);
    }

    static jstring nativeGetUserAgentString(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (!settings)
            return static_cast<jstring>(JNI::String(""));
        return static_cast<jstring>(JNI::String(webkit_settings_get_user_agent(settings)));
    }

    static void nativeSetUserAgentString(JNIEnv*, jobject, jlong settingsPtr, jstring userAgent) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            webkit_settings_set_user_agent(settings, JNI::String(userAgent).getContent().get());
    }

    static jboolean nativeGetMediaPlaybackRequiresUserGesture(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        return settings ? static_cast<jboolean>(webkit_settings_get_media_playback_requires_user_gesture(settings))
                        : JNI_FALSE;
    }

    static void nativeSetMediaPlaybackRequiresUserGesture(
        JNIEnv*, jobject, jlong settingsPtr, jboolean requires) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            webkit_settings_set_media_playback_requires_user_gesture(settings, static_cast<gboolean>(requires));
    }

    static jboolean nativeGetAllowUniversalAccessFromFileURLs(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        return settings ? static_cast<jboolean>(webkit_settings_get_allow_universal_access_from_file_urls(settings))
                        : JNI_FALSE;
    }

    static void nativeSetAllowUniversalAccessFromFileURLs(JNIEnv*, jobject, jlong settingsPtr, jboolean flag) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            webkit_settings_set_allow_universal_access_from_file_urls(settings, static_cast<gboolean>(flag));
    }

    static jboolean nativeGetAllowFileAccessFromFileURLs(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        return settings ? static_cast<jboolean>(webkit_settings_get_allow_file_access_from_file_urls(settings))
                        : JNI_FALSE;
    }

    static void nativeSetAllowFileAccessFromFileURLs(JNIEnv*, jobject, jlong settingsPtr, jboolean flag) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            webkit_settings_set_allow_file_access_from_file_urls(settings, static_cast<gboolean>(flag));
    }

    static jboolean nativeGetDeveloperExtrasEnabled(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        return settings ? static_cast<jboolean>(webkit_settings_get_enable_developer_extras(settings)) : JNI_FALSE;
    }

    static void nativeSetDeveloperExtrasEnabled(JNIEnv*, jobject, jlong settingsPtr, jboolean flag) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            webkit_settings_set_enable_developer_extras(settings, static_cast<gboolean>(flag));
    }

    static jboolean nativeGetDisableWebSecurity(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        return settings ? static_cast<jboolean>(webkit_settings_get_disable_web_security(settings)) : JNI_FALSE;
    }

    static void nativeSetDisableWebSecurity(JNIEnv*, jobject, jlong settingsPtr, jboolean disable) noexcept
    {
        auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);
        if (settings)
            webkit_settings_set_disable_web_security(settings, static_cast<gboolean>(disable));
    }
};

const JNIWebKitSettingsCache& getJNIWebKitSettingsCache()
{
    static const JNIWebKitSettingsCache s_singleton;
    return s_singleton;
}

void configureWebKitSettingsJNIMappings()
{
    getJNIWebKitSettingsCache();
}

} // namespace WebKit
