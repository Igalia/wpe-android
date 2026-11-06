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

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

@RunWith(AndroidJUnit4.class)
public class WebViewPointerTest {
    private static final String PAGE = "<html><body></body></html>";

    // The values of the pointer and hover media features that match, as "pointer:coarse any-pointer:coarse|fine ...".
    private static final String POINTER =
        "[['pointer', 'none', 'coarse', 'fine'], ['any-pointer', 'none', 'coarse', 'fine'], ['hover', 'none', 'hover'],"
        + " ['any-hover', 'none', 'hover']].map(([feature, ...values]) => feature + ':' +"
        + " values.filter(value => matchMedia('(' + feature + ': ' + value + ')').matches).join('|')).join(' ')";
    private static final String TOUCH = "\"pointer:coarse any-pointer:coarse hover:none any-hover:none\"";
    private static final String TOUCH_AND_MOUSE =
        "\"pointer:fine any-pointer:coarse|fine hover:hover any-hover:hover\"";

    // A USB mouse with three buttons and a wheel, for the uinput command of the shell. It stays while its standard
    // input is open.
    private static final String MOUSE = "{\"id\": 1, \"type\": \"uinput\", \"command\": \"register\","
                                        + " \"name\": \"WebViewPointerTest mouse\", \"vid\": 6353, \"pid\": 43981,"
                                        + " \"bus\": \"usb\", \"configuration\": ["
                                        + "{\"type\": 100, \"data\": [1, 2]},"          // UI_SET_EVBIT: EV_KEY, EV_REL
                                        + "{\"type\": 101, \"data\": [272, 273, 274]}," // UI_SET_KEYBIT: BTN_LEFT...
                                        + "{\"type\": 102, \"data\": [0, 1, 8]},"       // UI_SET_RELBIT: REL_X...
                                        + "{\"type\": 110, \"data\": [0]}]}";           // UI_SET_PROPBIT: POINTER

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private static boolean hasMouse() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.isEnabled() && device.supportsSource(InputDevice.SOURCE_MOUSE))
                return true;
        }
        return false;
    }

    // Polls the pointer and hover features that the page matches until they are the expected ones, and returns the
    // last ones.
    private static String waitForPointer(ActivityScenario<WebViewTestActivity> scenario,
                                         TestWebViewClient webViewClient, String expected) throws Exception {
        String pointer = null;
        for (int i = 0; i < 50; i++) {
            pointer =
                WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, POINTER);
            if (expected.equals(pointer))
                break;
            Thread.sleep(100);
        }
        return pointer;
    }

    /** On a touchscreen, the pointer is coarse and cannot hover, and pages get touch events. */
    @Test
    public void testTouchscreenIsACoarsePointer() throws Throwable {
        assumeFalse("The device has a mouse", hasMouse());
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, PAGE);

        assertEquals(TOUCH, waitForPointer(scenario, webViewClient, TOUCH));
        assertEquals("true", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                                 scenario, webViewClient, "'ontouchstart' in window && 'TouchEvent' in window"));
    }

    /**
     * A mouse is a fine pointer that can hover, and the primary pointer while it is connected; the touchscreen stays
     * among the pointers.
     */
    @Test
    public void testMouseIsAFinePointerThatHovers() throws Throwable {
        assumeFalse("The device has a mouse", hasMouse());
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, PAGE);
        assertEquals(TOUCH, waitForPointer(scenario, webViewClient, TOUCH));

        ParcelFileDescriptor[] uinput =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommandRw("uinput -");
        try (ParcelFileDescriptor output = uinput[0];
             OutputStream input = new ParcelFileDescriptor.AutoCloseOutputStream(uinput[1])) {
            input.write(MOUSE.getBytes(StandardCharsets.UTF_8));
            input.flush();
            assertEquals("The page did not get the mouse", TOUCH_AND_MOUSE,
                         waitForPointer(scenario, webViewClient, TOUCH_AND_MOUSE));
        }
        // The mouse goes when its standard input closes.
        assertEquals("The page did not lose the mouse", TOUCH, waitForPointer(scenario, webViewClient, TOUCH));
    }
}
