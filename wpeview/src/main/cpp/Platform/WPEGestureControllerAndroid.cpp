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

#include "WPEGestureControllerAndroid.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <optional>
#include <utility>

static guint longPressSignal = 0;

namespace {

struct Position {
    double x;
    double y;
};

double distance(Position a, Position b)
{
    return std::hypot(a.x - b.x, a.y - b.y);
}

// Rounded, as WPE's own controller does, so that the deltas of a drag add up to the whole move.
std::optional<Position> positionOf(WPEEvent* event)
{
    double x;
    double y;
    if (!wpe_event_get_position(event, &x, &y))
        return std::nullopt;
    return Position {std::round(x), std::round(y)};
}

// How far a finger can move, in logical pixels, before a touch is no longer a tap: the touch slop.
double touchSlop(WPEEvent* event)
{
    auto* settings = wpe_display_get_settings(wpe_view_get_display(wpe_event_get_view(event)));
    return wpe_settings_get_uint32(settings, WPE_SETTING_DRAG_THRESHOLD, nullptr);
}

// Whether the event time is the same as or after the other one. Event times are milliseconds since the device
// started, cut to 32 bits, so they wrap around after 49 days.
bool isAtOrAfter(uint32_t time, uint32_t other)
{
    return static_cast<int32_t>(time - other) >= 0;
}

// The time of events made here, on the clock of Android's event times: milliseconds of the monotonic clock.
uint32_t now()
{
    return static_cast<uint32_t>(g_get_monotonic_time() / 1000);
}

// A secondary click at a point, from the touchscreen, as WebKit's GTK port makes of a long press. WebKit gives a click
// from the touchscreen to the touch that is down there, so the page gets its contextmenu event as a touch's.
void sendSecondaryClick(WPEView* view, Position position)
{
    uint32_t time = now();
    g_autoptr(WPEEvent) move = wpe_event_pointer_move_new(WPE_EVENT_POINTER_MOVE, view, WPE_INPUT_SOURCE_TOUCHSCREEN,
        time, WPEModifiers {}, position.x, position.y, 0, 0);
    wpe_view_event(view, move);
    g_autoptr(WPEEvent) down = wpe_event_pointer_button_new(WPE_EVENT_POINTER_DOWN, view, WPE_INPUT_SOURCE_TOUCHSCREEN,
        time, WPE_MODIFIER_POINTER_BUTTON3, 3, position.x, position.y, 1);
    wpe_view_event(view, down);
    g_autoptr(WPEEvent) up = wpe_event_pointer_button_new(
        WPE_EVENT_POINTER_UP, view, WPE_INPUT_SOURCE_TOUCHSCREEN, time, WPEModifiers {}, 3, position.x, position.y, 1);
    wpe_view_event(view, up);
}

// A touch of one finger, as the view sent it.
struct Touch {
    uint32_t sequenceId;
    uint32_t downTime;
    Position down;
    double slop;
    // Whether the finger went farther than the touch slop, also during moves that the page cancelled.
    bool leftSlop {false};
    // When the finger was lifted, if it was.
    std::optional<uint32_t> endTime;
    // Whether another finger was down at some point during the touch.
    bool withOtherFingers {false};
    // Whether the app's context menu took the touch over, so that the rest of it goes nowhere.
    bool takenByMenu {false};
};

// A long press that asked the page for its context menu, until WebKit asks the app for it.
struct LongPress {
    uint32_t sequenceId;
    uint32_t downTime;
    Position position;
};

class Detector {
public:
    explicit Detector(WPEGestureControllerAndroid* controller)
        : m_controller(controller)
    {
    }

    ~Detector()
    {
        stopLongPressTimer();
        if (m_clickSource)
            g_source_remove(m_clickSource);
    }

    Detector(const Detector&) = delete;
    Detector& operator=(const Detector&) = delete;
    Detector(Detector&&) = delete;
    Detector& operator=(Detector&&) = delete;

    // Returns whether WebKit gets the event: not the rest of a touch that the app's context menu took.
    bool observe(WPEEvent* event)
    {
        auto position = positionOf(event);
        if (!position)
            return true;

        uint32_t sequenceId = wpe_event_touch_get_sequence_id(event);
        switch (wpe_event_get_event_type(event)) {
        case WPE_EVENT_TOUCH_DOWN: {
            Touch touch {
                sequenceId, wpe_event_get_time(event), *position, touchSlop(event), false, std::nullopt, false};
            // The fingers already down and this one all touch together.
            for (auto& other : m_touches) {
                if (other.sequenceId != sequenceId && !other.endTime) {
                    other.withOtherFingers = true;
                    touch.withOtherFingers = true;
                }
            }
            forgetEndedLongPress();
            m_touches.push_back(touch);
            if (m_touches.size() > maximumTouches)
                m_touches.pop_front();
            return true;
        }
        case WPE_EVENT_TOUCH_MOVE:
        case WPE_EVENT_TOUCH_UP:
        case WPE_EVENT_TOUCH_CANCEL:
            for (auto it = m_touches.rbegin(); it != m_touches.rend(); ++it) {
                if (it->sequenceId != sequenceId)
                    continue;
                if (distance(*position, it->down) > it->slop)
                    it->leftSlop = true;
                if (wpe_event_get_event_type(event) != WPE_EVENT_TOUCH_MOVE)
                    it->endTime = wpe_event_get_time(event);
                return !it->takenByMenu;
            }
            return true;
        default:
            return true;
        }
    }

    void setLongPressTimeout(uint32_t timeout)
    {
        m_longPressTimeout = timeout;
    }

    // WebKit asks the app for the context menu of the page. Returns where the long press that asked for it sent its
    // click, if one waits for its menu; each long press is taken once. When the app's menu takes the touch over, the
    // page gets a touch cancel, and the rest of the touch goes nowhere, unless the finger was lifted already.
    std::optional<Position> takeLongPress(bool takeTouch)
    {
        auto longPress = std::exchange(m_longPress, std::nullopt);
        if (!longPress)
            return std::nullopt;
        if (takeTouch)
            cancelTouchOf(*longPress);
        return longPress->position;
    }

    bool handleEvent(WPEEvent* event)
    {
        auto position = positionOf(event);
        uint32_t sequenceId = wpe_event_touch_get_sequence_id(event);
        auto type = wpe_event_get_event_type(event);

        if (type == WPE_EVENT_TOUCH_DOWN) {
            // As WPE's own controller, follow the first finger only, while it is down.
            uint32_t time = wpe_event_get_time(event);
            if (m_sequenceId && *m_sequenceId != sequenceId && !touchEndedBy(*m_sequenceId, m_downTime, time))
                return false;
            reset();
            if (position) {
                m_sequenceId = sequenceId;
                m_downTime = time;
                m_downPosition = *position;
                m_slop = touchSlop(event);
                m_gesture = WPE_GESTURE_TAP;
                m_view = wpe_event_get_view(event);
                startLongPressTimer();
            }
            return true;
        }

        if (!m_sequenceId || *m_sequenceId != sequenceId)
            return false;
        if (!position) {
            reset();
            return false;
        }
        Position point = *position;

        // A finger reuses its sequence id for every touch. When the page cancelled the touch down of a newer one,
        // only its later events come here; they end the touch followed so far, which can't go on.
        const Touch* touch = touchFor(event);
        if (touch && touch->downTime != m_downTime) {
            reset();
            return false;
        }

        switch (type) {
        case WPE_EVENT_TOUCH_MOVE:
            if (m_gesture == WPE_GESTURE_TAP && distance(point, m_downPosition) > m_slop) {
                stopLongPressTimer();
                // A page that cancelled a move of this touch keeps it from scrolling.
                if (m_pageCancelledEvent)
                    m_gesture = WPE_GESTURE_NONE;
                else {
                    m_gesture = WPE_GESTURE_DRAG;
                    m_dragBegin = true;
                    m_nextDeltaReferencePosition = m_downPosition;
                }
            } else if (m_gesture == WPE_GESTURE_DRAG)
                m_dragBegin = false;
            if (m_gesture == WPE_GESTURE_DRAG)
                updateDelta(point);
            break;
        case WPE_EVENT_TOUCH_UP:
            // The view's record of the touch includes the moves that the page cancelled, this touch up, and the other
            // fingers that came down meanwhile. As in Android's GestureDetector, a touch with other fingers is no tap,
            // so that a pinch with a finger that hardly moves does not click.
            if (m_gesture == WPE_GESTURE_TAP
                && (touch ? touch->leftSlop || touch->withOtherFingers : distance(point, m_downPosition) > m_slop))
                m_gesture = WPE_GESTURE_NONE;
            else if (m_gesture == WPE_GESTURE_DRAG)
                updateDelta(point);
            longPressAtTouchUp(sequenceId, touch);
            // A new touch can start now. The gesture stays for WebKit to read.
            m_sequenceId = std::nullopt;
            stopLongPressTimer();
            break;
        case WPE_EVENT_TOUCH_CANCEL:
            reset();
            break;
        default:
            return false;
        }
        return true;
    }

    // The page cancelled a touch event. WebKit does not say which one, nor of which finger; while a touch is
    // followed, it is mostly one of its moves, or its touch up, but it can be an event of another finger. A
    // cancelled move keeps the touch from scrolling, and stops a drag that began, but leaves a tap a tap; after a
    // cancelled touch up, the touch up does not come here, so there is no tap either. WPE's own controller drops
    // the whole touch for any of them.
    void cancel()
    {
        if (!m_sequenceId)
            return;
        m_pageCancelledEvent = true;
        if (m_gesture == WPE_GESTURE_DRAG)
            m_gesture = WPE_GESTURE_NONE;
    }

    WPEGesture gesture() const
    {
        return m_gesture;
    }

    // Where the touch started, for a tap and for a drag, as in WPE's own controller.
    std::optional<Position> position() const
    {
        if (m_gesture == WPE_GESTURE_NONE)
            return std::nullopt;
        return m_downPosition;
    }

    std::optional<Position> delta() const
    {
        return m_delta;
    }

    bool dragBegin() const
    {
        return m_dragBegin;
    }

private:
    // Enough for the touches that WebKit has not passed on yet.
    static constexpr size_t maximumTouches = 16;

    const Touch* touchFor(WPEEvent* event) const
    {
        uint32_t sequenceId = wpe_event_touch_get_sequence_id(event);
        uint32_t time = wpe_event_get_time(event);
        for (auto it = m_touches.rbegin(); it != m_touches.rend(); ++it) {
            if (it->sequenceId == sequenceId && isAtOrAfter(time, it->downTime))
                return &*it;
        }
        return nullptr;
    }

    // Whether the finger of a touch was lifted by the given time. WebKit does not pass the touch up on when the page
    // cancelled it. The view's record runs ahead of WebKit, so a finger lifted after that time still counts as down.
    bool touchEndedBy(uint32_t sequenceId, uint32_t downTime, uint32_t time) const
    {
        for (auto it = m_touches.rbegin(); it != m_touches.rend(); ++it) {
            if (it->sequenceId == sequenceId && it->downTime == downTime)
                return it->endTime && isAtOrAfter(time, *it->endTime);
        }
        return false;
    }

    Touch* findTouch(uint32_t sequenceId, uint32_t downTime)
    {
        for (auto it = m_touches.rbegin(); it != m_touches.rend(); ++it) {
            if (it->sequenceId == sequenceId && it->downTime == downTime)
                return &*it;
        }
        return nullptr;
    }

    void updateDelta(Position position)
    {
        m_delta = Position {position.x - m_nextDeltaReferencePosition.x, position.y - m_nextDeltaReferencePosition.y};
        m_nextDeltaReferencePosition = position;
    }

    // Counted from the touch down, since WebKit passes a touch on only after the page had it. A touch down time ahead
    // of the clock, as made up events can have, counts as now.
    void startLongPressTimer()
    {
        stopLongPressTimer();
        auto elapsed = std::clamp<int64_t>(static_cast<int32_t>(now() - m_downTime), 0, m_longPressTimeout);
        m_longPressSource = g_timeout_add(
            m_longPressTimeout - static_cast<uint32_t>(elapsed),
            [](gpointer data) -> gboolean {
                auto* detector = static_cast<Detector*>(data);
                detector->m_longPressSource = 0;
                detector->longPressed();
                return G_SOURCE_REMOVE;
            },
            this);
    }

    void stopLongPressTimer()
    {
        if (m_longPressSource)
            g_source_remove(m_longPressSource);
        m_longPressSource = 0;
    }

    // The finger stayed down for the long press timeout. The touch is no tap any more, and the page is asked for its
    // context menu, while the finger is still down.
    void longPressed()
    {
        if (!m_sequenceId || m_gesture != WPE_GESTURE_TAP || !m_view)
            return;
        uint32_t sequenceId = *m_sequenceId;
        // The view's record runs ahead of WebKit: the finger may have gone away, or another one come, meanwhile. One
        // lifted after the timeout made a long press all the same.
        const Touch* touch = findTouch(sequenceId, m_downTime);
        if (touch
            && (touch->leftSlop || touch->withOtherFingers
                || (touch->endTime && !isAtOrAfter(*touch->endTime, m_downTime + m_longPressTimeout))))
            return;
        m_gesture = WPE_GESTURE_NONE;
        m_longPress = LongPress {sequenceId, m_downTime, m_downPosition};
        sendSecondaryClick(m_view, m_downPosition);
        g_signal_emit(m_controller, longPressSignal, 0);
    }

    // A finger held for the long press timeout, and lifted before WebKit passed its touch down on, as on a busy page,
    // made a long press too, whether or not the timer ran before this touch up. The page is asked for its context
    // menu once WebKit has handled the touch up, as WebKit's GTK port does for every long press.
    void longPressAtTouchUp(uint32_t sequenceId, const Touch* touch)
    {
        if (m_gesture != WPE_GESTURE_TAP || !m_longPressSource || !m_view || !touch || !touch->endTime
            || !isAtOrAfter(*touch->endTime, m_downTime + m_longPressTimeout))
            return;
        m_gesture = WPE_GESTURE_NONE;
        m_longPress = LongPress {sequenceId, m_downTime, m_downPosition};
        m_clickSource = g_idle_add(
            [](gpointer data) -> gboolean {
                auto* detector = static_cast<Detector*>(data);
                detector->m_clickSource = 0;
                if (detector->m_view && detector->m_longPress) {
                    sendSecondaryClick(detector->m_view, detector->m_longPress->position);
                    g_signal_emit(detector->m_controller, longPressSignal, 0);
                }
                return G_SOURCE_REMOVE;
            },
            this);
    }

    // The page gets a touch cancel for the touch of a long press, which the app's menu takes over, and the rest of the
    // touch goes nowhere. Sent while WebKit asks for the menu, so that no event of the touch comes before it.
    void cancelTouchOf(const LongPress& longPress)
    {
        Touch* touch = findTouch(longPress.sequenceId, longPress.downTime);
        if (!touch || touch->endTime || !m_view)
            return;
        touch->takenByMenu = true;
        g_autoptr(WPEEvent) cancel = wpe_event_touch_new(WPE_EVENT_TOUCH_CANCEL, m_view, WPE_INPUT_SOURCE_TOUCHSCREEN,
            now(), WPEModifiers {}, longPress.sequenceId, longPress.position.x, longPress.position.y);
        wpe_view_event(m_view, cancel);
    }

    // A new touch comes: a long press whose finger was lifted no longer waits for its menu, which the page may never
    // ask for, as when it cancels its contextmenu event.
    void forgetEndedLongPress()
    {
        if (!m_longPress)
            return;
        const Touch* touch = findTouch(m_longPress->sequenceId, m_longPress->downTime);
        if (!touch || touch->endTime)
            m_longPress = std::nullopt;
    }

    void reset()
    {
        stopLongPressTimer();
        m_sequenceId = std::nullopt;
        m_downTime = 0;
        m_downPosition = {};
        m_slop = 0;
        m_gesture = WPE_GESTURE_NONE;
        m_pageCancelledEvent = false;
        m_nextDeltaReferencePosition = {};
        m_delta = std::nullopt;
        m_dragBegin = false;
    }

    // The touches the view sent, newest last. WebKit passes the events on after the page had them, so the touch it
    // is at can be behind the newest one.
    std::deque<Touch> m_touches;

    // The touch followed, from the touch down that the page did not cancel, and what it is so far.
    std::optional<uint32_t> m_sequenceId;
    uint32_t m_downTime {0};
    Position m_downPosition {};
    double m_slop {0};
    WPEGesture m_gesture {WPE_GESTURE_NONE};
    bool m_pageCancelledEvent {false};
    Position m_nextDeltaReferencePosition {};
    std::optional<Position> m_delta;
    bool m_dragBegin {false};

    // The controller owns the detector, and the view owns the controller.
    WPEGestureControllerAndroid* m_controller;
    WPEView* m_view {nullptr};
    // Android's default, until the view gives its own.
    uint32_t m_longPressTimeout {400};
    guint m_longPressSource {0};
    // The click of a long press found at its touch up, sent once WebKit has handled the touch up.
    guint m_clickSource {0};
    std::optional<LongPress> m_longPress;
};

} // namespace

struct _WPEGestureControllerAndroid {
    GObject parent;
    Detector* detector;
};

static void wpe_gesture_controller_android_interface_init(WPEGestureControllerInterface* interface);

G_DEFINE_FINAL_TYPE_WITH_CODE(WPEGestureControllerAndroid, wpe_gesture_controller_android, G_TYPE_OBJECT,
    G_IMPLEMENT_INTERFACE(WPE_TYPE_GESTURE_CONTROLLER, wpe_gesture_controller_android_interface_init))

static Detector& detectorOf(WPEGestureController* controller)
{
    return *WPE_GESTURE_CONTROLLER_ANDROID(controller)->detector;
}

static gboolean wpeGestureControllerAndroidHandleEvent(WPEGestureController* controller, WPEEvent* event)
{
    return detectorOf(controller).handleEvent(event) ? TRUE : FALSE;
}

static void wpeGestureControllerAndroidCancel(WPEGestureController* controller)
{
    detectorOf(controller).cancel();
}

static WPEGesture wpeGestureControllerAndroidGetGesture(WPEGestureController* controller)
{
    return detectorOf(controller).gesture();
}

static gboolean wpeGestureControllerAndroidGetGesturePosition(WPEGestureController* controller, double* x, double* y)
{
    auto position = detectorOf(controller).position();
    if (!position)
        return FALSE;
    *x = position->x;
    *y = position->y;
    return TRUE;
}

static gboolean wpeGestureControllerAndroidGetGestureDelta(WPEGestureController* controller, double* x, double* y)
{
    auto delta = detectorOf(controller).delta();
    if (!delta)
        return FALSE;
    *x = delta->x;
    *y = delta->y;
    return TRUE;
}

static gboolean wpeGestureControllerAndroidIsDragBegin(WPEGestureController* controller)
{
    return detectorOf(controller).dragBegin() ? TRUE : FALSE;
}

static void wpe_gesture_controller_android_interface_init(WPEGestureControllerInterface* interface)
{
    interface->handle_event = wpeGestureControllerAndroidHandleEvent;
    interface->cancel = wpeGestureControllerAndroidCancel;
    interface->get_gesture = wpeGestureControllerAndroidGetGesture;
    interface->get_gesture_position = wpeGestureControllerAndroidGetGesturePosition;
    interface->get_gesture_delta = wpeGestureControllerAndroidGetGestureDelta;
    interface->is_drag_begin = wpeGestureControllerAndroidIsDragBegin;
}

static void wpeGestureControllerAndroidFinalize(GObject* object)
{
    delete WPE_GESTURE_CONTROLLER_ANDROID(object)->detector;
    G_OBJECT_CLASS(wpe_gesture_controller_android_parent_class)->finalize(object);
}

static void wpe_gesture_controller_android_class_init(WPEGestureControllerAndroidClass* klass)
{
    G_OBJECT_CLASS(klass)->finalize = wpeGestureControllerAndroidFinalize;

    longPressSignal = g_signal_new(
        "long-press", G_TYPE_FROM_CLASS(klass), G_SIGNAL_RUN_LAST, 0, nullptr, nullptr, nullptr, G_TYPE_NONE, 0);
}

static void wpe_gesture_controller_android_init(WPEGestureControllerAndroid* controller)
{
    controller->detector = new Detector(controller);
}

WPEGestureController* wpe_gesture_controller_android_new(void)
{
    return WPE_GESTURE_CONTROLLER(g_object_new(WPE_TYPE_GESTURE_CONTROLLER_ANDROID, nullptr));
}

gboolean wpe_gesture_controller_android_observe_touch(WPEGestureControllerAndroid* controller, WPEEvent* event)
{
    g_return_val_if_fail(WPE_IS_GESTURE_CONTROLLER_ANDROID(controller), TRUE);
    return controller->detector->observe(event) ? TRUE : FALSE;
}

void wpe_gesture_controller_android_set_long_press_timeout(WPEGestureControllerAndroid* controller, guint timeout)
{
    g_return_if_fail(WPE_IS_GESTURE_CONTROLLER_ANDROID(controller));
    controller->detector->setLongPressTimeout(timeout);
}

gboolean wpe_gesture_controller_android_take_long_press(
    WPEGestureControllerAndroid* controller, gboolean takeTouch, double* x, double* y)
{
    g_return_val_if_fail(WPE_IS_GESTURE_CONTROLLER_ANDROID(controller), FALSE);
    auto position = controller->detector->takeLongPress(takeTouch);
    if (!position)
        return FALSE;
    *x = position->x;
    *y = position->y;
    return TRUE;
}
