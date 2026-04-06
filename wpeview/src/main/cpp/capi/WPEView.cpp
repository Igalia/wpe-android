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
#include "WPEKeymapAndroid.h"

#include <algorithm>
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
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jintArray, jfloatArray, jfloatArray)>(
                "nativeDispatchTouchEvent", nativeDispatchTouchEvent),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jint, jint)>(
                "nativeDispatchKeyEvent", nativeDispatchKeyEvent));
    }

private:
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

    static void nativeDispatchTouchEvent(JNIEnv* env, jobject, jlong viewPtr, jlong time, jint type, jint pointerCount,
        jintArray ids, jfloatArray xs, jfloatArray ys)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        JNI::ScalarArray<jint> pointerIds(ids);
        JNI::ScalarArray<jfloat> pointerXs(xs);
        JNI::ScalarArray<jfloat> pointerYs(ys);

        auto eventType = static_cast<WPEEventType>(type);
        for (int i = 0; i < pointerCount; i++) {
            g_autoptr(WPEEvent) event = wpe_event_touch_new(eventType, view, WPE_INPUT_SOURCE_TOUCHSCREEN,
                static_cast<uint32_t>(time), WPEModifiers {}, static_cast<uint32_t>(pointerIds.getReadOnlyContent()[i]),
                static_cast<double>(pointerXs.getReadOnlyContent()[i]),
                static_cast<double>(pointerYs.getReadOnlyContent()[i]));
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

const JNIWPEViewCache& getJNIWPEViewCache()
{
    static const JNIWPEViewCache s_singleton;
    return s_singleton;
}

void configureWPEViewJNIMappings()
{
    getJNIWPEViewCache();
}
} // namespace WebKit
