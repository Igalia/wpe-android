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

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How a page goes fullscreen and leaves it. The element fills the WebView, and the app hears of it through
 * onEnterFullscreen and onExitFullscreen, once for each change, whichever side leaves fullscreen: the page, the app,
 * or Android when the WebView leaves its window.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewFullscreenTest {
    private static final long TIMEOUT_SECONDS = 30;
    private static final String ENTER = "enter";
    private static final String EXIT = "exit";

    // changes counts the fullscreenchange events.
    // A video needs a source to go fullscreen; sound is enough.
    private static final String PAGE = "<html><body><div id='box'>box</div><video id='video' controls src='" +
                                       WebViewActivityScenarioHelper.silentWavUrl() + "'></video>"
                                       + "<script>var changes = 0;\n"
                                       + "document.addEventListener('fullscreenchange', () => changes++);\n"
                                       + "</script></body></html>";
    private static final String REQUEST = "document.getElementById('box').requestFullscreen(); 'requested'";
    private static final String FULLSCREEN_ELEMENT =
        "document.fullscreenElement ? document.fullscreenElement.id : 'none'";
    private static final String BOX = "\"box\"";
    private static final String NONE = "\"none\"";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // The calls of the chrome client, in order.
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final AtomicBoolean declineFullscreen = new AtomicBoolean();
    private ActivityScenario<WebViewTestActivity> scenario;

    @Before
    public void setUp() throws Exception {
        scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onEnterFullscreen(@NonNull WebView view) {
                addEvent(view, ENTER);
                // An app that does not show pages fullscreen.
                if (declineFullscreen.get())
                    view.exitFullscreen();
            }

            @Override
            public void onExitFullscreen(@NonNull WebView view) {
                addEvent(view, EXIT);
            }

            private void addEvent(WebView view, String event) {
                boolean onMainThread = Looper.myLooper() == Looper.getMainLooper();
                boolean ofThisView = view == activity.getWebView();
                events.add(onMainThread && ofThisView ? event : event + " on another thread or view");
            }
        }));
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), PAGE);
        // A hidden page cannot go fullscreen.
        assertEquals("\"visible\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                        scenario, "document.visibilityState", "\"visible\""));
    }

    @After
    public void tearDown() {
        scenario.onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
    }

    private void evaluateJavascript(String script) {
        scenario.onActivity(activity -> activity.getWebView().evaluateJavascript(script, null));
    }

    private String waitForJavaScriptResult(String script, String expected) throws Exception {
        return WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, script, expected);
    }

    private void assertNextEvent(String expected) throws InterruptedException {
        assertEquals(expected, events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private void assertNoMoreEvents() throws InterruptedException { assertNull(events.poll(1, TimeUnit.SECONDS)); }

    private boolean isFullscreen() {
        AtomicBoolean fullscreen = new AtomicBoolean();
        scenario.onActivity(activity -> fullscreen.set(activity.getWebView().isFullscreen()));
        return fullscreen.get();
    }

    // evaluateJavascript() runs scripts as if the user had tapped, which requestFullscreen() needs.
    private void enterFullscreen() throws Exception {
        evaluateJavascript(REQUEST);
        assertNextEvent(ENTER);
        assertTrue(isFullscreen());
        assertEquals(BOX, waitForJavaScriptResult(FULLSCREEN_ELEMENT, BOX));
    }

    @Test
    public void testElementFillsTheWebView() throws Exception {
        enterFullscreen();
        String fills = "var box = document.fullscreenElement.getBoundingClientRect();\n"
                       + "box.width == window.innerWidth && box.height == window.innerHeight";
        assertEquals("true", waitForJavaScriptResult(fills, "true"));
        assertEquals("1", waitForJavaScriptResult("changes", "1"));
        assertNoMoreEvents();
    }

    // The fullscreen of a video, as its own fullscreen button asks for it, is the fullscreen of its element.
    @Test
    public void testVideoGoesFullscreen() throws Exception {
        String metadata = "document.getElementById('video').readyState >= HTMLMediaElement.HAVE_METADATA";
        assertEquals("true", waitForJavaScriptResult(metadata, "true"));
        String request = "try { document.getElementById('video').webkitEnterFullscreen(); 'requested' }\n"
                         + "catch (error) { error.name }";
        assertEquals("\"requested\"", waitForJavaScriptResult(request, "\"requested\""));
        assertNextEvent(ENTER);
        assertTrue(isFullscreen());
        assertEquals("\"video\"", waitForJavaScriptResult(FULLSCREEN_ELEMENT, "\"video\""));

        evaluateJavascript("document.exitFullscreen(); 'left'");
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));
        assertNoMoreEvents();
    }

    @Test
    public void testPageLeavesFullscreen() throws Exception {
        enterFullscreen();

        evaluateJavascript("document.exitFullscreen(); 'left'");
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));
        assertEquals("2", waitForJavaScriptResult("changes", "2"));
        assertNoMoreEvents();
    }

    @Test
    public void testAppLeavesFullscreen() throws Exception {
        enterFullscreen();

        // The app hears of it right away, and the page a moment later.
        AtomicReference<String> event = new AtomicReference<>();
        scenario.onActivity(activity -> {
            activity.getWebView().exitFullscreen();
            event.set(events.poll());
        });
        assertEquals(EXIT, event.get());
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));
        assertEquals("2", waitForJavaScriptResult("changes", "2"));
        assertNoMoreEvents();

        // Leaving does nothing more once the page is out of fullscreen.
        scenario.onActivity(activity -> activity.getWebView().exitFullscreen());
        assertNoMoreEvents();

        // And the page can go fullscreen again.
        enterFullscreen();
        assertEquals("3", waitForJavaScriptResult("changes", "3"));
    }

    @Test
    public void testAppDeclinesFullscreen() throws Exception {
        declineFullscreen.set(true);
        evaluateJavascript(REQUEST);
        assertNextEvent(ENTER);
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));
        assertNoMoreEvents();

        declineFullscreen.set(false);
        enterFullscreen();
    }

    @Test
    public void testWebViewTakenOutOfTheWindowLeavesFullscreen() throws Exception {
        enterFullscreen();

        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getWebView();
            parent.set((ViewGroup)webView.getParent());
            parent.get().removeView(webView);
        });
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));

        scenario.onActivity(activity -> parent.get().addView(activity.getWebView()));
        assertEquals("\"visible\"", waitForJavaScriptResult("document.visibilityState", "\"visible\""));
        assertFalse(isFullscreen());
        assertNoMoreEvents();
        enterFullscreen();
    }

    @Test
    public void testWebViewHiddenInPlaceLeavesFullscreen() throws Exception {
        enterFullscreen();

        scenario.onActivity(activity -> activity.getWebView().setVisibility(View.GONE));
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));

        scenario.onActivity(activity -> activity.getWebView().setVisibility(View.VISIBLE));
        assertEquals("\"visible\"", waitForJavaScriptResult("document.visibilityState", "\"visible\""));
        assertNoMoreEvents();
        enterFullscreen();
    }

    @Test
    public void testStoppedActivityLeavesFullscreen() throws Exception {
        enterFullscreen();

        scenario.moveToState(Lifecycle.State.CREATED);
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));

        scenario.moveToState(Lifecycle.State.RESUMED);
        assertEquals("\"visible\"", waitForJavaScriptResult("document.visibilityState", "\"visible\""));
        assertNoMoreEvents();
        enterFullscreen();
    }

    @Test
    public void testNewPageLeavesFullscreen() throws Exception {
        enterFullscreen();

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), PAGE);
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertNoMoreEvents();
    }

    @Test
    public void testDestroyLeavesFullscreen() throws Exception {
        enterFullscreen();

        scenario.onActivity(activity -> activity.getWebView().destroy());
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertNoMoreEvents();
    }
}
