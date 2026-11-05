package org.wpewebkit.wpeview;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/**
 * Taps and drags with a finger, which always moves a little during a tap. Pages that cancel touchmove, as the
 * scroll locks of popup menus do, still get a click from a tap, and are not scrolled.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewTouchTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // A tap: the finger moves a few pixels and comes back, well within the touch slop.
    private static final float[][] TAP = {{0, 0}, {2, 1}, {3, 3}, {1, 2}, {1, 2}};

    // A drag of 600 pixels upwards, which scrolls the page down.
    private static final float[][] DRAG = {{0, 0},    {0, -100}, {0, -200}, {0, -300},
                                           {0, -400}, {0, -500}, {0, -600}, {0, -600}};

    // The finger goes far and comes back to where it started before it is lifted: not a tap.
    private static final float[][] AWAY_AND_BACK = {{0, 0}, {0, -150}, {0, -300}, {0, -150}, {0, 0}, {0, 0}};

    // Event times go on from the last finger path, so that a touch never starts before the one before it ended.
    private long lastEventTime;
    private final TestWebViewClient webViewClient = new TestWebViewClient();

    /**
     * A target over the whole view, which stays in place when the page scrolls, on a page tall enough to scroll.
     * cancel(type) makes the target cancel the touch events of that type, and uncancel() stops it. The page logs
     * the events it gets, with a ! for those cancelled.
     *
     * <p>The target listens to touchend without being passive, though it cancels nothing. When the page could not
     * cancel a touch event, WebKit answers for it before the page has it, and the click that WebKit then makes of
     * a tap can reach the page before its touchend; the page then drops the click. That is a matter of WebKit, not
     * of the gestures tested here, so the page avoids it.
     */
    private static String page(String cancelledType) {
        return "<html><head><meta name=viewport content='width=device-width'><style>"
            + "body { margin: 0; height: 5000px }"
            + "#target { position: fixed; top: 0; left: 0; width: 100%; height: 100vh }"
            + "</style></head><body><div id=target></div><script>"
            + "var clicks = 0; var touchends = 0; var cancelled = null; var log = [];"
            + "var rendered = false;"
            + "target.addEventListener('click', () => clicks++);"
            + "target.addEventListener('touchend', () => {}, {passive: false});"
            + "addEventListener('touchend', () => touchends++, {passive: true});"
            + "for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel', 'pointerdown', 'pointerup', "
            + "'pointercancel', 'mousedown', 'mouseup', 'click'])"
            + "  addEventListener(type, e => log.push(type + (e.defaultPrevented ? '!' : '')), {passive: true});"
            + "function cancel(type) { cancelled = {type, listener: e => e.preventDefault()};"
            + "  target.addEventListener(type, cancelled.listener, {passive: false}); }"
            + "function uncancel() { target.removeEventListener(cancelled.type, cancelled.listener); }"
            + "function render() { rendered = false;"
            + "  requestAnimationFrame(() => requestAnimationFrame(() => { rendered = true; })); }" +
            (cancelledType != null ? "cancel('" + cancelledType + "');" : "") + "render();"
            + "</script></body></html>";
    }

    private ActivityScenario<WebViewTestActivity> loadPage(String cancelledType) throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, page(cancelledType));
        waitForRendering(scenario);
        return scenario;
    }

    /**
     * Waits for the page to be rendered, after its touch event listeners changed. Before that WebKit does not know
     * that the page can cancel a touch there, and does not wait for it.
     */
    private static void waitForRendering(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        assertEquals("The page was not rendered", "true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "rendered", "true"));
    }

    private void changeListeners(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        run(scenario, script + "; render()");
        waitForRendering(scenario);
    }

    private String run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, script);
    }

    /**
     * Moves a finger over the target: down at the first point, through the others, and up at the last one. Each
     * point is an offset in pixels from the middle of the view. All the paths go out at once, before WebKit has
     * answered for any of their events.
     */
    private void moveFinger(ActivityScenario<WebViewTestActivity> scenario, float[][]... paths) {
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            float x = view.getWidth() / 2f;
            float y = view.getHeight() / 2f;
            for (float[][] path : paths) {
                long downTime = Math.max(SystemClock.uptimeMillis(), lastEventTime + 50);
                for (int i = 0; i < path.length; i++) {
                    int action = i == 0                 ? MotionEvent.ACTION_DOWN
                                 : i == path.length - 1 ? MotionEvent.ACTION_UP
                                                        : MotionEvent.ACTION_MOVE;
                    lastEventTime = downTime + 16L * i;
                    MotionEvent event =
                        MotionEvent.obtain(downTime, lastEventTime, action, x + path[i][0], y + path[i][1], 0);
                    view.dispatchTouchEvent(event);
                    event.recycle();
                }
            }
        });
    }

    /**
     * Taps with two fingers that overlap, as in quick taps with two thumbs: a finger on the left half of the target,
     * then one on the right half while the first is still down, then one of them lifts, and then the other.
     */
    private void tapWithTwoFingers(ActivityScenario<WebViewTestActivity> scenario, boolean firstLiftsFirst) {
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            float y = view.getHeight() / 2f;
            float left = view.getWidth() / 4f;
            float right = view.getWidth() * 3 / 4f;
            long downTime = Math.max(SystemClock.uptimeMillis(), lastEventTime + 50);
            int secondDown = MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            int liftedIndex = firstLiftsFirst ? 0 : 1;
            int firstLift = MotionEvent.ACTION_POINTER_UP | (liftedIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            sendTouch(view, downTime, 0, MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {left}, y);
            sendTouch(view, downTime, 16, secondDown, new int[] {0, 1}, new float[] {left, right}, y);
            sendTouch(view, downTime, 32, MotionEvent.ACTION_MOVE, new int[] {0, 1}, new float[] {left + 2, right + 2},
                      y + 1);
            sendTouch(view, downTime, 48, firstLift, new int[] {0, 1}, new float[] {left + 2, right + 2}, y + 1);
            sendTouch(view, downTime, 64, MotionEvent.ACTION_UP, new int[] {firstLiftsFirst ? 1 : 0},
                      new float[] {firstLiftsFirst ? right + 2 : left + 2}, y + 1);
            lastEventTime = downTime + 64;
        });
    }

    private static void sendTouch(WebView view, long downTime, long offset, int action, int[] ids, float[] xs,
                                  float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = y;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(downTime, downTime + offset, action, ids.length, properties, coords, 0,
                                               0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    /**
     * Checks the page's value of the expression once the page had the touch ends of the given number of finger
     * paths, and some more time for the clicks and the scrolling that would follow them.
     */
    private void assertAfterTouches(ActivityScenario<WebViewTestActivity> scenario, int touches, String expression,
                                    String expected) throws Throwable {
        assertEquals(
            "The page did not get all the touch ends", String.valueOf(touches),
            WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "touchends", String.valueOf(touches)));
        Thread.sleep(1000);
        assertEquals(expression + ", with the events " + run(scenario, "log.join(' ')"), expected,
                     run(scenario, expression));
    }

    @Test
    public void testTapClicks() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(null);
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 1, "clicks", "1");
    }

    /** As in a popup menu whose page behind is locked: its items get clicks. */
    @Test
    public void testTapClicksWhenThePageCancelsTouchmove() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchmove");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 1, "clicks", "1");
    }

    /** Taps one after the other, sent before WebKit answers for the first one, each give a click. */
    @Test
    public void testQuickTapsClickWhenThePageCancelsTouchmove() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchmove");
        moveFinger(scenario, TAP, TAP, TAP);
        assertAfterTouches(scenario, 3, "clicks", "3");
    }

    @Test
    public void testNoClickWhenThePageCancelsTouchstart() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchstart");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 1, "clicks", "0");
    }

    @Test
    public void testNoClickWhenThePageCancelsTouchend() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchend");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 1, "clicks", "0");
    }

    /** A tap after one whose touch start the page cancelled still clicks, and only once. */
    @Test
    public void testTapAfterACancelledTouchstartClicksOnce() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchstart");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 1, "clicks", "0");
        changeListeners(scenario, "uncancel()");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 2, "clicks", "1");
    }

    /**
     * After a tap whose touch end the page cancelled, a touch whose touch start it cancelled gives no click either,
     * though the same finger moves and lifts where the first touch did.
     */
    @Test
    public void testNoClickFromATouchAfterACancelledTouchend() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchend");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 1, "clicks", "0");
        changeListeners(scenario, "uncancel(); cancel('touchstart')");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 2, "clicks", "0");
        changeListeners(scenario, "uncancel()");
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 3, "clicks", "1");
    }

    /**
     * When a second finger lands while the first one is down, neither finger taps, as with Android's GestureDetector,
     * also on a page that cancels touchmove: a pinch with a finger that hardly moves does not click.
     */
    @Test
    public void testNoClickWhenASecondFingerLands() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchmove");
        tapWithTwoFingers(scenario, false);
        assertAfterTouches(scenario, 2, "clicks", "0");
        tapWithTwoFingers(scenario, true);
        assertAfterTouches(scenario, 4, "clicks", "0");
        // The next tap of one finger clicks.
        moveFinger(scenario, TAP);
        assertAfterTouches(scenario, 5, "clicks", "1");
    }

    @Test
    public void testNoClickWhenTheFingerGoesAwayAndComesBack() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchmove");
        moveFinger(scenario, AWAY_AND_BACK);
        assertAfterTouches(scenario, 1, "clicks", "0");
    }

    @Test
    public void testDragScrolls() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(null);
        moveFinger(scenario, DRAG);
        assertAfterTouches(scenario, 1, "scrollY > 100", "true");
        assertAfterTouches(scenario, 1, "clicks", "0");
    }

    @Test
    public void testNoScrollWhenThePageCancelsTouchmove() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage("touchmove");
        moveFinger(scenario, DRAG);
        assertAfterTouches(scenario, 1, "scrollY", "0");
        assertAfterTouches(scenario, 1, "clicks", "0");
    }
}
