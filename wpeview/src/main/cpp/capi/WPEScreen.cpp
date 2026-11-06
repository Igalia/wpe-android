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
#include "JNIMappings.h"
#include "WPEScreenAndroid.h"
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
        registerNativeMethods(JNI::NativeMethod<jlong(jint)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<jint(jlong)>("nativeGetWidth", nativeGetWidth),
            JNI::NativeMethod<jint(jlong)>("nativeGetHeight", nativeGetHeight),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeSetSize", nativeSetSize),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeSetPhysicalSize", nativeSetPhysicalSize),
            JNI::NativeMethod<jfloat(jlong)>("nativeGetScale", nativeGetScale),
            JNI::NativeMethod<void(jlong, jfloat)>("nativeSetScale", nativeSetScale),
            JNI::NativeMethod<jfloat(jlong)>("nativeGetRefreshRateHz", nativeGetRefreshRateHz),
            JNI::NativeMethod<void(jlong, jfloat)>("nativeSetRefreshRateHz", nativeSetRefreshRateHz));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject, jint id)
    {
        return reinterpret_cast<jlong>(wpe_screen_android_new(static_cast<guint32>(id)));
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr)
    {
        g_object_unref(JNI::from_jlong<WPEScreen>(nativePtr));
    }

    static jint nativeGetWidth(JNIEnv*, jobject, jlong nativePtr)
    {
        return wpe_screen_get_width(JNI::from_jlong<WPEScreen>(nativePtr));
    }

    static jint nativeGetHeight(JNIEnv*, jobject, jlong nativePtr)
    {
        return wpe_screen_get_height(JNI::from_jlong<WPEScreen>(nativePtr));
    }

    static void nativeSetSize(JNIEnv*, jobject, jlong nativePtr, jint width, jint height)
    {
        wpe_screen_set_size(JNI::from_jlong<WPEScreen>(nativePtr), width, height);
    }

    static void nativeSetPhysicalSize(JNIEnv*, jobject, jlong nativePtr, jint width, jint height)
    {
        wpe_screen_set_physical_size(JNI::from_jlong<WPEScreen>(nativePtr), width, height);
    }

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

static const JNIWPEScreenCache& getJNIWPEScreenCache()
{
    static const JNIWPEScreenCache s_singleton;
    return s_singleton;
}

void configureWPEScreenJNIMappings()
{
    getJNIWPEScreenCache();
}
} // namespace WebKit
