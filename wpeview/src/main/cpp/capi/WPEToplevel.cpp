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
        registerNativeMethods(JNI::NativeMethod<jlong(jlong, JNISurface)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeSetPhysicalSize", nativeSetPhysicalSize),
            JNI::NativeMethod<void(jlong, jintArray)>("nativeGetSize", nativeGetSize),
            JNI::NativeMethod<void(jlong, JNISurface)>("nativeSetSurface", nativeSetSurface));
    }

private:
    static jlong nativeInit(JNIEnv* env, jobject, jlong displayPtr, JNISurface surface)
    {
        auto* display = JNI::from_jlong<WPEDisplay>(displayPtr);
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;

        auto* toplevel = wpe_toplevel_android_new(display, window);
        if (window)
            ANativeWindow_release(window);

        return reinterpret_cast<jlong>(toplevel);
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr) noexcept
    {
        g_object_unref(JNI::from_jlong<WPEToplevel>(nativePtr));
    }

    static void nativeSetPhysicalSize(JNIEnv*, jobject, jlong nativePtr, jint width, jint height) noexcept
    {
        wpe_toplevel_android_set_physical_size(
            WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr)), width, height);
    }

    static void nativeGetSize(JNIEnv* env, jobject, jlong nativePtr, jintArray out) noexcept
    {
        int width = 0, height = 0;
        wpe_toplevel_get_size(JNI::from_jlong<WPEToplevel>(nativePtr), &width, &height);
        jint data[2] = {static_cast<jint>(width), static_cast<jint>(height)};
        env->SetIntArrayRegion(out, 0, 2, data);
    }

    static void nativeSetSurface(JNIEnv* env, jobject, jlong nativePtr, JNISurface surface) noexcept
    {
        auto* toplevel = JNI::from_jlong<WPEToplevel>(nativePtr);
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;

        wpe_toplevel_android_set_window(WPE_TOPLEVEL_ANDROID(toplevel), window);

        if (window)
            ANativeWindow_release(window);
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
