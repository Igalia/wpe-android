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
#include "WPEKeymapAndroid.h"

#include <algorithm>
#include <array>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEView, "org/wpewebkit/wpe/WPEView");

namespace WebKit {

class JNIWPEViewCache final : public JNI::TypedClass<JNIWPEView> {
public:
    JNIWPEViewCache()
        : JNI::TypedClass<JNIWPEView>(true)
    {
        registerNativeMethods(JNI::NativeMethod<void(jlong, jlong)>("nativeSetToplevel", nativeSetToplevel),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeResized", nativeResized),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetMapped", nativeSetMapped),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetVisible", nativeSetVisible),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetFocused", nativeSetFocused),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jintArray, jfloatArray, jfloatArray)>(
                "nativeDispatchTouchEvent", nativeDispatchTouchEvent),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jint, jint)>(
                "nativeDispatchKeyEvent", nativeDispatchKeyEvent),
            JNI::StaticNativeMethod<jboolean()>(
                "nativeUsesUnbufferedTouchDispatch", nativeUsesUnbufferedTouchDispatch));
    }

private:
    static jboolean nativeUsesUnbufferedTouchDispatch(JNIEnv*, jclass)
    {
        std::array<char, PROP_VALUE_MAX> value {};
        __system_property_get("debug.wpe.touch.buffered", value.data());
        return value[0] == '1' ? JNI_FALSE : JNI_TRUE;
    }

    static void nativeSetToplevel(JNIEnv*, jobject, jlong viewPtr, jlong toplevelPtr)
    {
        wpe_view_set_toplevel(JNI::from_jlong<WPEView>(viewPtr), JNI::from_jlong<WPEToplevel>(toplevelPtr));
    }

    static void nativeResized(JNIEnv*, jobject, jlong viewPtr, jint width, jint height)
    {
        wpe_view_resized(JNI::from_jlong<WPEView>(viewPtr), static_cast<uint32_t>(std::max(0, width)),
            static_cast<uint32_t>(std::max(0, height)));
    }

    static void nativeSetMapped(JNIEnv*, jobject, jlong viewPtr, jboolean mapped)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        mapped ? wpe_view_map(view) : wpe_view_unmap(view);
    }

    static void nativeSetVisible(JNIEnv*, jobject, jlong viewPtr, jboolean visible)
    {
        wpe_view_set_visible(JNI::from_jlong<WPEView>(viewPtr), visible ? TRUE : FALSE);
    }

    static void nativeSetFocused(JNIEnv*, jobject, jlong viewPtr, jboolean focused)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        if (focused == static_cast<jboolean>(wpe_view_get_has_focus(view)))
            return;
        focused ? wpe_view_focus_in(view) : wpe_view_focus_out(view);
    }

    // wpe_event_set_time_usec() is newer than some WebKit builds wpeview runs with, so it is looked up
    // at run time. debug.wpe.touch.usec=0 keeps touch events at whole milliseconds.
    using SetTimeUsecFunction = void (*)(WPEEvent*, guint64);
    static SetTimeUsecFunction setTimeUsecFunction()
    {
        static const SetTimeUsecFunction function = []() -> SetTimeUsecFunction {
            std::array<char, PROP_VALUE_MAX> value {};
            if (__system_property_get("debug.wpe.touch.usec", value.data()) > 0 && value[0] == '0')
                return nullptr;
            return reinterpret_cast<SetTimeUsecFunction>(dlsym(RTLD_DEFAULT, "wpe_event_set_time_usec"));
        }();
        return function;
    }

    // timeNanos is the MotionEvent time, CLOCK_MONOTONIC: touch panels report at 120-240 Hz, where
    // whole milliseconds are a large part of the interval between samples.
    static void nativeDispatchTouchEvent(JNIEnv* env, jobject, jlong viewPtr, jlong timeNanos, jint type,
        jint pointerCount, jintArray ids, jfloatArray xs, jfloatArray ys)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        auto setTimeUsec = setTimeUsecFunction();
        JNI::ScalarArray<jint> pointerIds(ids);
        JNI::ScalarArray<jfloat> pointerXs(xs);
        JNI::ScalarArray<jfloat> pointerYs(ys);

        auto eventType = static_cast<WPEEventType>(type);
        for (int i = 0; i < pointerCount; i++) {
            g_autoptr(WPEEvent) event = wpe_event_touch_new(eventType, view, WPE_INPUT_SOURCE_TOUCHSCREEN,
                static_cast<uint32_t>(timeNanos / 1000000), WPEModifiers {},
                static_cast<uint32_t>(pointerIds.getReadOnlyContent()[i]),
                static_cast<double>(pointerXs.getReadOnlyContent()[i]),
                static_cast<double>(pointerYs.getReadOnlyContent()[i]));
            if (setTimeUsec)
                setTimeUsec(event, static_cast<guint64>(timeNanos / 1000));
            wpe_view_event(view, event);
        }

        UNUSED_PARAM(env);
    }

    static void nativeDispatchKeyEvent(
        JNIEnv*, jobject, jlong viewPtr, jlong time, jint type, jint keyCode, jint unicodeChar, jint metaState)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        uint32_t hardwareKeyCode = androidToXkbKeycode(keyCode);
        uint32_t keySym = androidToKeysym(keyCode, unicodeChar);
        WPEModifiers modifiers = androidToWpeModifiers(metaState);

        g_autoptr(WPEEvent) event = wpe_event_keyboard_new(static_cast<WPEEventType>(type), view,
            WPE_INPUT_SOURCE_KEYBOARD, static_cast<uint32_t>(time), modifiers, hardwareKeyCode, keySym);
        wpe_view_event(view, event);
    }
};

static const JNIWPEViewCache& getJNIWPEViewCache()
{
    static const JNIWPEViewCache s_singleton;
    return s_singleton;
}

void configureWPEViewJNIMappings()
{
    getJNIWPEViewCache();
}
} // namespace WebKit
