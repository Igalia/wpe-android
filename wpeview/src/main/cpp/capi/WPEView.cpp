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
        registerNativeMethods(
            JNI::NativeMethod<void(jlong, jlong)>("nativeSetToplevel", JNIWPEViewCache::nativeSetToplevel),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeResized", JNIWPEViewCache::nativeResized),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetMapped", JNIWPEViewCache::nativeSetMapped),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jintArray, jfloatArray, jfloatArray)>(
                "nativeDispatchTouchEvent", JNIWPEViewCache::nativeDispatchTouchEvent),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jint, jint)>(
                "nativeDispatchKeyEvent", JNIWPEViewCache::nativeDispatchKeyEvent));
    }

private:
    static void nativeSetToplevel(JNIEnv*, jobject, jlong viewPtr, jlong toplevelPtr) noexcept
    {
        auto* view = reinterpret_cast<WPEView*>(viewPtr);
        auto* toplevel = reinterpret_cast<WPEToplevel*>(toplevelPtr);
        if (view)
            wpe_view_set_toplevel(view, toplevel);
    }

    static void nativeResized(JNIEnv*, jobject, jlong viewPtr, jint width, jint height) noexcept
    {
        auto* view = reinterpret_cast<WPEView*>(viewPtr);
        if (!view)
            return;

        wpe_view_resized(view, static_cast<uint32_t>(std::max(0, width)), static_cast<uint32_t>(std::max(0, height)));
    }

    static void nativeSetMapped(JNIEnv*, jobject, jlong viewPtr, jboolean mapped) noexcept
    {
        auto* view = reinterpret_cast<WPEView*>(viewPtr);
        if (view)
            mapped ? wpe_view_map(view) : wpe_view_unmap(view);
    }

    static void nativeDispatchTouchEvent(JNIEnv*, jobject, jlong viewPtr, jlong time, jint type, jint pointerCount,
        jintArray ids, jfloatArray xs, jfloatArray ys) noexcept
    {
        auto* view = reinterpret_cast<WPEView*>(viewPtr);
        if (!view)
            return;
        if (!ids || !xs || !ys || pointerCount <= 0)
            return;

        JNI::ScalarArray<jint> pointerIds(ids);
        JNI::ScalarArray<jfloat> pointerXs(xs);
        JNI::ScalarArray<jfloat> pointerYs(ys);
        if (pointerIds.getSize() < static_cast<size_t>(pointerCount)
            || pointerXs.getSize() < static_cast<size_t>(pointerCount)
            || pointerYs.getSize() < static_cast<size_t>(pointerCount))
            return;

        for (int i = 0; i < pointerCount; i++) {
            // NOLINTBEGIN(clang-analyzer-optin.core.EnumCastOutOfRange)
            WPEEvent* event = wpe_event_touch_new(static_cast<WPEEventType>(type), view, WPE_INPUT_SOURCE_TOUCHSCREEN,
                static_cast<uint32_t>(time), static_cast<WPEModifiers>(0),
                static_cast<uint32_t>(pointerIds.getReadOnlyContent()[i]),
                static_cast<double>(pointerXs.getReadOnlyContent()[i]),
                static_cast<double>(pointerYs.getReadOnlyContent()[i]));
            // NOLINTEND(clang-analyzer-optin.core.EnumCastOutOfRange)
            wpe_view_event(view, event);
            wpe_event_unref(event);
        }
    }

    static void nativeDispatchKeyEvent(
        JNIEnv*, jobject, jlong viewPtr, jlong time, jint type, jint keyCode, jint unicodeChar, jint metaState) noexcept
    {
        auto* view = reinterpret_cast<WPEView*>(viewPtr);
        if (!view)
            return;

        uint32_t hardwareKeyCode = androidToXkbKeycode(keyCode);
        uint32_t keySym = androidToKeysym(keyCode, unicodeChar);
        WPEModifiers modifiers = androidToWpeModifiers(metaState);

        WPEEvent* event = wpe_event_keyboard_new(static_cast<WPEEventType>(type), view, WPE_INPUT_SOURCE_KEYBOARD,
            static_cast<uint32_t>(time), modifiers, hardwareKeyCode, keySym);
        wpe_view_event(view, event);
        wpe_event_unref(event);
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
