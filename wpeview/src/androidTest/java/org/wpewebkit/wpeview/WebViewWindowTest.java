package org.wpewebkit.wpeview;

import android.app.AlertDialog;
import android.app.Dialog;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

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

    // load() starts loading a new media element, and state says whether it has started; a page out of its
    // window does not start loading new media until it is back in one. Loading needs no audio output.
    private static final String MEDIA_PAGE = "<html><body><script>var state = 'none';\n"
                                             + "function load() {\n"
                                             + "    state = 'pending';\n"
                                             + "    var audio = new Audio();\n"
                                             + "    audio.onloadstart = () => state = 'loading';\n"
                                             + "    audio.src = '" + silentWavUrl() + "';\n"
                                             + "}</script></body></html>";

    // A tenth of a second of silence, as a WAV file in a data: URL.
    private static String silentWavUrl() {
        int samples = 800;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short)1).putShort((short)1).putInt(8000).putInt(8000).putShort((short)1).putShort((short)8);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples);
        while (wav.hasRemaining())
            wav.put((byte)0x80);
        return "data:audio/wav;base64," + Base64.encodeToString(wav.array(), Base64.NO_WRAP);
    }

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

    @Test
    public void testDialogTakesTheFocusFromThePage() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(webViewClient);

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
