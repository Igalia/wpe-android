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
        registerNativeMethods(JNI::NativeMethod<jlong(jlong)>("nativeInit", JNIWPEToplevelCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWPEToplevelCache::nativeDestroy),
            JNI::NativeMethod<void(jlong, JNISurface)>(
                "nativeSurfaceCreated", JNIWPEToplevelCache::nativeSurfaceCreated),
            JNI::NativeMethod<void(jlong)>("nativeSurfaceDestroyed", JNIWPEToplevelCache::nativeSurfaceDestroyed),
            JNI::NativeMethod<void(jlong, jfloat)>("nativeSetScale", JNIWPEToplevelCache::nativeSetScale));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject, jlong displayPtr)
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto* display = reinterpret_cast<WPEDisplay*>(displayPtr);
        if (display == nullptr)
            return 0;
        return reinterpret_cast<jlong>(wpe_display_create_toplevel(display, 1));
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr) noexcept
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel != nullptr)
            g_object_unref(toplevel);
    }

    static void nativeSurfaceCreated(JNIEnv* env, jobject, jlong nativePtr, JNISurface surface) noexcept
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel == nullptr || !surface)
            return;

        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        if (window != nullptr) {
            wpe_toplevel_android_on_window_created(WPE_TOPLEVEL_ANDROID(toplevel), window);
            ANativeWindow_release(window);
        }
    }

    static void nativeSurfaceDestroyed(JNIEnv*, jobject, jlong nativePtr) noexcept
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel != nullptr)
            wpe_toplevel_android_on_window_destroyed(WPE_TOPLEVEL_ANDROID(toplevel));
    }

    static void nativeSetScale(JNIEnv*, jobject, jlong nativePtr, jfloat scale) noexcept
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto* toplevel = reinterpret_cast<WPEToplevel*>(nativePtr);
        if (toplevel == nullptr)
            return;

        wpe_toplevel_scale_changed(toplevel, scale);

        WPEDisplay* display = wpe_toplevel_get_display(toplevel);
        WPEScreen* screen = (display != nullptr) ? wpe_display_get_screen(display, 0) : nullptr;
        if (screen != nullptr)
            wpe_screen_set_scale(screen, scale);
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
