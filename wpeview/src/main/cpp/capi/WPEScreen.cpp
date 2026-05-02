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
#include <algorithm>
#include <cmath>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEScreen, "org/wpewebkit/wpe/WPEScreen");

namespace WebKit {

class JNIWPEScreenCache final : public JNI::TypedClass<JNIWPEScreen> {
public:
    JNIWPEScreenCache()
        : JNI::TypedClass<JNIWPEScreen>(true)
    {
        registerNativeMethods(JNI::NativeMethod<jfloat(jlong)>("nativeGetScale", nativeGetScale),
            JNI::NativeMethod<void(jlong, jfloat)>("nativeSetScale", nativeSetScale),
            JNI::NativeMethod<jfloat(jlong)>("nativeGetRefreshRateHz", nativeGetRefreshRateHz),
            JNI::NativeMethod<void(jlong, jfloat)>("nativeSetRefreshRateHz", nativeSetRefreshRateHz));
    }

private:
    static jfloat nativeGetScale(JNIEnv*, jobject, jlong nativePtr)
    {
        return static_cast<jfloat>(wpe_screen_get_scale(JNI::from_jlong<WPEScreen>(nativePtr)));
    }

    static void nativeSetScale(JNIEnv*, jobject, jlong nativePtr, jfloat scale)
    {
        wpe_screen_set_scale(JNI::from_jlong<WPEScreen>(nativePtr), scale);
    }

    static jfloat nativeGetRefreshRateHz(JNIEnv*, jobject, jlong nativePtr)
    {
        return static_cast<jfloat>(wpe_screen_get_refresh_rate(JNI::from_jlong<WPEScreen>(nativePtr)) / 1000.0);
    }

    static void nativeSetRefreshRateHz(JNIEnv*, jobject, jlong nativePtr, jfloat refreshRateHz)
    {
        auto refreshRateMilliHz = static_cast<guint32>(std::lround(std::max(refreshRateHz, 0.0F) * 1000.0F));
        wpe_screen_set_refresh_rate(JNI::from_jlong<WPEScreen>(nativePtr), refreshRateMilliHz);
    }
};

const JNIWPEScreenCache& getJNIWPEScreenCache()
{
    static const JNIWPEScreenCache s_singleton;
    return s_singleton;
}

void configureWPEScreenJNIMappings()
{
    getJNIWPEScreenCache();
}
} // namespace WebKit
