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

#include <cmath>
#include <cstdint>
#include <deque>
#include <optional>

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
};

class Detector {
public:
    void observe(WPEEvent* event)
    {
        auto position = positionOf(event);
        if (!position)
            return;

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
            m_touches.push_back(touch);
            if (m_touches.size() > maximumTouches)
                m_touches.pop_front();
            break;
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
                break;
            }
            break;
        default:
            break;
        }
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
            // A new touch can start now. The gesture stays for WebKit to read.
            m_sequenceId = std::nullopt;
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

    void updateDelta(Position position)
    {
        m_delta = Position {position.x - m_nextDeltaReferencePosition.x, position.y - m_nextDeltaReferencePosition.y};
        m_nextDeltaReferencePosition = position;
    }

    void reset()
    {
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
}

static void wpe_gesture_controller_android_init(WPEGestureControllerAndroid* controller)
{
    controller->detector = new Detector;
}

WPEGestureController* wpe_gesture_controller_android_new(void)
{
    return WPE_GESTURE_CONTROLLER(g_object_new(WPE_TYPE_GESTURE_CONTROLLER_ANDROID, nullptr));
}

void wpe_gesture_controller_android_observe_touch(WPEGestureControllerAndroid* controller, WPEEvent* event)
{
    g_return_if_fail(WPE_IS_GESTURE_CONTROLLER_ANDROID(controller));
    controller->detector->observe(event);
}
