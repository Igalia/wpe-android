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
        registerNativeMethods(JNI::NativeMethod<jlong()>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<jstring(jlong)>("nativeGetUserAgentString", nativeGetUserAgentString),
            JNI::NativeMethod<void(jlong, jstring)>("nativeSetUserAgentString", nativeSetUserAgentString),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeGetMediaPlaybackRequiresUserGesture", nativeGetMediaPlaybackRequiresUserGesture),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetMediaPlaybackRequiresUserGesture", nativeSetMediaPlaybackRequiresUserGesture),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeGetAllowUniversalAccessFromFileURLs", nativeGetAllowUniversalAccessFromFileURLs),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetAllowUniversalAccessFromFileURLs", nativeSetAllowUniversalAccessFromFileURLs),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeGetAllowFileAccessFromFileURLs", nativeGetAllowFileAccessFromFileURLs),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetAllowFileAccessFromFileURLs", nativeSetAllowFileAccessFromFileURLs),
            JNI::NativeMethod<jboolean(jlong)>("nativeGetDeveloperExtrasEnabled", nativeGetDeveloperExtrasEnabled),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetDeveloperExtrasEnabled", nativeSetDeveloperExtrasEnabled),
            JNI::NativeMethod<jboolean(jlong)>("nativeGetDisableWebSecurity", nativeGetDisableWebSecurity),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetDisableWebSecurity", nativeSetDisableWebSecurity));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject)
    {
        return reinterpret_cast<jlong>(webkit_settings_new());
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        g_object_unref(JNI::from_jlong<WebKitSettings>(settingsPtr));
    }

    static jstring nativeGetUserAgentString(JNIEnv*, jobject, jlong settingsPtr) noexcept
    {
        return static_cast<jstring>(
            JNI::String(webkit_settings_get_user_agent(JNI::from_jlong<WebKitSettings>(settingsPtr))));
    }

    static void nativeSetUserAgentString(JNIEnv*, jobject, jlong settingsPtr, jstring userAgent) noexcept
    {
        webkit_settings_set_user_agent(
            JNI::from_jlong<WebKitSettings>(settingsPtr), JNI::String(userAgent).getContent().get());
    }

// NOLINTBEGIN(cppcoreguidelines-macro-usage)
#define DEFINE_BOOL_SETTER_GETTER(NativeName, WebKitName)                                                              \
    static jboolean nativeGet##NativeName(JNIEnv*, jobject, jlong settingsPtr) noexcept                                \
    {                                                                                                                  \
        return static_cast<jboolean>(webkit_settings_get_##WebKitName(JNI::from_jlong<WebKitSettings>(settingsPtr)));  \
    }                                                                                                                  \
    static void nativeSet##NativeName(JNIEnv*, jobject, jlong settingsPtr, jboolean flag) noexcept                     \
    {                                                                                                                  \
        webkit_settings_set_##WebKitName(JNI::from_jlong<WebKitSettings>(settingsPtr), static_cast<gboolean>(flag));   \
    }

    DEFINE_BOOL_SETTER_GETTER(MediaPlaybackRequiresUserGesture, media_playback_requires_user_gesture)
    DEFINE_BOOL_SETTER_GETTER(AllowUniversalAccessFromFileURLs, allow_universal_access_from_file_urls)
    DEFINE_BOOL_SETTER_GETTER(AllowFileAccessFromFileURLs, allow_file_access_from_file_urls)
    DEFINE_BOOL_SETTER_GETTER(DeveloperExtrasEnabled, enable_developer_extras)
    DEFINE_BOOL_SETTER_GETTER(DisableWebSecurity, disable_web_security)

#undef DEFINE_BOOL_SETTER_GETTER
    // NOLINTEND(cppcoreguidelines-macro-usage)
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
