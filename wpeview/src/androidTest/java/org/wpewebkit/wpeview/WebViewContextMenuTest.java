package org.wpewebkit.wpeview;

import android.os.SystemClock;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Long presses: a finger held for Android's long press timeout asks the page for its context menu while it is down,
 * and the view's chrome client gets what it was on. The page can cancel its contextmenu event to keep the long press.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewContextMenuTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // Longer than Android's long press timeout, which is 400 ms by default and 500 ms on older versions.
    private static final long HOLD_MILLIS = 1500;
    // A red pixel, as an image that needs no network.
    private static final String IMAGE_URL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAA"
                                            + "DUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";
    private static final String LINK_URL = "https://example.com/linked";
    private static final String FRAME_LINK_URL = "https://example.com/framed";

    private final TestWebViewClient webViewClient = new TestWebViewClient();
    private final LinkedBlockingQueue<WebChromeClient.ContextMenuParams> menus = new LinkedBlockingQueue<>();
    private long lastEventTime;

    /**
     * Blocks one under the other, each 100 pixels high, on a page tall enough to scroll: a link, an image, a link in a
     * frame, a field, a block whose contextmenu events the page cancels, and bare page. The page logs the events it
     * gets, with the pointer type of pointer events and contextmenu, and a ! for those cancelled, and counts clicks,
     * which never follow links. With touchListeners, it listens to touch and pointer events, and WebKit sends it
     * touches; without, it listens only to mouse events.
     */
    private static String page(boolean touchListeners) {
        String types = touchListeners ? "'touchstart', 'touchmove', 'touchend', 'touchcancel', 'pointerdown', "
                                            + "'pointerup', 'pointercancel', "
                                      : "";
        return "<html><head><meta name=viewport content='width=device-width'><style>"
            + "body { margin: 0; height: 5000px } #link, #image, #frame, #field, #veto { display: block; margin: 0; "
            + "border: 0; padding: 0; width: 100%; height: 100px; box-sizing: border-box }"
            + "</style></head><body>"
            + "<a id=link href='" + LINK_URL + "' title='The title'>The text</a>"
            + "<img id=image src='" + IMAGE_URL + "'>"
            + "<iframe id=frame srcdoc=\"<body style='margin: 0'><a href='" + FRAME_LINK_URL +
            "' style='display: block; height: 100px'>In a frame</a>\"></iframe>"
            + "<input id=field>"
            + "<div id=veto>Veto</div>"
            + "<script>"
            + "var clicks = 0; var log = []; var rendered = false;"
            + "addEventListener('click', e => { clicks++; e.preventDefault(); });"
            + "veto.addEventListener('contextmenu', e => e.preventDefault());"
            + "for (const type of [" + types + "'mousedown', 'mouseup', 'contextmenu'])"
            + "  addEventListener(type, e => log.push(type + (e.pointerType ? ':' + e.pointerType : '')"
            + "    + (e.defaultPrevented ? '!' : '')), {passive: true});"
            + "function center(id, below) { const r = document.getElementById(id).getBoundingClientRect();"
            + "  return (r.left + r.width / 2) + ',' + (r.top + r.height / 2 + below); }"
            + "requestAnimationFrame(() => requestAnimationFrame(() => { rendered = true; }));"
            + "</script></body></html>";
    }

    private ActivityScenario<WebViewTestActivity> loadPage(boolean touchListeners) throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowContextMenu(@NonNull WebView view, @NonNull ContextMenuParams params) {
                menus.add(params);
            }
        }));
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, page(touchListeners));
        // Until the page is rendered, WebKit does not know that it listens to touches, and does not send them.
        assertEquals("The page was not rendered", "true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "rendered", "true"));
        return scenario;
    }

    private String run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, script);
    }

    // The middle of an element of the page, in the pixels of the view.
    private float[] centerOf(ActivityScenario<WebViewTestActivity> scenario, String id) throws Throwable {
        return centerOf(scenario, id, 0);
    }

    // A point under the middle of an element of the page by some pixels of the page, in the pixels of the view.
    private float[] centerOf(ActivityScenario<WebViewTestActivity> scenario, String id, int below) throws Throwable {
        String[] point = run(scenario, "center('" + id + "', " + below + ")").replace("\"", "").split(",");
        float[] scale = new float[1];
        scenario.onActivity(activity -> scale[0] = activity.getWebView().getPageScale());
        return new float[] {Float.parseFloat(point[0]) * scale[0], Float.parseFloat(point[1]) * scale[0]};
    }

    private void touch(ActivityScenario<WebViewTestActivity> scenario, int action, float[] point, long[] downTime) {
        scenario.onActivity(activity -> {
            long time = Math.max(SystemClock.uptimeMillis(), lastEventTime + 1);
            if (action == MotionEvent.ACTION_DOWN)
                downTime[0] = time;
            lastEventTime = time;
            MotionEvent event = MotionEvent.obtain(downTime[0], time, action, point[0], point[1], 0);
            activity.getWebView().dispatchTouchEvent(event);
            event.recycle();
        });
    }

    /** Puts a finger down at the point, and returns the down time for the next events of the touch. */
    private long[] press(ActivityScenario<WebViewTestActivity> scenario, float[] point) {
        long[] downTime = new long[1];
        touch(scenario, MotionEvent.ACTION_DOWN, point, downTime);
        return downTime;
    }

    /** Holds a finger on the element for longer than the long press timeout, and returns what the client got. */
    private WebChromeClient.ContextMenuParams longPress(ActivityScenario<WebViewTestActivity> scenario, String id,
                                                        boolean expectMenu) throws Throwable {
        float[] point = centerOf(scenario, id);
        long[] downTime = press(scenario, point);
        WebChromeClient.ContextMenuParams params = menus.poll(HOLD_MILLIS, TimeUnit.MILLISECONDS);
        if (expectMenu) {
            assertNotNull("No context menu while the finger was down, with the events " + run(scenario, "log"), params);
            assertEquals(point[0], params.getX(), 2);
            assertEquals(point[1], params.getY(), 2);
        }
        touch(scenario, MotionEvent.ACTION_UP, point, downTime);
        return params;
    }

    // Waits for what a lift would bring, a click or a late menu, which must not come.
    private void assertNothingMore(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        assertNull("A second context menu came", menus.poll(1000, TimeUnit.MILLISECONDS));
        assertEquals("A click came, with the events " + run(scenario, "log"), "0", run(scenario, "clicks"));
    }

    @Test
    public void testLongPressOnALink() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        WebChromeClient.ContextMenuParams params = longPress(scenario, "link", true);
        assertEquals(LINK_URL, params.getLinkUrl());
        assertEquals("The text", params.getLinkText());
        assertEquals("The title", params.getLinkTitle());
        assertNull(params.getImageUrl());
        assertNull(params.getMediaUrl());
        assertFalse(params.isEditable());
        assertNothingMore(scenario);
        // The page got its contextmenu as the touch's, and then a cancel of the touch, which ends with no touchend.
        String log = run(scenario, "log.join(' ')");
        assertTrue(log, log.matches("\"pointerdown:touch touchstart mousedown contextmenu:touch pointercancel:touch "
                                    + "touchcancel\""));
    }

    @Test
    public void testLongPressOnAnImage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        WebChromeClient.ContextMenuParams params = longPress(scenario, "image", true);
        assertEquals(IMAGE_URL, params.getImageUrl());
        assertNull(params.getLinkUrl());
        assertNothingMore(scenario);
    }

    @Test
    public void testLongPressOnALinkInAFrame() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        WebChromeClient.ContextMenuParams params = longPress(scenario, "frame", true);
        assertEquals(FRAME_LINK_URL, params.getLinkUrl());
        assertEquals("In a frame", params.getLinkText());
    }

    @Test
    public void testLongPressOnThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        float[] point = centerOf(scenario, "veto", 150);
        long[] downTime = press(scenario, point);
        WebChromeClient.ContextMenuParams params = menus.poll(HOLD_MILLIS, TimeUnit.MILLISECONDS);
        touch(scenario, MotionEvent.ACTION_UP, point, downTime);
        assertNotNull("No context menu for the bare page", params);
        assertNull(params.getLinkUrl());
        assertNull(params.getImageUrl());
        assertFalse(params.isEditable());
        assertNothingMore(scenario);
    }

    /** A long press in a field gives it the focus, as a tap does. */
    @Test
    public void testLongPressInAField() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        WebChromeClient.ContextMenuParams params = longPress(scenario, "field", true);
        assertTrue(params.isEditable());
        assertEquals("\"field\"", run(scenario, "document.activeElement.id"));
    }

    /** The page cancels its contextmenu event: the app gets nothing, and the touch ends as usual, with no click. */
    @Test
    public void testNoMenuWhenThePageCancelsContextmenu() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        assertNull(longPress(scenario, "veto", false));
        assertNothingMore(scenario);
        String log = run(scenario, "log.join(' ')");
        assertTrue(log, log.contains("contextmenu:touch!") && log.contains("touchend") && !log.contains("cancel"));
        // A long press after it, where the page lets the menu show, gives its own params.
        assertEquals(LINK_URL, longPress(scenario, "link", true).getLinkUrl());
    }

    /** A tap after a long press clicks, and a second long press gives a menu again. */
    @Test
    public void testTapAndLongPressAfterALongPress() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        longPress(scenario, "link", true);
        assertNothingMore(scenario);
        float[] point = centerOf(scenario, "link");
        long[] downTime = press(scenario, point);
        touch(scenario, MotionEvent.ACTION_UP, point, downTime);
        assertEquals("1", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "clicks", "1"));
        assertEquals(IMAGE_URL, longPress(scenario, "image", true).getImageUrl());
    }

    /** A finger that moves away before the timeout scrolls the page, and makes no long press. */
    @Test
    public void testNoMenuWhenTheFingerMovesAway() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        float[] point = centerOf(scenario, "veto");
        long[] downTime = press(scenario, point);
        for (int i = 1; i <= 6; i++)
            touch(scenario, MotionEvent.ACTION_MOVE, new float[] {point[0], point[1] - 50 * i}, downTime);
        assertNull("A context menu came", menus.poll(HOLD_MILLIS, TimeUnit.MILLISECONDS));
        touch(scenario, MotionEvent.ACTION_UP, new float[] {point[0], point[1] - 300}, downTime);
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "scrollY > 50", "true"));
        assertNothingMore(scenario);
    }

    /**
     * On a page whose touchstart takes longer than the long press timeout, WebKit passes the touch down on only after
     * the finger was held and lifted: that is a long press too, which never clicks.
     */
    @Test
    public void testLongPressOnABusyPage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        run(scenario, "addEventListener('touchstart', () => { const end = Date.now() + 1500;"
                          + " while (Date.now() < end); }, {passive: false}); rendered = false;"
                          + "requestAnimationFrame(() => requestAnimationFrame(() => { rendered = true; }))");
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "rendered", "true"));
        float[] point = centerOf(scenario, "link");
        long[] downTime = press(scenario, point);
        Thread.sleep(800);
        touch(scenario, MotionEvent.ACTION_UP, point, downTime);
        WebChromeClient.ContextMenuParams params = menus.poll(5000, TimeUnit.MILLISECONDS);
        assertNotNull("No context menu, with the events " + run(scenario, "log"), params);
        assertEquals(LINK_URL, params.getLinkUrl());
        assertNothingMore(scenario);
    }

    /**
     * The first long press after a load gives a menu, on a page that listens to touches, whose contextmenu event is the
     * touch's, and on one that does not, which WebKit sends no touches, so that its contextmenu event says mouse.
     */
    @Test
    public void testFirstLongPressAfterALoad() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(true);
        assertEquals(LINK_URL, longPress(scenario, "link", true).getLinkUrl());
        assertTrue(run(scenario, "log.join(' ')").contains("contextmenu:touch"));
        assertNothingMore(scenario);

        scenario = loadPage(false);
        assertEquals(LINK_URL, longPress(scenario, "link", true).getLinkUrl());
        assertTrue(run(scenario, "log.join(' ')").contains("contextmenu:mouse"));
        assertNothingMore(scenario);
    }
}
