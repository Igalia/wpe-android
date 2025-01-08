/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

#include "WKSettings.h"

#include "WKWebView.h"

namespace {
void nativeSetNativeUserAgentString(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jstring userAgent) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        WebKitSettings* settings = webkit_web_view_get_settings(wkWebView->webView());
        webkit_settings_set_user_agent(settings, JNI::String(userAgent).getContent().get());
    }
}

void nativeSetMediaPlaybackRequiresUserGesture(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jboolean require) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        WebKitSettings* settings = webkit_web_view_get_settings(wkWebView->webView());
        webkit_settings_set_media_playback_requires_user_gesture(settings, static_cast<gboolean>(require));
    }
}

void nativeSetAllowFileAccessFromFileURLs(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jboolean flag) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        WebKitSettings* settings = webkit_web_view_get_settings(wkWebView->webView());
        webkit_settings_set_allow_file_access_from_file_urls(settings, static_cast<gboolean>(flag));
    }
}

void nativeSetAllowUniversalAccessFromFileURLs(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jboolean flag) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        WebKitSettings* settings = webkit_web_view_get_settings(wkWebView->webView());
        webkit_settings_set_allow_universal_access_from_file_urls(settings, static_cast<gboolean>(flag));
    }
}

void nativeSetDeveloperExtrasEnabled(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jboolean flag) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        WebKitSettings* settings = webkit_web_view_get_settings(wkWebView->webView());
        webkit_settings_set_enable_developer_extras(settings, static_cast<gboolean>(flag));
    }
}

void nativeSetDisableWebSecurity(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jboolean disable) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        WebKitSettings* settings = webkit_web_view_get_settings(wkWebView->webView());
        webkit_settings_set_disable_web_security(settings, static_cast<gboolean>(disable));
    }
}
} // namespace

void WKSettings::configureJNIMappings()
{
    JNI::Class("org/wpewebkit/wpe/WKSettings")
        .registerNativeMethods(
            JNI::NativeMethod<void(jlong, jstring)>("nativeSetUserAgentString", nativeSetNativeUserAgentString),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetMediaPlaybackRequiresUserGesture", nativeSetMediaPlaybackRequiresUserGesture),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetAllowFileAccessFromFileURLs", nativeSetAllowFileAccessFromFileURLs),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetAllowUniversalAccessFromFileURLs", nativeSetAllowUniversalAccessFromFileURLs),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "nativeSetDeveloperExtrasEnabled", nativeSetDeveloperExtrasEnabled),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetDisableWebSecurity", nativeSetDisableWebSecurity));
}
