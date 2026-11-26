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
#include "WPEGestureControllerAndroid.h"
#include "WPEKeymapAndroid.h"

#include <algorithm>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEView, "org/wpewebkit/wpe/WPEView");
DECLARE_JNI_CLASS_SIGNATURE(JNIWPEViewTouchListener, "org/wpewebkit/wpe/WPEView$TouchListenerHolder");

namespace WebKit {

class JNIWPEViewTouchListenerCache final : public JNI::TypedClass<JNIWPEViewTouchListener> {
public:
    JNIWPEViewTouchListenerCache()
        : JNI::TypedClass<JNIWPEViewTouchListener>(true)
        , m_onPageCancelledTap(getMethod<void(jlong)>("onPageCancelledTap"))
    {
    }

    void onPageCancelledTap(JNIWPEViewTouchListener listener, guint32 downTime) const
    {
        if (!m_onPageCancelledTap.invoke(listener, static_cast<jlong>(downTime)))
            Logging::logError("cannot call WPEView tap-cancelled callback");
    }

private:
    // NOLINTNEXTLINE(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jlong)> m_onPageCancelledTap;
};

static const JNIWPEViewTouchListenerCache& getJNITouchListenerCache()
{
    static const JNIWPEViewTouchListenerCache s_singleton;
    return s_singleton;
}

namespace {

    struct TouchListenerHolder {
        JNI::GlobalRef<JNIWPEViewTouchListener> ref;
    };

    GQuark touchListenerQuark()
    {
        static GQuark quark = g_quark_from_static_string("wpe-view-android-touch-listener");
        return quark;
    }

    void destroyTouchListenerHolder(gpointer ptr) noexcept
    {
        delete static_cast<TouchListenerHolder*>(ptr);
    }

    void onPageCancelledTapThunk(guint32 downTime, gpointer userData)
    {
        auto* holder = static_cast<TouchListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNITouchListenerCache().onPageCancelledTap(holder->ref.get(), downTime);
    }

} // namespace

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
            JNI::NativeMethod<void(jlong, JNIWPEViewTouchListener)>("nativeSetTouchListener", nativeSetTouchListener),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jint, jint, jfloat, jfloat, jfloat, jfloat)>(
                "nativeDispatchPointerMoveEvent", nativeDispatchPointerMoveEvent),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jint, jint, jint, jfloat, jfloat)>(
                "nativeDispatchPointerButtonEvent", nativeDispatchPointerButtonEvent),
            JNI::NativeMethod<void(jlong, jlong, jint, jint, jint, jfloat, jfloat, jboolean, jboolean, jfloat, jfloat)>(
                "nativeDispatchScrollEvent", nativeDispatchScrollEvent));
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

    static void nativeDispatchTouchEvent(JNIEnv* env, jobject, jlong viewPtr, jlong time, jint type, jint pointerCount,
        jintArray ids, jfloatArray xs, jfloatArray ys)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        JNI::ScalarArray<jint> pointerIds(ids);
        JNI::ScalarArray<jfloat> pointerXs(xs);
        JNI::ScalarArray<jfloat> pointerYs(ys);

        auto eventType = static_cast<WPEEventType>(type);
        auto* gestureController = wpe_view_get_gesture_controller(view);
        for (int i = 0; i < pointerCount; i++) {
            g_autoptr(WPEEvent) event = wpe_event_touch_new(eventType, view, WPE_INPUT_SOURCE_TOUCHSCREEN,
                static_cast<uint32_t>(time), WPEModifiers {}, static_cast<uint32_t>(pointerIds.getReadOnlyContent()[i]),
                static_cast<double>(pointerXs.getReadOnlyContent()[i]),
                static_cast<double>(pointerYs.getReadOnlyContent()[i]));
            // Before WebKit, which only passes on the events that the page did not cancel.
            if (gestureController && WPE_IS_GESTURE_CONTROLLER_ANDROID(gestureController))
                wpe_gesture_controller_android_observe_touch(WPE_GESTURE_CONTROLLER_ANDROID(gestureController), event);
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

    // The gesture controller of the view tells which taps the page cancelled.
    static void nativeSetTouchListener(JNIEnv* env, jobject, jlong viewPtr, JNIWPEViewTouchListener listener)
    {
        auto* gestureController = wpe_view_get_gesture_controller(JNI::from_jlong<WPEView>(viewPtr));
        if (!gestureController || !WPE_IS_GESTURE_CONTROLLER_ANDROID(gestureController))
            return;
        auto* controller = WPE_GESTURE_CONTROLLER_ANDROID(gestureController);
        if (listener) {
            auto* holder = new TouchListenerHolder {JNI::GlobalRef<JNIWPEViewTouchListener>(env, listener)};
            // qdata owns the holder: the destroy notify runs on controller dispose and on listener replacement.
            g_object_set_qdata_full(G_OBJECT(controller), touchListenerQuark(), holder, destroyTouchListenerHolder);
            wpe_gesture_controller_android_set_tap_cancelled_callback(controller, onPageCancelledTapThunk, holder);
        } else {
            wpe_gesture_controller_android_set_tap_cancelled_callback(controller, nullptr, nullptr);
            g_object_set_qdata(G_OBJECT(controller), touchListenerQuark(), nullptr);
        }
    }

    // The keys and the mouse buttons held, from the meta state and the button state of Android's events.
    static WPEModifiers pointerModifiers(jint metaState, jint buttonState)
    {
        unsigned modifiers = androidToWpeModifiers(metaState);
        if (buttonState & AMOTION_EVENT_BUTTON_PRIMARY)
            modifiers |= WPE_MODIFIER_POINTER_BUTTON1;
        if (buttonState & AMOTION_EVENT_BUTTON_TERTIARY)
            modifiers |= WPE_MODIFIER_POINTER_BUTTON2;
        if (buttonState & AMOTION_EVENT_BUTTON_SECONDARY)
            modifiers |= WPE_MODIFIER_POINTER_BUTTON3;
        return static_cast<WPEModifiers>(modifiers);
    }

    static void nativeDispatchPointerMoveEvent(JNIEnv*, jobject, jlong viewPtr, jlong time, jint type, jint source,
        jint metaState, jint buttonState, jfloat x, jfloat y, jfloat deltaX, jfloat deltaY)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        g_autoptr(WPEEvent) event
            = wpe_event_pointer_move_new(static_cast<WPEEventType>(type), view, static_cast<WPEInputSource>(source),
                static_cast<uint32_t>(time), pointerModifiers(metaState, buttonState), x, y, deltaX, deltaY);
        wpe_view_event(view, event);
    }

    static void nativeDispatchPointerButtonEvent(JNIEnv*, jobject, jlong viewPtr, jlong time, jint type, jint source,
        jint metaState, jint buttonState, jint androidButton, jfloat x, jfloat y)
    {
        guint button;
        switch (androidButton) {
        case AMOTION_EVENT_BUTTON_PRIMARY:
            button = WPE_BUTTON_PRIMARY;
            break;
        case AMOTION_EVENT_BUTTON_TERTIARY:
            button = WPE_BUTTON_MIDDLE;
            break;
        case AMOTION_EVENT_BUTTON_SECONDARY:
            button = WPE_BUTTON_SECONDARY;
            break;
        default:
            return;
        }

        // A press counts the presses of the button before it, for double clicks, and its release goes with it.
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        auto eventType = static_cast<WPEEventType>(type);
        auto eventTime = static_cast<uint32_t>(time);
        guint pressCount = eventType == WPE_EVENT_POINTER_DOWN
            ? wpe_view_compute_press_count(view, x, y, button, eventTime)
            : wpe_view_get_press_count(view, button);
        g_autoptr(WPEEvent) event = wpe_event_pointer_button_new(eventType, view, static_cast<WPEInputSource>(source),
            eventTime, pointerModifiers(metaState, buttonState), button, x, y, pressCount);
        wpe_view_event(view, event);
    }

    static void nativeDispatchScrollEvent(JNIEnv*, jobject, jlong viewPtr, jlong time, jint source, jint metaState,
        jint buttonState, jfloat deltaX, jfloat deltaY, jboolean preciseDeltas, jboolean isStop, jfloat x, jfloat y)
    {
        auto* view = JNI::from_jlong<WPEView>(viewPtr);
        g_autoptr(WPEEvent) event = wpe_event_scroll_new(view, static_cast<WPEInputSource>(source),
            static_cast<uint32_t>(time), pointerModifiers(metaState, buttonState), deltaX, deltaY,
            preciseDeltas ? TRUE : FALSE, isStop ? TRUE : FALSE, x, y);
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
    getJNITouchListenerCache();
    getJNIWPEViewCache();
}
} // namespace WebKit
