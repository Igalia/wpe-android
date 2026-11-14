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

import android.app.UiAutomation;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How a page goes fullscreen and leaves it. The element fills the WebView, and the app hears of it through
 * onEnterFullscreen and onExitFullscreen, once for each change, whichever side leaves fullscreen: the page, the app,
 * Android when the WebView leaves its window, the page leaving the view, or the end of the web process.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewFullscreenTest {
    private static final long TIMEOUT_SECONDS = 30;
    private static final String ENTER = "enter";
    private static final String EXIT = "exit";
    private static final String TERMINATED = "terminated";

    // changes counts the fullscreenchange events, and request() keeps how a request ended in settled.
    // A video needs a source to go fullscreen; sound is enough.
    private static final String PAGE =
        "<html><body><div id='box'>box</div><div id='other'>other</div><video id='video' controls src='" +
        WebViewActivityScenarioHelper.silentWavUrl() + "'></video>"
        + "<script>var changes = 0;\n"
        + "document.addEventListener('fullscreenchange', () => changes++);\n"
        + "var settled = null;\n"
        + "function request(id) {\n"
        + "  settled = null;\n"
        + "  document.getElementById(id).requestFullscreen()\n"
        + "    .then(() => settled = 'entered', e => settled = e.name);\n"
        + "}\n"
        + "</script></body></html>";
    private static final String REQUEST = "document.getElementById('box').requestFullscreen(); 'requested'";
    private static final String FULLSCREEN_ELEMENT =
        "document.fullscreenElement ? document.fullscreenElement.id : 'none'";
    private static final String FILLS = "var box = document.fullscreenElement.getBoundingClientRect();\n"
                                        + "box.width == window.innerWidth && box.height == window.innerHeight";
    private static final String BOX = "\"box\"";
    private static final String NONE = "\"none\"";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // The calls of the chrome client, in order.
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final AtomicBoolean declineFullscreen = new AtomicBoolean();
    // What the app does once, when it hears that the page left fullscreen.
    private final AtomicReference<Consumer<WebView>> onExit = new AtomicReference<>();
    // The color requests the chrome client took, and what it does once when it hears that one is gone.
    private final AtomicInteger colorRequests = new AtomicInteger();
    private final AtomicReference<Consumer<WebView>> onHideColorChooser = new AtomicReference<>();
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
                Consumer<WebView> action = onExit.getAndSet(null);
                if (action != null)
                    action.accept(view);
            }

            @Override
            public boolean onShowColorChooser(@NonNull WebView view, @NonNull ColorChooserParams params,
                                              @NonNull ColorChooserCallback callback) {
                colorRequests.incrementAndGet();
                return true;
            }

            @Override
            public void onHideColorChooser(@NonNull WebView view) {
                Consumer<WebView> action = onHideColorChooser.getAndSet(null);
                if (action != null)
                    action.accept(view);
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
        assertEquals("true", waitForJavaScriptResult(FILLS, "true"));
        assertEquals("1", waitForJavaScriptResult("changes", "1"));
        assertNoMoreEvents();
    }

    // Turning the screen resizes the WebView, and the element goes on filling it. The test turns the display itself,
    // since Android ignores the orientation an app asks for on large screens.
    @Test
    public void testTurningKeepsFullscreen() throws Exception {
        enterFullscreen();

        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AtomicInteger rotation = new AtomicInteger();
        AtomicBoolean autoRotate = new AtomicBoolean();
        AtomicBoolean startedWide = new AtomicBoolean();
        scenario.onActivity(activity -> {
            rotation.set(activity.getDisplay().getRotation());
            autoRotate.set(
                Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1);
            startedWide.set(activity.getWebView().getWidth() > activity.getWebView().getHeight());
        });
        String wide = "window.innerWidth > window.innerHeight";
        String started = String.valueOf(startedWide.get());
        String turned = String.valueOf(!startedWide.get());
        try {
            automation.setRotation((rotation.get() + 1) % 4);
            assertEquals(turned, waitForJavaScriptResult(wide, turned));
            assertTrue(isFullscreen());
            assertEquals("true", waitForJavaScriptResult(FILLS, "true"));

            automation.setRotation(rotation.get());
            assertEquals(started, waitForJavaScriptResult(wide, started));
            assertTrue(isFullscreen());
            assertEquals("true", waitForJavaScriptResult(FILLS, "true"));
            assertEquals("1", waitForJavaScriptResult("changes", "1"));
            assertNoMoreEvents();
        } finally {
            automation.setRotation(autoRotate.get() ? UiAutomation.ROTATION_UNFREEZE : rotation.get());
        }
    }

    // Asking again while fullscreen, for the same element or for another one, does not tell the app again, since the
    // WebView is fullscreen already. The page then leaves fullscreen once.
    @Test
    public void testRequestingFullscreenAgain() throws Exception {
        enterFullscreen();

        for (String id : new String[] {"box", "other"}) {
            evaluateJavascript("request('" + id + "'); 'requested'");
            assertEquals("true", waitForJavaScriptResult("settled != null", "true"));
            assertNoMoreEvents();
            assertTrue(isFullscreen());
        }

        // Each element asked for may have stayed fullscreen, so the page leaves fullscreen until none is.
        evaluateJavascript("(async () => { while (document.fullscreenElement) await document.exitFullscreen(); })();"
                           + " 'left'");
        assertNextEvent(EXIT);
        assertFalse(isFullscreen());
        assertEquals(NONE, waitForJavaScriptResult(FULLSCREEN_ELEMENT, NONE));
        assertNoMoreEvents();
        enterFullscreen();
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

    // The page is gone with its web process, and the app gets its screen back before it hears so. A page loaded again
    // in the view cannot go fullscreen yet, as WebKit still counts the view as fullscreen. Once WebKit is fixed, this
    // test should load the page again and go fullscreen.
    @Test
    public void testEndedWebProcessLeavesFullscreen() throws Exception {
        TestWebViewClient client = new TestWebViewClient() {
            @Override
            public void onWebProcessTerminated(@NonNull WebView view, int reason) {
                events.add(TERMINATED);
            }
        };
        scenario.onActivity(activity -> activity.getWebView().setWebViewClient(client));
        enterFullscreen();

        scenario.onActivity(activity -> activity.getWebView().terminateWebProcess());
        assertNextEvent(EXIT);
        assertNextEvent(TERMINATED);
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

    // The app hears that the page left fullscreen as the view hands it over, once the view is done with it, and only
    // then. Shown again, the page can go fullscreen again.
    @Test
    public void testReleasedPageLeavesFullscreen() throws Exception {
        enterFullscreen();
        AtomicReference<String> pageOnExit = new AtomicReference<>();
        onExit.set(view -> pageOnExit.set(view.getPage() == null ? "released" : "still shown"));
        AtomicReference<WebPage> released = new AtomicReference<>();
        try {
            scenario.onActivity(activity -> released.set(activity.getWebView().releasePage()));
            assertNextEvent(EXIT);
            assertEquals("released", pageOnExit.get());
            assertFalse(isFullscreen());
            assertEquals(NONE, WebPageTestHelper.waitForResult(released.get(), FULLSCREEN_ELEMENT, NONE));

            scenario.onActivity(activity -> activity.getWebView().setPage(released.get()));
            assertEquals("\"visible\"", waitForJavaScriptResult("document.visibilityState", "\"visible\""));
            assertNoMoreEvents();
            enterFullscreen();
        } finally {
            scenario.onActivity(activity -> {
                if (released.get() != null)
                    released.get().destroy();
            });
        }
    }

    // The app hears that the page left fullscreen as the page that the view made is destroyed, once it is, and only
    // then: showing another page later does not tell it again.
    @Test
    public void testDestroyedPageLeavesFullscreen() throws Exception {
        enterFullscreen();
        AtomicReference<String> pageOnExit = new AtomicReference<>();
        onExit.set(view -> {
            WebPage page = view.getPage();
            pageOnExit.set(page != null && page.isDestroyed() ? "destroyed" : "not destroyed");
        });
        AtomicReference<WebContext> context = new AtomicReference<>();
        AtomicReference<WebPage> other = new AtomicReference<>();
        try {
            scenario.onActivity(activity -> activity.getWebView().getPage().destroy());
            assertNextEvent(EXIT);
            assertEquals("destroyed", pageOnExit.get());
            assertFalse(isFullscreen());

            scenario.onActivity(activity -> {
                context.set(new WebContext(activity, false, true));
                other.set(new WebPage(context.get()));
                activity.getWebView().setPage(other.get());
            });
            assertNoMoreEvents();
        } finally {
            scenario.onActivity(activity -> {
                activity.getWebView().destroy();
                if (other.get() != null)
                    other.get().destroy();
                if (context.get() != null)
                    context.get().destroy();
            });
        }
    }

    // The app hears that the page left fullscreen before the view is destroyed, also when the chrome client destroys
    // the view as it hears that a color request of the page that leaves is gone, before the view could tell.
    @Test
    public void testDestroyOnHideOfAColorRequestOfAFullscreenPage() throws Exception {
        enterFullscreen();
        evaluateJavascript("var field = document.createElement('input'); field.type = 'color';"
                           + " document.getElementById('box').appendChild(field); field.click(); 'opened'");
        WebPageTestHelper.waitFor("The chrome client got no color request", () -> colorRequests.get() == 1);
        AtomicReference<String> viewOnExit = new AtomicReference<>();
        onExit.set(view -> viewOnExit.set(view.getPage() != null ? "not destroyed" : "destroyed"));
        onHideColorChooser.set(WebView::destroy);
        AtomicReference<WebContext> context = new AtomicReference<>();
        AtomicReference<WebPage> other = new AtomicReference<>();
        try {
            scenario.onActivity(activity -> {
                context.set(new WebContext(activity, false, true));
                other.set(new WebPage(context.get()));
                activity.getWebView().setPage(other.get());
            });
            assertNextEvent(EXIT);
            assertEquals("not destroyed", viewOnExit.get());
            scenario.onActivity(activity -> {
                assertNull(activity.getWebView().getPage());
                assertNull(other.get().getView());
            });
            assertNoMoreEvents();
        } finally {
            scenario.onActivity(activity -> {
                if (other.get() != null)
                    other.get().destroy();
                if (context.get() != null)
                    context.get().destroy();
            });
        }
    }

    // The app hears that the page left fullscreen as it left the view once the view is done with it, so it may show
    // yet another page then.
    @Test
    public void testAnotherPageShownOnExit() throws Exception {
        enterFullscreen();
        AtomicReference<WebContext> context = new AtomicReference<>();
        AtomicReference<WebPage> second = new AtomicReference<>();
        AtomicReference<WebPage> third = new AtomicReference<>();
        scenario.onActivity(activity -> {
            context.set(new WebContext(activity, false, true));
            second.set(new WebPage(context.get()));
            third.set(new WebPage(context.get()));
            third.get().loadHtml("<p>Third</p>", null);
        });
        try {
            onExit.set(view -> view.setPage(third.get()));
            scenario.onActivity(activity -> activity.getWebView().setPage(second.get()));
            assertNextEvent(EXIT);
            scenario.onActivity(activity -> {
                WebView view = activity.getWebView();
                assertEquals(third.get(), view.getPage());
                assertEquals(view, third.get().getView());
                assertNull(second.get().getView());
                assertFalse(view.isFullscreen());
            });
            assertEquals("\"visible\"",
                         WebPageTestHelper.waitForResult(third.get(), "document.visibilityState", "\"visible\""));
            assertNoMoreEvents();
        } finally {
            scenario.onActivity(activity -> {
                second.get().destroy();
                third.get().destroy();
                context.get().destroy();
            });
        }
    }

    // The app may destroy the view when it hears that its page left fullscreen as the view is destroyed.
    @Test
    public void testDestroyOnExitOfDestroy() throws Exception {
        enterFullscreen();

        onExit.set(WebView::destroy);
        scenario.onActivity(activity -> activity.getWebView().destroy());
        assertNextEvent(EXIT);
        scenario.onActivity(activity -> assertNull(activity.getWebView().getInternalWebKitWebView()));
        assertNoMoreEvents();
    }

    // The app may destroy the view when it hears that its page left fullscreen as the activity stops.
    @Test
    public void testDestroyOnExitOfAStoppedActivity() throws Exception {
        enterFullscreen();

        onExit.set(WebView::destroy);
        scenario.moveToState(Lifecycle.State.CREATED);
        assertNextEvent(EXIT);
        scenario.onActivity(activity -> assertNull(activity.getWebView().getInternalWebKitWebView()));
        assertNoMoreEvents();
    }
}
