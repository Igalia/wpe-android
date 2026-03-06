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
#include "WPEToplevelAndroid.h"

#include <android/native_window_jni.h>
#include <jni.h>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEToplevel, "org/wpewebkit/wpe/WPEToplevel");
DECLARE_JNI_CLASS_SIGNATURE(JNISurface, "android/view/Surface");

namespace WebKit {

class JNIWPEToplevelCache final : public JNI::TypedClass<JNIWPEToplevel> {
public:
    JNIWPEToplevelCache()
        : JNI::TypedClass<JNIWPEToplevel>(true)
    {
        registerNativeMethods(
            JNI::NativeMethod<jlong(jlong, JNISurface)>("nativeInit", JNIWPEToplevelCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWPEToplevelCache::nativeDestroy),
            JNI::NativeMethod<void(jlong, jint, jint)>(
                "nativeSetPhysicalSize", JNIWPEToplevelCache::nativeSetPhysicalSize),
            JNI::NativeMethod<jint(jlong)>("nativeGetWidth", JNIWPEToplevelCache::nativeGetWidth),
            JNI::NativeMethod<jint(jlong)>("nativeGetHeight", JNIWPEToplevelCache::nativeGetHeight),
            JNI::NativeMethod<void(jlong, JNISurface)>("nativeSetSurface", JNIWPEToplevelCache::nativeSetSurface));
    }

private:
    static void nativeSetPhysicalSize(JNIEnv* env, jobject jniObject, jlong nativePtr, jint width, jint height) noexcept
    {
        UNUSED_PARAM(env);
        UNUSED_PARAM(jniObject);
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel != nullptr && WPE_IS_TOPLEVEL_ANDROID(toplevel))
            wpe_toplevel_android_set_physical_size(WPE_TOPLEVEL_ANDROID(toplevel), width, height);
    }

    static jint nativeGetWidth(JNIEnv* env, jobject jniObject, jlong nativePtr) noexcept
    {
        UNUSED_PARAM(env);
        UNUSED_PARAM(jniObject);
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel == nullptr)
            return 0;

        int width = 0;
        wpe_toplevel_get_size(toplevel, &width, nullptr);
        return width;
    }

    static jint nativeGetHeight(JNIEnv* env, jobject jniObject, jlong nativePtr) noexcept
    {
        UNUSED_PARAM(env);
        UNUSED_PARAM(jniObject);
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel == nullptr)
            return 0;

        int height = 0;
        wpe_toplevel_get_size(toplevel, nullptr, &height);
        return height;
    }

    static jlong nativeInit(JNIEnv* env, jobject jniObject, jlong displayPtr, JNISurface surface)
    {
        UNUSED_PARAM(jniObject);
        auto* display = reinterpret_cast<WPEDisplay*>(displayPtr);
        if (display == nullptr)
            return 0;

        ANativeWindow* window = nullptr;
        if (surface)
            window = ANativeWindow_fromSurface(env, surface);

        auto* toplevel = wpe_toplevel_android_new(display, window);
        if (window != nullptr)
            ANativeWindow_release(window);

        return reinterpret_cast<jlong>(toplevel);
    }

    static void nativeSetSurface(JNIEnv* env, jobject jniObject, jlong nativePtr, JNISurface surface) noexcept
    {
        UNUSED_PARAM(jniObject);
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel == nullptr || !WPE_IS_TOPLEVEL_ANDROID(toplevel))
            return;

        ANativeWindow* window = nullptr;
        if (surface)
            window = ANativeWindow_fromSurface(env, surface);

        wpe_toplevel_android_set_window(WPE_TOPLEVEL_ANDROID(toplevel), window);

        if (window != nullptr)
            ANativeWindow_release(window);
    }

    static void nativeDestroy(JNIEnv* env, jobject jniObject, jlong nativePtr) noexcept
    {
        UNUSED_PARAM(env);
        UNUSED_PARAM(jniObject);
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel != nullptr)
            g_object_unref(toplevel);
    }
};

const JNIWPEToplevelCache& getJNIWPEToplevelCache()
{
    static const JNIWPEToplevelCache s_singleton;
    return s_singleton;
}

void configureWPEToplevelJNIMappings()
{
    getJNIWPEToplevelCache();
}

} // namespace WebKit
