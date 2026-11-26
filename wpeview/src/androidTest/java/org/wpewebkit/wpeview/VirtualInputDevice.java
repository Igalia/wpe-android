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

import android.os.ParcelFileDescriptor;
import android.view.InputDevice;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An input device that the uinput command of the shell plugs in for as long as it is open: a USB mouse with three
 * buttons and two wheels, or a touchpad. It takes the events of Linux, as a USB device sends them.
 */
final class VirtualInputDevice implements AutoCloseable {
    static final int BTN_LEFT = 272;
    static final int BTN_RIGHT = 273;
    static final int BTN_MIDDLE = 274;

    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_REL = 2;
    private static final int EV_ABS = 3;
    private static final int REL_X = 0;
    private static final int REL_Y = 1;
    private static final int REL_HWHEEL = 6;
    private static final int REL_WHEEL = 8;
    private static final int BTN_TOUCH = 330;
    private static final int BTN_TOOL_DOUBLETAP = 333;
    private static final int ABS_X = 0;
    private static final int ABS_Y = 1;
    private static final int ABS_MT_SLOT = 47;
    private static final int ABS_MT_POSITION_X = 53;
    private static final int ABS_MT_POSITION_Y = 54;
    private static final int ABS_MT_TRACKING_ID = 57;

    private final String name;
    private final ParcelFileDescriptor output;
    private final OutputStream input;

    /**
     * A mouse with the left, right and middle buttons, a wheel and a horizontal wheel: the key and relative events
     * (UI_SET_EVBIT), the three buttons (UI_SET_KEYBIT), x, y and the two wheels (UI_SET_RELBIT), and a pointer
     * (UI_SET_PROPBIT).
     */
    static VirtualInputDevice mouse(String name) throws Exception {
        return new VirtualInputDevice(name, "\"pid\": 43981, \"configuration\": ["
                                                + "{\"type\": 100, \"data\": [1, 2]},"
                                                + "{\"type\": 101, \"data\": [272, 273, 274]},"
                                                + "{\"type\": 102, \"data\": [0, 1, 6, 8]},"
                                                + "{\"type\": 110, \"data\": [0]}]");
    }

    /**
     * A touchpad of 100 by 66 millimeters that tells where up to five fingers are and has one button, which Android
     * takes for a touchpad with its gestures: the key and absolute events (UI_SET_EVBIT), the button, the touch and
     * the counts of fingers (UI_SET_KEYBIT), the position of the first finger and of each finger (UI_SET_ABSBIT), and
     * a pointer that is a button pad (UI_SET_PROPBIT).
     */
    static VirtualInputDevice touchpad(String name) throws Exception {
        return new VirtualInputDevice(
            name, "\"pid\": 43982, \"configuration\": ["
                      + "{\"type\": 100, \"data\": [1, 3]},"
                      + "{\"type\": 101, \"data\": [272, 325, 330, 333, 334, 335]},"
                      + "{\"type\": 103, \"data\": [0, 1, 47, 53, 54, 57]},"
                      + "{\"type\": 110, \"data\": [0, 2]}],"
                      + "\"abs_info\": [" + axis(ABS_X, 3000, 30) + ", " + axis(ABS_Y, 2000, 30) + ", " +
                      axis(ABS_MT_SLOT, 4, 0) + ", " + axis(ABS_MT_POSITION_X, 3000, 30) + ", " +
                      axis(ABS_MT_POSITION_Y, 2000, 30) + ", " + axis(ABS_MT_TRACKING_ID, 65535, 0) + "]");
    }

    // An absolute axis from 0 to its maximum, with its resolution in units per millimeter.
    private static String axis(int code, int maximum, int resolution) {
        return "{\"code\": " + code + ", \"info\": {\"value\": 0, \"minimum\": 0, \"maximum\": " + maximum +
            ", \"fuzz\": 0, \"flat\": 0, \"resolution\": " + resolution + "}}";
    }

    private VirtualInputDevice(String name, String configuration) throws Exception {
        this.name = name;
        ParcelFileDescriptor[] uinput =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommandRw("uinput -");
        output = uinput[0];
        input = new ParcelFileDescriptor.AutoCloseOutputStream(uinput[1]);
        send("{\"id\": 1, \"type\": \"uinput\", \"command\": \"register\", \"name\": \"" + name +
             "\", \"vid\": 6353, \"bus\": \"usb\", " + configuration + "}");
        if (!waitForDevice(true)) {
            input.close();
            output.close();
            fail(name + " was not plugged in");
        }
    }

    /** Unplugs the device, and waits until Android no longer has it. */
    @Override
    public void close() throws Exception {
        input.close();
        output.close();
        assertTrue(name + " was not unplugged", waitForDevice(false));
    }

    private boolean waitForDevice(boolean present) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (hasDevice() == present)
                return true;
            Thread.sleep(50);
        }
        return false;
    }

    private boolean hasDevice() { return getDeviceId() != -1; }

    /** The identifier of the device in Android's events, or -1 while Android does not have it. */
    int getDeviceId() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && name.equals(device.getName()))
                return id;
        }
        return -1;
    }

    private void send(String command) throws IOException {
        input.write(command.getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    // Sends the events, as type, code and value, as one report.
    private void inject(int... events) throws IOException {
        StringBuilder command = new StringBuilder("{\"id\": 1, \"command\": \"inject\", \"events\": [");
        for (int event : events)
            command.append(event).append(", ");
        send(command.append(EV_SYN).append(", 0, 0]}").toString());
    }

    /** Moves the mouse by a distance in its own units, which Android speeds up into more pixels. */
    void move(int dx, int dy) throws IOException { inject(EV_REL, REL_X, dx, EV_REL, REL_Y, dy); }

    /** Moves the pointer of the mouse to the top left corner of the screen, where it stops. */
    void moveToTopLeftCorner() throws IOException, InterruptedException {
        for (int i = 0; i < 3; i++) {
            move(-5000, -5000);
            Thread.sleep(20);
        }
    }

    void press(int button) throws IOException { inject(EV_KEY, button, 1); }

    void release(int button) throws IOException { inject(EV_KEY, button, 0); }

    void click(int button) throws IOException, InterruptedException {
        press(button);
        Thread.sleep(50);
        release(button);
    }

    /** Turns the wheel by steps, up when positive. */
    void scroll(int steps) throws IOException { inject(EV_REL, REL_WHEEL, steps); }

    /** Turns the horizontal wheel by steps, to the right when positive. */
    void scrollHorizontally(int steps) throws IOException { inject(EV_REL, REL_HWHEEL, steps); }

    /**
     * Moves two fingers side by side on the touchpad, 20 millimeters apart, from the given point in millimeters, by
     * a distance in millimeters along y over some frames, as on a touchpad that sends a report every 12
     * milliseconds, and lifts them.
     */
    void swipeTwoFingers(int x, int y, int distance) throws IOException, InterruptedException {
        int x0 = x * 30;
        int x1 = (x + 20) * 30;
        int y0 = y * 30;
        inject(EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_TRACKING_ID, 10, EV_ABS, ABS_MT_POSITION_X, x0, EV_ABS,
               ABS_MT_POSITION_Y, y0, EV_ABS, ABS_MT_SLOT, 1, EV_ABS, ABS_MT_TRACKING_ID, 11, EV_ABS, ABS_MT_POSITION_X,
               x1, EV_ABS, ABS_MT_POSITION_Y, y0, EV_KEY, BTN_TOUCH, 1, EV_KEY, BTN_TOOL_DOUBLETAP, 1, EV_ABS, ABS_X,
               x0, EV_ABS, ABS_Y, y0);
        int frames = 20;
        for (int i = 1; i <= frames; i++) {
            Thread.sleep(12);
            int yi = y0 + distance * 30 * i / frames;
            inject(EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_POSITION_Y, yi, EV_ABS, ABS_MT_SLOT, 1, EV_ABS,
                   ABS_MT_POSITION_Y, yi, EV_ABS, ABS_Y, yi);
        }
        Thread.sleep(12);
        inject(EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_TRACKING_ID, -1, EV_ABS, ABS_MT_SLOT, 1, EV_ABS,
               ABS_MT_TRACKING_ID, -1, EV_KEY, BTN_TOUCH, 0, EV_KEY, BTN_TOOL_DOUBLETAP, 0);
    }
}
