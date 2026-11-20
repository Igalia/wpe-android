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

#pragma once

#include <glib-object.h>
#include <wpe/wpe-platform.h>

G_BEGIN_DECLS

/**
 * Finds the taps and the drags of a touchscreen, as Android does.
 *
 * WebKit asks the gesture controller of a view about the touch events that the page did not cancel, and calls
 * wpe_gesture_controller_cancel() for those it did. WPE's own controller then drops the whole touch, so a page that
 * cancels touchmove gets no click from a tap, as a finger always moves a little. Scroll locks, such as those of
 * popup menus, cancel touchmove, and their items then ignore taps.
 *
 * This controller follows Android instead: a touch is a tap while the finger stays within the touch slop and no
 * other finger comes down, whatever the page cancels; a page that cancels a move keeps the touch from scrolling; and
 * one that cancels the touch down or the touch up gets no tap. It has to know where the finger went during the moves
 * the page cancelled, and which fingers were down, so the view gives it every touch event with
 * wpe_gesture_controller_android_observe_touch() before WebKit gets it.
 *
 * It also finds long presses, which WPE's own controller does not: a touch that would be a tap, held for Android's
 * long press timeout, counted from the touch down. The touch is no tap any more, so lifting the finger does not click,
 * and the page is asked for its context menu, while the finger is down, with a secondary click at the touch, from the
 * touchscreen, as WebKit's GTK port makes of a long press when the finger lifts: WebKit gives that click to the touch
 * that is down, so the page gets its contextmenu event as a touch's, and can cancel it. The "long-press" signal
 * tells of it, for the vibration. When WebKit then asks the app for its context menu, its handler calls
 * wpe_gesture_controller_android_take_long_press().
 *
 * It takes the place of WPE's own controller, so gestures that WPE adds later do not reach WPE Android by
 * themselves: when WebKit is updated, check that this still implements every method of WPEGestureController.
 */
#define WPE_TYPE_GESTURE_CONTROLLER_ANDROID (wpe_gesture_controller_android_get_type())
G_DECLARE_FINAL_TYPE(
    WPEGestureControllerAndroid, wpe_gesture_controller_android, WPE, GESTURE_CONTROLLER_ANDROID, GObject)

WPEGestureController* wpe_gesture_controller_android_new(void);

/**
 * Records a touch event before WebKit gets it, and returns whether WebKit gets it: not the rest of a touch that the
 * app's context menu took over.
 */
gboolean wpe_gesture_controller_android_observe_touch(WPEGestureControllerAndroid* controller, WPEEvent* event);

/** The long press timeout, in milliseconds, for the touches that come from now on. */
void wpe_gesture_controller_android_set_long_press_timeout(WPEGestureControllerAndroid* controller, guint timeout);

/**
 * For WebKit asking the app for the context menu of the page: returns whether a long press asked for it, with the
 * point of its click in logical pixels. Each long press is taken once; a menu that comes without one, as for a
 * contextmenu event the page cancelled after a long press, gets FALSE. With takeTouch, for a menu that the app may
 * show, the page gets a touch cancel for the touch of the long press, and the rest of the touch goes nowhere, unless
 * the finger was lifted already.
 */
gboolean wpe_gesture_controller_android_take_long_press(
    WPEGestureControllerAndroid* controller, gboolean takeTouch, double* x, double* y);

G_END_DECLS
