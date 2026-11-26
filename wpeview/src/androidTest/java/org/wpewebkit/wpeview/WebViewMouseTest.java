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
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * A mouse, and a touchpad, plugged in through the uinput command of the shell: the page gets them as a mouse, which
 * hovers, has buttons and a wheel, and not as touches.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewMouseTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private final TestWebViewClient webViewClient = new TestWebViewClient();

    /**
     * A target over the whole view, which stays in place when the page scrolls, on a page that scrolls both ways. The
     * page logs the events of the target as "type:button:buttons", with the pointer type of pointer events and the
     * click count of double clicks. moves counts the mouse moves, at is where the last one was, and wheels the wheel
     * events that scroll down and to the right, in pixels. ups counts the mouseup events anywhere on the page.
     */
    private static final String PAGE =
        "<!DOCTYPE html><html><head><meta name=viewport content='width=device-width'><style>"
        + "body { margin: 0; width: 300vw; height: 300vh }"
        + "#target { position: fixed; top: 0; left: 0; width: 100vw; height: 100vh }"
        + "</style></head><body><div id=target></div><script>"
        + "var log = []; var moves = 0; var ups = 0; var wheels = { down: 0, right: 0 };"
        + "addEventListener('mouseup', () => ups++);"
        + "var at = [0, 0];"
        + "target.addEventListener('mousemove', e => { moves++; at = [e.clientX, e.clientY]; });"
        + "for (const type of ['mouseout', 'mouseleave', 'mousedown', 'mouseup', 'click', 'auxclick', 'contextmenu',"
        + "  'pointerdown', 'pointerup', 'touchstart', 'touchend'])"
        + "  target.addEventListener(type, e => log.push(type + ':' + e.button + ':' + e.buttons +"
        + "    (e.pointerType ? ':' + e.pointerType : '')));"
        + "target.addEventListener('dblclick', e => log.push('dblclick:' + e.detail));"
        + "target.addEventListener('contextmenu', e => e.preventDefault());"
        + "target.addEventListener('wheel', e => { if (e.deltaMode == 0 && e.deltaY > 0) wheels.down++;"
        + "  if (e.deltaMode == 0 && e.deltaX > 0) wheels.right++; }, {passive: true});"
        + "function events() { return [...new Set(log)].sort().join(' '); }"
        + "</script></body></html>";

    private ActivityScenario<WebViewTestActivity> loadPage() {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, PAGE);
        return scenario;
    }

    private String run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, script);
    }

    private static String waitFor(ActivityScenario<WebViewTestActivity> scenario, String script, String expected)
        throws Throwable {
        return WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, script, expected);
    }

    /**
     * Plugs in a mouse and moves its pointer near the middle of the page, which then has it over its target, away from
     * the scroll bars at the edges, which take the presses of a mouse. The log starts empty then.
     */
    private VirtualInputDevice hoverWithMouse(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        VirtualInputDevice mouse = VirtualInputDevice.mouse("WebViewMouseTest mouse");
        try {
            // Android may drop the first moves of a mouse that was just plugged in, and the pointer then stays where
            // the test before left it, so the mouse starts again from the corner.
            boolean inMiddle = false;
            for (int attempt = 0; attempt < 3 && !inMiddle; attempt++)
                inMiddle = moveToMiddle(scenario, mouse);
            assertTrue("The pointer did not reach the middle of the page, " + pointerOnScreen(), inMiddle);
            assertEquals("true", run(scenario, "target.matches(':hover')"));
            run(scenario, "log = []");
        } catch (Throwable failure) {
            // Unplugged, so that the tests that need no mouse can run after this one.
            mouse.close();
            throw failure;
        }
        return mouse;
    }

    /**
     * Moves the pointer to the top left corner of the screen, into the page in small steps, and then towards the
     * middle of the page by where the page saw it last. Android speeds up the moves of a mouse by an amount that
     * depends on the screen, so each step goes a quarter of the way. Returns whether the pointer is near the middle.
     */
    private boolean moveToMiddle(ActivityScenario<WebViewTestActivity> scenario, VirtualInputDevice mouse)
        throws Throwable {
        mouse.moveToTopLeftCorner();
        run(scenario, "moves = 0");
        for (int step = 0; step < 50 && "0".equals(run(scenario, "moves")); step++)
            moveAndWait(scenario, mouse, 8, 8);
        for (int step = 0; step < 20; step++) {
            String[] values =
                run(scenario, "[innerWidth / 2 - at[0], innerHeight / 2 - at[1], innerWidth, innerHeight, "
                                  + "devicePixelRatio].join(' ')")
                    .replace("\"", "")
                    .split(" ");
            double dx = Double.parseDouble(values[0]);
            double dy = Double.parseDouble(values[1]);
            double width = Double.parseDouble(values[2]);
            double height = Double.parseDouble(values[3]);
            double ratio = Double.parseDouble(values[4]);
            if (Math.abs(dx) < width / 4 && Math.abs(dy) < height / 4)
                return true;
            moveAndWait(scenario, mouse, quarterOfTheWay(dx, ratio, width), quarterOfTheWay(dy, ratio, height));
        }
        return false;
    }

    // A quarter of the distance in pixels of the page, in units of the mouse, at least one unit while the pointer is
    // still far from the middle.
    private static int quarterOfTheWay(double distance, double ratio, double size) {
        if (Math.abs(distance) < size / 4)
            return 0;
        int units = (int)Math.round(distance * ratio / 4);
        return units != 0 ? units : (int)Math.signum(distance);
    }

    // Moves the mouse, and waits until the page sees the pointer move, for a second at most.
    private void moveAndWait(ActivityScenario<WebViewTestActivity> scenario, VirtualInputDevice mouse, int dx, int dy)
        throws Throwable {
        String moves = run(scenario, "moves");
        mouse.move(dx, dy);
        for (int i = 0; i < 20 && moves.equals(run(scenario, "moves")); i++)
            Thread.sleep(50);
    }

    // Where Android has the pointer of the mouse, as its input service tells.
    private static String pointerOnScreen() throws Exception {
        for (String line : WebViewActivityScenarioHelper.shell("dumpsys input").split("\n")) {
            if (line.contains("MOUSE) at") || line.contains("Position:"))
                return line.trim();
        }
        return "no pointer";
    }

    @Test
    public void testMouseHoversThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            String moves = run(scenario, "moves");
            mouse.move(10, 10);
            assertEquals("The page did not get the move", "true",
                         waitFor(scenario, "moves > " + moves + " && target.matches(':hover')", "true"));
        }
    }

    /** The pointer leaves the page when it leaves the view, and the page no longer has it over its target. */
    @Test
    public void testMouseLeavesThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        // The view takes the top half of the window, and the pointer leaves it at the bottom.
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                               ((LinearLayout)view.getParent()).getHeight() / 2));
        });
        assertEquals("The page did not get smaller", "true",
                     waitFor(scenario, "innerHeight < screen.height * 0.6", "true"));
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            for (int i = 0; i < 3; i++) {
                mouse.move(0, 5000);
                Thread.sleep(20);
            }
            assertEquals("The pointer is still over the page", "false",
                         waitFor(scenario, "target.matches(':hover')", "false"));
            assertEquals("\"mouseleave:0:0 mouseout:0:0\"", run(scenario, "events()"));
        }
    }

    /** A click with the left button is a click of a mouse, not a tap, and the pointer stays over the page. */
    @Test
    public void testLeftClick() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            mouse.click(VirtualInputDevice.BTN_LEFT);
            assertEquals("The page did not get the click", "true", waitFor(scenario, "log.length >= 5", "true"));
            Thread.sleep(500);
            assertEquals("\"click:0:0:mouse mousedown:0:1 mouseup:0:0 pointerdown:0:1:mouse pointerup:0:0:mouse\"",
                         run(scenario, "events()"));
            assertEquals("true", run(scenario, "target.matches(':hover')"));
        }
    }

    /** A right click opens the context menu of the page, and does not click. */
    @Test
    public void testRightClick() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            mouse.click(VirtualInputDevice.BTN_RIGHT);
            assertEquals("The page did not get the click", "true", waitFor(scenario, "log.length >= 6", "true"));
            Thread.sleep(500);
            assertEquals("\"auxclick:2:0:mouse contextmenu:2:2:mouse mousedown:2:2 mouseup:2:0 pointerdown:2:2:mouse "
                             + "pointerup:2:0:mouse\"",
                         run(scenario, "events()"));
        }
    }

    /** A click with the middle button is an auxiliary click, not a click. */
    @Test
    public void testMiddleClick() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            mouse.click(VirtualInputDevice.BTN_MIDDLE);
            assertEquals("The page did not get the click", "true", waitFor(scenario, "log.length >= 5", "true"));
            Thread.sleep(500);
            assertEquals("\"auxclick:1:0:mouse mousedown:1:4 mouseup:1:0 pointerdown:1:4:mouse pointerup:1:0:mouse\"",
                         run(scenario, "events()"));
        }
    }

    /**
     * Android cancels the touch stream of a press, as when a parent view takes it over: the pointer leaves the page,
     * and the button is released out of the target, which gets no click, nor a press from a turn of the wheel while
     * the button is still held.
     */
    @Test
    public void testCancelledPressDoesNotClick() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            mouse.press(VirtualInputDevice.BTN_LEFT);
            assertEquals("The page did not get the press", "true",
                         waitFor(scenario, "log.includes('mousedown:0:1')", "true"));
            int deviceId = mouse.getDeviceId();
            scenario.onActivity(activity -> {
                WebView view = activity.getWebView();
                MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
                properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
                MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
                coords[0].x = view.getWidth() / 2f;
                coords[0].y = view.getHeight() / 2f;
                long now = SystemClock.uptimeMillis();
                MotionEvent cancel =
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 1, properties, coords, 0,
                                       MotionEvent.BUTTON_PRIMARY, 1, 1, deviceId, 0, InputDevice.SOURCE_MOUSE, 0);
                view.dispatchTouchEvent(cancel);
                cancel.recycle();
            });
            // The button is still held for a turn of the wheel, which presses nothing.
            mouse.scroll(-1);
            assertEquals("The page did not get the wheel", "true", waitFor(scenario, "wheels.down > 0", "true"));
            mouse.release(VirtualInputDevice.BTN_LEFT);
            assertEquals("The page did not get the release", "true", waitFor(scenario, "ups == 1", "true"));
            Thread.sleep(500);
            assertEquals("\"mousedown:0:1 mouseleave:0:1 mouseout:0:1 pointerdown:0:1:mouse\"",
                         run(scenario, "events()"));
        }
    }

    /**
     * The page leaves the view while the left button is held, as when the app shows another tab: the pointer leaves
     * the page, and the button is released out of its target, which gets no click. The page that comes gets neither
     * the press, even with a turn of the wheel while the button is held, nor the release.
     */
    @Test
    public void testPageLeavesWhileAButtonIsHeld() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        List<WebContext> context = new ArrayList<>();
        scenario.onActivity(activity -> context.add(new WebContext(activity, false, true)));
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage first = WebPageTestHelper.newPage(context.get(0), client);
        WebPage second = WebPageTestHelper.newPage(context.get(0), client);
        try {
            WebPageTestHelper.loadHtml(first, client, PAGE);
            WebPageTestHelper.loadHtml(second, client, PAGE);
            scenario.onActivity(activity -> activity.getWebView().setPage(first));
            try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
                mouse.press(VirtualInputDevice.BTN_LEFT);
                assertEquals("The page did not get the press", "true",
                             WebPageTestHelper.waitForResult(first, "log.includes('mousedown:0:1')", "true"));
                scenario.onActivity(activity -> activity.getWebView().setPage(second));
                assertEquals("The page that left did not get the release", "true",
                             WebPageTestHelper.waitForResult(first, "ups == 1", "true"));
                mouse.scroll(-1);
                Thread.sleep(200);
                mouse.release(VirtualInputDevice.BTN_LEFT);
                assertEquals("The page that came did not get the wheel", "true",
                             WebPageTestHelper.waitForResult(second, "wheels.down > 0", "true"));
                Thread.sleep(500);
                assertEquals("\"mousedown:0:1 mouseleave:0:1 mouseout:0:1 pointerdown:0:1:mouse\"",
                             WebPageTestHelper.evaluate(first, "events()"));
                assertEquals("\"\"", WebPageTestHelper.evaluate(second, "events()"));
                assertEquals("0", WebPageTestHelper.evaluate(second, "ups"));
            }
        } finally {
            WebPageTestHelper.onMain(() -> {
                first.destroy();
                second.destroy();
                context.get(0).destroy();
            });
        }
    }

    @Test
    public void testDoubleClick() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            mouse.click(VirtualInputDevice.BTN_LEFT);
            Thread.sleep(50);
            mouse.click(VirtualInputDevice.BTN_LEFT);
            assertEquals("The page did not get the double click", "true",
                         waitFor(scenario, "log.includes('dblclick:2')", "true"));
        }
    }

    /**
     * The left button held while the right one is pressed and released: the page gets both buttons, with the
     * buttons held at each step.
     */
    @Test
    public void testButtonsHeldTogether() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            mouse.press(VirtualInputDevice.BTN_LEFT);
            Thread.sleep(50);
            mouse.click(VirtualInputDevice.BTN_RIGHT);
            Thread.sleep(50);
            mouse.release(VirtualInputDevice.BTN_LEFT);
            assertEquals("The page did not get the buttons", "true",
                         waitFor(scenario, "log.includes('mouseup:0:0')", "true"));
            assertEquals("true", run(scenario, "['pointerdown:0:1:mouse', 'mousedown:0:1', 'mousedown:2:3', "
                                                   + "'contextmenu:2:3:mouse', 'mouseup:2:1', 'pointerup:0:0:mouse', "
                                                   + "'mouseup:0:0'].every(event => log.includes(event))"));
        }
    }

    /** The wheel scrolls the page down and to the right, smoothly, and the page gets its wheel events in pixels. */
    @Test
    public void testWheelScrollsThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario)) {
            for (int i = 0; i < 3; i++) {
                mouse.scroll(-1);
                Thread.sleep(50);
            }
            assertEquals("The page did not scroll down", "true",
                         waitFor(scenario, "wheels.down > 0 && scrollY > 0", "true"));
            for (int i = 0; i < 3; i++) {
                mouse.scrollHorizontally(1);
                Thread.sleep(50);
            }
            assertEquals("The page did not scroll to the right", "true",
                         waitFor(scenario, "wheels.right > 0 && scrollX > 0", "true"));
            assertEquals("\"\"", run(scenario, "events()"));
        }
    }

    /**
     * Two fingers that move up on a touchpad scroll the page down, with wheel events in pixels, and the page gets no
     * touches and no presses. The pointer stays over the page: Android ends its hover before the fingers move, and the
     * page does not see it leave when the touch stream of the fingers comes some milliseconds later.
     */
    @Test
    public void testTouchpadScrollsThePage() throws Throwable {
        assumeTrue("Touchpads have their gestures from Android 14",
                   Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        try (VirtualInputDevice mouse = hoverWithMouse(scenario);
             VirtualInputDevice touchpad = VirtualInputDevice.touchpad("WebViewMouseTest touchpad")) {
            touchpad.swipeTwoFingers(30, 50, -20);
            assertEquals("The page did not scroll down", "true",
                         waitFor(scenario, "wheels.down > 0 && scrollY > 0", "true"));
            Thread.sleep(500);
            assertEquals("The page got touches or presses, or the pointer left it", "\"\"", run(scenario, "events()"));
            assertEquals("true", waitFor(scenario, "target.matches(':hover')", "true"));
        }
    }

    /**
     * A field of the app keeps the focus while the wheel or two fingers on a touchpad scroll the page, and loses it to
     * the view when a button is pressed on the page.
     */
    @Test
    public void testOnlyAPressTakesTheFocus() throws Throwable {
        assumeTrue("Touchpads have their gestures from Android 14",
                   Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        ActivityScenario<WebViewTestActivity> scenario = loadPage();
        List<EditText> field = new ArrayList<>();
        scenario.onActivity(activity -> {
            field.add(new EditText(activity));
            ((LinearLayout)activity.getWebView().getParent()).addView(field.get(0), 0);
        });
        try (VirtualInputDevice mouse = hoverWithMouse(scenario);
             VirtualInputDevice touchpad = VirtualInputDevice.touchpad("WebViewMouseTest touchpad")) {
            scenario.onActivity(activity -> assertTrue(field.get(0).requestFocus()));
            mouse.scroll(-1);
            assertEquals("The page did not scroll down", "true",
                         waitFor(scenario, "wheels.down > 0 && scrollY > 0", "true"));
            touchpad.swipeTwoFingers(30, 50, -20);
            assertEquals("The page did not get the touchpad scroll", "true",
                         waitFor(scenario, "wheels.down > 3", "true"));
            Thread.sleep(500);
            scenario.onActivity(activity -> assertTrue("The scroll took the focus", field.get(0).hasFocus()));
            mouse.click(VirtualInputDevice.BTN_LEFT);
            assertEquals("The page did not get the click", "true",
                         waitFor(scenario, "log.includes('click:0:0:mouse')", "true"));
            scenario.onActivity(
                activity -> assertTrue("The press did not take the focus", activity.getWebView().hasFocus()));
        }
    }
}
