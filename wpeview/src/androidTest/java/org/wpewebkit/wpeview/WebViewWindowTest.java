package org.wpewebkit.wpeview;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.wpewebkit.wpeview.WebViewActivityScenarioHelper.silentWavUrl;

/**
 * How the window of a WebView reaches its page: the page is hidden while its WebView is out of the
 * window or hidden in place, the two ways a browser hides a tab, or while the activity is stopped, and
 * shown again when it comes back. A page is in a window only while its WebView has a surface, and out of
 * it, it cannot load new media. And like on a desktop, the page has focus only while its window has.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewWindowTest {
    private static final String VISIBILITY_STATE = "document.visibilityState";
    private static final String VISIBLE = "\"visible\"";
    private static final String HIDDEN = "\"hidden\"";
    private static final String PENDING = "\"pending\"";
    private static final String LOADING = "\"loading\"";
    private static final String PLAYING = "\"playing\"";
    private static final String PAUSED = "\"paused\"";

    // load() starts loading a new media element, and state says whether it has started; a page out of its
    // window does not start loading new media until it is back in one. Loading needs no audio output.
    private static final String MEDIA_PAGE = "<html><body><script>var state = 'none';\n"
                                             + "function load() {\n"
                                             + "    state = 'pending';\n"
                                             + "    var audio = new Audio();\n"
                                             + "    audio.onloadstart = () => state = 'loading';\n"
                                             + "    audio.src = '" + silentWavUrl() + "';\n"
                                             + "}</script></body></html>";

    // audio plays silence in a loop. WebKit counts it as audible, like music: it has an audio track and it is
    // neither muted nor at volume 0. started becomes true when the promise of play() resolves.
    private static final String AUDIO_PAGE = "<html><body><script>var audio = new Audio('" + silentWavUrl() + "');\n"
                                             + "audio.loop = true;\n"
                                             + "var started = false;\n"
                                             + "function play() { audio.play().then(() => started = true); }\n"
                                             + "function state() { return audio.paused ? 'paused' : 'playing'; }\n"
                                             + "</script></body></html>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private ActivityScenario<WebViewTestActivity> loadPage(TestWebViewClient webViewClient) throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, "<html><body></body></html>");
        assertEquals(VISIBLE,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, VISIBLE));
        return scenario;
    }

    @Test
    public void testWebViewTakenOutOfTheWindowAndBack() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(webViewClient);

        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        scenario.onActivity(activity -> {
            WebView webView = activity.getWebView();
            parent.set((ViewGroup)webView.getParent());
            parent.get().removeView(webView);
        });
        assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, HIDDEN));

        scenario.onActivity(activity -> parent.get().addView(activity.getWebView()));
        assertEquals(VISIBLE,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, VISIBLE));
    }

    @Test
    public void testWebViewOfAStoppedActivity() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(webViewClient);

        // A stopped activity loses the surface of its WebView, which stays attached to the window.
        scenario.moveToState(Lifecycle.State.CREATED);
        assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, HIDDEN));

        scenario.moveToState(Lifecycle.State.RESUMED);
        assertEquals(VISIBLE,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, VISIBLE));
    }

    @Test
    public void testStoppedActivityDoesNotLoadMedia() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), MEDIA_PAGE);
        WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "load(); state", PENDING);
        assertEquals(LOADING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state", LOADING));

        scenario.moveToState(Lifecycle.State.CREATED);
        assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, HIDDEN));
        assertEquals(PENDING,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "load(); state", PENDING));
        Thread.sleep(3000);
        assertEquals(PENDING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state", PENDING));

        scenario.moveToState(Lifecycle.State.RESUMED);
        assertEquals(LOADING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state", LOADING));
    }

    /** A tab that loads its page before it is ever shown is out of the window until it gets a surface. */
    @Test
    public void testWebViewThatNeverHadASurfaceIsOutOfTheWindow() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<WebView> tab = new AtomicReference<>();
        Function<WebViewTestActivity, WebView> inTab = activity -> tab.get();
        try {
            scenario.onActivity(activity -> {
                tab.set(new WebView(activity, WebViewActivityScenarioHelper.sharedWebContext(activity)));
                tab.get().setWebViewClient(webViewClient);
                tab.get().loadHtml(MEDIA_PAGE, null);
            });
            webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);

            assertEquals(PENDING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inTab,
                                                                                        "load(); state", PENDING));
            Thread.sleep(3000);
            assertEquals(PENDING,
                         WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inTab, "state", PENDING));

            scenario.onActivity(activity -> {
                activity.addContentView(tab.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                              ViewGroup.LayoutParams.MATCH_PARENT));
            });
            assertEquals(LOADING,
                         WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, inTab, "state", LOADING));
        } finally {
            scenario.onActivity(activity -> {
                WebView webView = tab.get();
                if (webView != null) {
                    if (webView.getParent() != null)
                        ((ViewGroup)webView.getParent()).removeView(webView);
                    webView.destroy();
                }
            });
        }
    }

    @Test
    public void testWebViewHiddenInPlaceAndShown() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(webViewClient);

        scenario.onActivity(activity -> activity.getWebView().setVisibility(View.GONE));
        assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, HIDDEN));

        scenario.onActivity(activity -> activity.getWebView().setVisibility(View.VISIBLE));
        assertEquals(VISIBLE,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, VISIBLE));
    }

    @Test
    public void testParentOfTheWebViewHiddenAndShown() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(webViewClient);

        scenario.onActivity(activity -> ((View)activity.getWebView().getParent()).setVisibility(View.INVISIBLE));
        assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, HIDDEN));

        scenario.onActivity(activity -> ((View)activity.getWebView().getParent()).setVisibility(View.VISIBLE));
        assertEquals(VISIBLE,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, VISIBLE));
    }

    // Turns the setting on if requirePageVisibility is true, and otherwise leaves its default.
    private ActivityScenario<WebViewTestActivity> playAudio(boolean requirePageVisibility) throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(new TestWebViewClient());
        AtomicBoolean required = new AtomicBoolean();
        scenario.onActivity(activity -> {
            WebSettings settings = activity.getWebView().getSettings();
            if (requirePageVisibility)
                settings.setAudiblePlaybackRequiresPageVisibility(true);
            required.set(settings.getAudiblePlaybackRequiresPageVisibility());
        });
        assertEquals(requirePageVisibility, required.get());
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, AUDIO_PAGE);
        // WebKit admits a playback some time after play(), and hiding the page before then changes what it does: it
        // takes media that has not started playing for silent and suspends it until the page is shown again, and a
        // playback it admits after the page was hidden resumes the media despite the setting. The promise of play()
        // tells when the playback was admitted, but only for media that could already play: for media still loading,
        // the data that arrives resolves it.
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                 scenario, "audio.readyState >= audio.HAVE_FUTURE_DATA", "true"));
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, "play(); 0");
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "started", "true"));
        assertEquals(PLAYING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state()", PLAYING));
        return scenario;
    }

    private static void takeWebViewOutOfTheWindow(ActivityScenario<WebViewTestActivity> scenario,
                                                  AtomicReference<ViewGroup> parent) {
        scenario.onActivity(activity -> {
            WebView webView = activity.getWebView();
            parent.set((ViewGroup)webView.getParent());
            parent.get().removeView(webView);
        });
    }

    /** A tab that is not shown pauses its audible media, and plays it again when it is shown. */
    @Test
    public void testAudibleMediaPausesWhileThePageIsHidden() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = playAudio(true);

        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        takeWebViewOutOfTheWindow(scenario, parent);
        assertEquals(PAUSED, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state()", PAUSED));

        scenario.onActivity(activity -> parent.get().addView(activity.getWebView()));
        assertEquals(PLAYING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state()", PLAYING));
    }

    /** By default, audible media keeps playing in a hidden page, as it does without the setting in WebKit. */
    @Test
    public void testAudibleMediaKeepsPlayingWhileThePageIsHiddenByDefault() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = playAudio(false);

        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        takeWebViewOutOfTheWindow(scenario, parent);
        assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, VISIBILITY_STATE, HIDDEN));
        String time = WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, new TestWebViewClient(), "audio.currentTime");
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                 scenario, "state() == 'playing' && audio.currentTime != " + time, "true"));

        scenario.onActivity(activity -> parent.get().addView(activity.getWebView()));
    }

    // Android gives the focus to the window of a new activity a moment after it is shown, and not at all while a window
    // over it has the focus, such as the notification shade.
    private static void waitForWindowFocus(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        AtomicBoolean focused = new AtomicBoolean();
        for (int i = 0; i < 100 && !focused.get(); i++) {
            scenario.onActivity(activity -> focused.set(activity.hasWindowFocus()));
            if (!focused.get())
                Thread.sleep(100);
        }
        if (focused.get())
            return;
        String windows = WebViewActivityScenarioHelper.shell("dumpsys window displays");
        Matcher focusedWindow = Pattern.compile("mCurrentFocus=.*").matcher(windows);
        fail("The window of the activity never got the focus, " +
             (focusedWindow.find() ? focusedWindow.group() : "no window has it"));
    }

    /** A destroyed WebView that stays in the window ignores input, focus and size changes, and its surface. */
    @Test
    public void testDestroyedWebViewInTheWindow() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(new TestWebViewClient());
        scenario.onActivity(activity -> {
            WebView webView = activity.getWebView();
            webView.requestFocus();
            webView.destroy();

            webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
            long now = SystemClock.uptimeMillis();
            MotionEvent touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0);
            webView.dispatchTouchEvent(touch);
            touch.recycle();
            webView.clearFocus();
            webView.setVisibility(View.INVISIBLE);
            webView.setVisibility(View.VISIBLE);
            webView.setLayoutParams(new LinearLayout.LayoutParams(200, 200));
            ViewGroup parent = (ViewGroup)webView.getParent();
            parent.removeView(webView);
            parent.addView(webView);
        });
        getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> assertEquals(200, activity.getWebView().getWidth()));
    }

    @Test
    public void testDialogTakesTheFocusFromThePage() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(webViewClient);
        waitForWindowFocus(scenario);

        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        assertEquals("true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "document.hasFocus()", "true"));

        AtomicReference<Dialog> dialog = new AtomicReference<>();
        scenario.onActivity(
            activity -> dialog.set(new AlertDialog.Builder(activity).setMessage("A dialog over the page").show()));
        try {
            assertEquals("false", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "document.hasFocus()",
                                                                                        "false"));
        } finally {
            scenario.onActivity(activity -> dialog.get().dismiss());
        }
        assertEquals("true",
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "document.hasFocus()", "true"));
    }
}
