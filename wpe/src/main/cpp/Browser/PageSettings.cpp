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

#include "PageSettings.h"

#include "Page.h"

namespace {
void setNativeUserAgent(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jstring userAgent) noexcept
{
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if (page != nullptr) {
        WebKitWebView* webView = page->webView();
        WebKitSettings* settings = webkit_web_view_get_settings(webView);
        webkit_settings_set_user_agent(settings, JNI::String(userAgent).getContent().get());
        webkit_web_view_set_settings(webView, settings);
    }
}

void setNativeMediaPlaybackRequiresUserGesture(
    JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jboolean require) noexcept
{
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if (page != nullptr) {
        WebKitWebView* webView = page->webView();
        WebKitSettings* settings = webkit_web_view_get_settings(webView);
        webkit_settings_set_media_playback_requires_user_gesture(settings, static_cast<gboolean>(require));
        webkit_web_view_set_settings(webView, settings);
    }
}
} // namespace

void PageSettings::configureJNIMappings()
{
    JNI::Class("com/wpe/wpe/PageSettings")
        .registerNativeMethods(JNI::NativeMethod<void(jlong, jstring)>("setNativeUserAgent", setNativeUserAgent),
            JNI::NativeMethod<void(jlong, jboolean)>(
                "setNativeMediaPlaybackRequiresUserGesture", setNativeMediaPlaybackRequiresUserGesture));
}
