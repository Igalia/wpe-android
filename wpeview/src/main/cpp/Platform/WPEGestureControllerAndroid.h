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
 * It takes the place of WPE's own controller, so gestures that WPE adds later do not reach WPE Android by
 * themselves: when WebKit is updated, check that this still implements every method of WPEGestureController.
 */
#define WPE_TYPE_GESTURE_CONTROLLER_ANDROID (wpe_gesture_controller_android_get_type())
G_DECLARE_FINAL_TYPE(
    WPEGestureControllerAndroid, wpe_gesture_controller_android, WPE, GESTURE_CONTROLLER_ANDROID, GObject)

WPEGestureController* wpe_gesture_controller_android_new(void);

void wpe_gesture_controller_android_observe_touch(WPEGestureControllerAndroid* controller, WPEEvent* event);

G_END_DECLS
