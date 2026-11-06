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
#include "Logging.h"
#include "WPEToplevelAndroid.h"

#include <android/native_window_jni.h>
#include <jni.h>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEToplevel, "org/wpewebkit/wpe/WPEToplevel");
DECLARE_JNI_CLASS_SIGNATURE(JNIWPEToplevelListener, "org/wpewebkit/wpe/WPEToplevel$ListenerHolder");
DECLARE_JNI_CLASS_SIGNATURE(JNISurface, "android/view/Surface");

namespace WebKit {

class JNIWPEToplevelListenerCache final : public JNI::TypedClass<JNIWPEToplevelListener> {
public:
    JNIWPEToplevelListenerCache()
        : JNI::TypedClass<JNIWPEToplevelListener>(true)
        , m_onFullscreenRequested(getMethod<void(jboolean)>("onFullscreenRequested"))
    {
    }

    void onFullscreenRequested(JNIWPEToplevelListener listener, gboolean fullscreen) const
    {
        if (!m_onFullscreenRequested.invoke(listener, static_cast<jboolean>(fullscreen ? JNI_TRUE : JNI_FALSE)))
            Logging::logError("cannot call WPEToplevel fullscreen callback");
    }

private:
    // NOLINTNEXTLINE(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jboolean)> m_onFullscreenRequested;
};

static const JNIWPEToplevelListenerCache& getJNIListenerCache()
{
    static const JNIWPEToplevelListenerCache s_singleton;
    return s_singleton;
}

namespace {

    struct ListenerHolder {
        JNI::GlobalRef<JNIWPEToplevelListener> ref;
    };

    GQuark listenerQuark()
    {
        static GQuark quark = g_quark_from_static_string("wpe-toplevel-android-listener");
        return quark;
    }

    void destroyListenerHolder(gpointer ptr) noexcept
    {
        delete static_cast<ListenerHolder*>(ptr);
    }

    void onFullscreenRequestedThunk(gboolean fullscreen, gpointer userData)
    {
        auto* holder = static_cast<ListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNIListenerCache().onFullscreenRequested(holder->ref.get(), fullscreen);
    }

} // namespace

class JNIWPEToplevelCache final : public JNI::TypedClass<JNIWPEToplevel> {
public:
    JNIWPEToplevelCache()
        : JNI::TypedClass<JNIWPEToplevel>(true)
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jlong, JNISurface)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeSetPhysicalSize", nativeSetPhysicalSize),
            JNI::NativeMethod<void(jlong, jintArray)>("nativeGetSize", nativeGetSize),
            JNI::NativeMethod<jfloat(jlong)>("nativeGetScale", nativeGetScale),
            JNI::NativeMethod<void(jlong, JNISurface)>("nativeSetSurface", nativeSetSurface),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetWindowFocused", nativeSetWindowFocused),
            JNI::NativeMethod<void(jlong, jlong)>("nativeSetScreen", nativeSetScreen),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetFullscreen", nativeSetFullscreen),
            JNI::NativeMethod<jboolean(jlong)>("nativeIsFullscreen", nativeIsFullscreen),
            JNI::NativeMethod<void(jlong, JNIWPEToplevelListener)>("nativeSetListener", nativeSetListener),
            JNI::NativeMethod<void(jlong, jfloat, jfloat, jfloat)>(
                "nativeSetBackgroundColor", nativeSetBackgroundColor));
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

    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr)
    {
        g_object_unref(JNI::from_jlong<WPEToplevel>(nativePtr));
    }

    static void nativeSetPhysicalSize(JNIEnv*, jobject, jlong nativePtr, jint width, jint height)
    {
        wpe_toplevel_android_set_physical_size(
            WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr)), width, height);
    }

    static void nativeGetSize(JNIEnv* env, jobject, jlong nativePtr, jintArray out)
    {
        int width = 0, height = 0;
        wpe_toplevel_get_size(JNI::from_jlong<WPEToplevel>(nativePtr), &width, &height);
        jint data[2] = {static_cast<jint>(width), static_cast<jint>(height)};
        env->SetIntArrayRegion(out, 0, 2, data);
    }

    static jfloat nativeGetScale(JNIEnv*, jobject, jlong nativePtr)
    {
        return static_cast<jfloat>(wpe_toplevel_get_scale(JNI::from_jlong<WPEToplevel>(nativePtr)));
    }

    static void nativeSetSurface(JNIEnv* env, jobject, jlong nativePtr, JNISurface surface)
    {
        auto* toplevel = JNI::from_jlong<WPEToplevel>(nativePtr);
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;

        wpe_toplevel_android_set_window(WPE_TOPLEVEL_ANDROID(toplevel), window);

        if (window)
            ANativeWindow_release(window);
    }

    static void nativeSetBackgroundColor(JNIEnv*, jobject, jlong nativePtr, jfloat red, jfloat green, jfloat blue)
    {
        wpe_toplevel_android_set_background_color(
            WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr)), red, green, blue);
    }

    static void nativeSetWindowFocused(JNIEnv*, jobject, jlong nativePtr, jboolean focused)
    {
        wpe_toplevel_android_set_window_focused(
            WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr)), focused ? TRUE : FALSE);
    }

    static void nativeSetScreen(JNIEnv*, jobject, jlong nativePtr, jlong screenPtr)
    {
        wpe_toplevel_android_set_screen(
            WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr)), JNI::from_jlong<WPEScreen>(screenPtr));
    }

    static void nativeSetFullscreen(JNIEnv*, jobject, jlong nativePtr, jboolean fullscreen)
    {
        wpe_toplevel_android_set_fullscreen(
            WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr)), fullscreen ? TRUE : FALSE);
    }

    static jboolean nativeIsFullscreen(JNIEnv*, jobject, jlong nativePtr)
    {
        auto state = wpe_toplevel_get_state(JNI::from_jlong<WPEToplevel>(nativePtr));
        return (state & WPE_TOPLEVEL_STATE_FULLSCREEN) ? JNI_TRUE : JNI_FALSE;
    }

    static void nativeSetListener(JNIEnv* env, jobject, jlong nativePtr, JNIWPEToplevelListener listener)
    {
        auto* toplevel = WPE_TOPLEVEL_ANDROID(JNI::from_jlong<WPEToplevel>(nativePtr));
        if (listener) {
            auto* holder = new ListenerHolder {JNI::GlobalRef<JNIWPEToplevelListener>(env, listener)};
            // qdata owns the holder: the destroy notify runs on toplevel dispose and on listener replacement.
            g_object_set_qdata_full(G_OBJECT(toplevel), listenerQuark(), holder, destroyListenerHolder);
            wpe_toplevel_android_set_fullscreen_callback(toplevel, onFullscreenRequestedThunk, holder);
        } else {
            wpe_toplevel_android_set_fullscreen_callback(toplevel, nullptr, nullptr);
            g_object_set_qdata(G_OBJECT(toplevel), listenerQuark(), nullptr);
        }
    }
};

static const JNIWPEToplevelCache& getJNIWPEToplevelCache()
{
    static const JNIWPEToplevelCache s_singleton;
    return s_singleton;
}

void configureWPEToplevelJNIMappings()
{
    getJNIListenerCache();
    getJNIWPEToplevelCache();
}
} // namespace WebKit
