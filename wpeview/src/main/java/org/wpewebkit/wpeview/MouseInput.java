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

package org.wpewebkit.wpeview;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WPEEventType;
import org.wpewebkit.wpe.WPEInputSource;
import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;

/**
 * Gives the page that a WebView shows the events of a mouse, or of a touchpad, as a mouse: the pointer hovers the
 * page, each button reaches it with the buttons held, the wheel scrolls it in steps and two fingers on a touchpad
 * scroll it smoothly. Android gives the view the presses of a mouse as touches, which pages would take for a finger.
 *
 * <p>Android sends a mouse to a view in three ways: hover events while no button is held, a touch stream from the
 * first press to the last release, and generic events for each button press and release and for the wheel. The
 * buttons go to the page as the button state of the events changes, whichever way the events come. The first press
 * ends the hover with a hover exit, and the last release starts it again. Android also sends a hover exit before the
 * touch stream of a touchpad gesture, which may come some milliseconds after it. So the pointer leaves the page only
 * when nothing on the view follows its hover exit soon.
 *
 * <p>Pages have no way to cancel a press of a mouse. When Android cancels the touch stream of a press, or the page
 * leaves the view while a button is held, the pointer leaves the page, and the buttons are released out of it, so that
 * the element pressed gets no click.
 */
final class MouseInput {
    // The buttons that pages get. The Back and Forward buttons of a mouse come as keys too.
    private static final int BUTTONS =
        MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY;

    // What the touch stream of a device is: touches, the buttons of a mouse, two fingers that scroll on a touchpad, or
    // the rest of a stream that began on another page.
    private static final int TOUCHES = 0;
    private static final int BUTTON_PRESSES = 1;
    private static final int TOUCHPAD_SCROLL = 2;
    private static final int IGNORED = 3;

    // How long the pointer stays over the page after a hover exit that tells no button, in milliseconds.
    private static final long LEAVE_DELAY = 100;

    private final WebView webView;
    // When the pointer left the view, if nothing comes after its hover exit.
    private long exitTime;
    private final Runnable leave = () -> leave(exitTime);
    // The mouse or touchpad stream, and its device: Android may send a view the touches of a finger at the same time.
    private int stream = TOUCHES;
    private int streamDevice;
    // Whether the page has the pointer over it, where, in logical pixels, and the buttons it knows to be held.
    private boolean inside;
    private float x;
    private float y;
    private int buttons;
    // The buttons held when the page left, which go to no page until they are released.
    private int ignoredButtons;
    // Where the primary button was pressed, in the pixels of the view, to tell a click from a drag.
    private float pressX;
    private float pressY;
    // Where a touchpad scroll scrolls, and where Android's finger of the scroll was last, in logical pixels.
    private float scrollX;
    private float scrollY;
    private float fingerX;
    private float fingerY;
    private boolean scrolled;

    MouseInput(@NonNull WebView webView) { this.webView = webView; }

    // Whether the event comes from a mouse, or from a touchpad that moves the pointer.
    private static boolean isFromMouse(@NonNull MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_MOUSE);
    }

    private static int streamOf(@NonNull MotionEvent event) {
        if (!isFromMouse(event))
            return TOUCHES;
        // Android moves a finger of its own for two fingers that scroll on a touchpad.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
            return TOUCHPAD_SCROLL;
        if ((event.getButtonState() & BUTTONS) != 0)
            return BUTTON_PRESSES;
        // The other gestures of a touchpad, as a pinch, go to the page as the fingers that Android makes of them.
        return TOUCHES;
    }

    /** Takes the hover events of a mouse. Returns whether it took the event. */
    boolean onHoverEvent(@NonNull MotionEvent event) {
        if (!isFromMouse(event) || webView.platformView == null || webView.wpeToplevel == null)
            return false;
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_HOVER_ENTER:
        case MotionEvent.ACTION_HOVER_MOVE:
            webView.removeCallbacks(leave);
            update(event, event.getButtonState());
            return true;
        case MotionEvent.ACTION_HOVER_EXIT:
            // The exit of a press tells the button, and its touch stream follows. The exit of a touchpad gesture
            // does not, and its touch stream may come some milliseconds later.
            if ((event.getButtonState() & BUTTONS) == 0) {
                exitTime = event.getEventTime();
                webView.postDelayed(leave, LEAVE_DELAY);
            }
            return true;
        default:
            return false;
        }
    }

    /** Takes the button presses and releases, and the wheel, of a mouse. Returns whether it took the event. */
    boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        WPEView target = webView.platformView;
        if (!isFromMouse(event) || target == null || webView.wpeToplevel == null)
            return false;
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_BUTTON_PRESS:
        case MotionEvent.ACTION_BUTTON_RELEASE:
            if ((event.getActionButton() & BUTTONS) == 0)
                return false;
            webView.removeCallbacks(leave);
            update(event, event.getButtonState());
            return true;
        case MotionEvent.ACTION_SCROLL:
            webView.removeCallbacks(leave);
            update(event, event.getButtonState());
            // The steps of the wheel, which WebKit scrolls smoothly by a distance of its own. Android counts them up
            // and to the right, and WebKit up and to the left.
            target.dispatchScrollEvent(event.getEventTime(), WPEInputSource.MOUSE, event.getMetaState(), buttons,
                                       -event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                                       event.getAxisValue(MotionEvent.AXIS_VSCROLL), false, false, x, y);
            return true;
        default:
            return false;
        }
    }

    /**
     * Takes the touch streams of a mouse, from the first press to the last release, and of two fingers that scroll on
     * a touchpad. Returns whether it took the event: the streams of touches are not for it.
     */
    boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int kind = streamOf(event);
            if (isFromMouse(event))
                webView.removeCallbacks(leave);
            if (kind != TOUCHES || event.getDeviceId() == streamDevice) {
                stream = kind;
                streamDevice = event.getDeviceId();
            }
        }
        if (stream == TOUCHES || event.getDeviceId() != streamDevice)
            return false;
        int kind = stream;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            stream = TOUCHES;
        if (webView.platformView == null || webView.wpeToplevel == null)
            return true;
        if (kind == BUTTON_PRESSES) {
            // A press focuses the view, as a tap does. A scroll on a touchpad, as a turn of the wheel, does not.
            if (action == MotionEvent.ACTION_DOWN)
                webView.requestFocus();
            if (action == MotionEvent.ACTION_CANCEL) {
                abandonButtons(event.getEventTime());
                return true;
            }
            // The last button is released before the touch stream ends.
            update(event, action == MotionEvent.ACTION_UP ? 0 : event.getButtonState());
            // A drag that ends out of the view leaves the page, as no hover exit comes then.
            if (action == MotionEvent.ACTION_UP &&
                (event.getX() < 0 || event.getY() < 0 || event.getX() >= webView.getWidth() ||
                 event.getY() >= webView.getHeight()))
                leave(event.getEventTime());
        } else if (kind == TOUCHPAD_SCROLL) {
            scroll(event, action);
        }
        return true;
    }

    /**
     * The page leaves the view, or the view leaves its window, while the pointer may be over the page: the pointer
     * leaves the page, and the buttons held are released out of it. Those buttons then go to no page until they are
     * released, nor does the rest of the touch stream.
     */
    void leavePage() {
        webView.removeCallbacks(leave);
        abandonButtons(SystemClock.uptimeMillis());
        if (stream != TOUCHES)
            stream = IGNORED;
    }

    // The pointer leaves the page, and the buttons held are released out of it. They stay held on the mouse, and go to
    // no page until they are released.
    private void abandonButtons(long time) {
        ignoredButtons |= buttons;
        leave(time);
        releaseOutside(time);
    }

    // Moves the pointer of the page to the event, and presses and releases its buttons to the given state.
    private void update(@NonNull MotionEvent event, int buttonState) {
        WPEView target = webView.platformView;
        WPEToplevel toplevel = webView.wpeToplevel;
        if (target == null || toplevel == null)
            return;
        // MotionEvent coords are in physical pixels; WPE expects logical pixels, at the scale of the screen the page
        // is on.
        float scale = toplevel.getScale();
        float newX = event.getX() / scale;
        float newY = event.getY() / scale;
        long time = event.getEventTime();
        int metaState = event.getMetaState();
        if (!inside || newX != x || newY != y) {
            target.dispatchPointerMoveEvent(time, inside ? WPEEventType.POINTER_MOVE : WPEEventType.POINTER_ENTER,
                                            WPEInputSource.MOUSE, metaState, buttons, newX, newY, inside ? newX - x : 0,
                                            inside ? newY - y : 0);
            inside = true;
            x = newX;
            y = newY;
        }
        ignoredButtons &= buttonState;
        int changed = (buttons ^ (buttonState & ~ignoredButtons)) & BUTTONS;
        while (changed != 0) {
            int button = Integer.lowestOneBit(changed);
            changed &= ~button;
            buttons ^= button;
            boolean pressed = (buttons & button) != 0;
            target.dispatchPointerButtonEvent(time, pressed ? WPEEventType.POINTER_DOWN : WPEEventType.POINTER_UP,
                                              WPEInputSource.MOUSE, metaState, buttons, button, x, y);
            if (button != MotionEvent.BUTTON_PRIMARY)
                continue;
            // As a tap does, a click brings back the keyboard of the focused field, which Back may have hidden. A
            // drag, as to select text, does not. For a field that WebKit keeps the keyboard down for, WebKit sends
            // the field again without the hint once the click lands on it.
            if (pressed) {
                pressX = event.getX();
                pressY = event.getY();
            } else if (isClick(event) && webView.isInputFieldFocused() && !webView.fieldKeepsKeyboardDown()) {
                webView.showSoftInput();
            }
        }
    }

    // Whether the release of the primary button is near enough where it was pressed to make a click.
    private boolean isClick(@NonNull MotionEvent event) {
        int slop = ViewConfiguration.get(webView.getContext()).getScaledTouchSlop();
        return Math.abs(event.getX() - pressX) <= slop && Math.abs(event.getY() - pressY) <= slop;
    }

    // The pointer leaves the page. Its position is out of the page, as WebKit takes it for a move.
    private void leave(long time) {
        WPEView target = webView.platformView;
        if (!inside || target == null)
            return;
        inside = false;
        target.dispatchPointerMoveEvent(time, WPEEventType.POINTER_LEAVE, WPEInputSource.MOUSE, 0, buttons, -1, -1, 0,
                                        0);
    }

    // Releases the buttons held out of the page, where they click nothing that they pressed.
    private void releaseOutside(long time) {
        WPEView target = webView.platformView;
        while (buttons != 0) {
            int button = Integer.lowestOneBit(buttons);
            buttons &= ~button;
            if (target != null)
                target.dispatchPointerButtonEvent(time, WPEEventType.POINTER_UP, WPEInputSource.MOUSE, 0, buttons,
                                                  button, -1, -1);
        }
    }

    // Two fingers that scroll on a touchpad, which Android sends as one finger that moves from the pointer, as far as
    // the page should move. The page scrolls at the pointer, which stays in place, by the moves of that finger in
    // pixels, which WebKit scales as it does for every touchpad, and WebKit carries the scroll on after the fingers
    // lift.
    private void scroll(@NonNull MotionEvent event, int action) {
        WPEView target = webView.platformView;
        WPEToplevel toplevel = webView.wpeToplevel;
        if (target == null || toplevel == null)
            return;
        float scale = toplevel.getScale();
        float newX = event.getX() / scale;
        float newY = event.getY() / scale;
        switch (action) {
        case MotionEvent.ACTION_DOWN:
            scrollX = newX;
            scrollY = newY;
            fingerX = newX;
            fingerY = newY;
            scrolled = false;
            break;
        case MotionEvent.ACTION_MOVE:
            float deltaX = newX - fingerX;
            float deltaY = newY - fingerY;
            fingerX = newX;
            fingerY = newY;
            if (deltaX == 0 && deltaY == 0)
                break;
            scrolled = true;
            target.dispatchScrollEvent(event.getEventTime(), WPEInputSource.TOUCHPAD, event.getMetaState(), buttons,
                                       deltaX, deltaY, true, false, scrollX, scrollY);
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            if (scrolled)
                target.dispatchScrollEvent(event.getEventTime(), WPEInputSource.TOUCHPAD, event.getMetaState(), buttons,
                                           0, 0, true, true, scrollX, scrollY);
            break;
        default:
            break;
        }
    }
}
