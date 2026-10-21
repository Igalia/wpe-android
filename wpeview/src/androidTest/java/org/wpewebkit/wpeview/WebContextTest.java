package org.wpewebkit.wpeview;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WebContextTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // Polls window.devicePixelRatio until it is the expected one, and returns the last value.
    private static double waitForDevicePixelRatio(ActivityScenario<WebViewTestActivity> scenario,
                                                  TestWebViewClient webViewClient, double expected) throws Throwable {
        double ratio = 0;
        for (int i = 0; i < 100; i++) {
            ratio = Double.parseDouble(WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                scenario, webViewClient, "window.devicePixelRatio"));
            if (Math.abs(ratio - expected) < 0.01)
                break;
            Thread.sleep(100);
        }
        return ratio;
    }

    /**
     * Every WebContext uses the display of the process, and destroying one leaves that display
     * working for the others.
     */
    @Test
    public void testContextsShareTheDisplay() throws Throwable {
        AtomicBoolean sameDisplay = new AtomicBoolean(false);
        AtomicLong displayAfterDestroy = new AtomicLong(0);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                WebContext first = new WebContext(activity, false, true);
                WebContext second = new WebContext(activity, false, true);
                try {
                    sameDisplay.set(first.getWPEDisplay() == second.getWPEDisplay());
                    first.destroy();
                    first = null;
                    displayAfterDestroy.set(second.getWPEDisplay().getNativePtr());
                } finally {
                    if (first != null)
                        first.destroy();
                    second.destroy();
                }
            })
            .moveToState(Lifecycle.State.RESUMED);

        assertTrue("Two WebContexts got different displays", sameDisplay.get());
        assertNotEquals("Destroying a WebContext destroyed the shared display", 0, displayAfterDestroy.get());

        // The activity's own WebView uses the same display, and still loads pages and runs scripts.
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, "<html><body></body></html>");
        assertEquals(
            "2", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, "1 + 1"));
    }

    /**
     * A new display size reaches the pages of an activity that handles that configuration change itself, as the
     * test activity does, and keeps its WebViews.
     */
    @Test
    public void testDisplaySizeChangeReachesThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, "<html><body></body></html>");

        String densities = WebViewActivityScenarioHelper.shell("wm density");
        Matcher physical = Pattern.compile("Physical density: (\\d+)").matcher(densities);
        Matcher override = Pattern.compile("Override density: (\\d+)").matcher(densities);
        assertTrue("Unexpected output of wm density: " + densities, physical.find());
        boolean overridden = override.find();
        int density = Integer.parseInt(overridden ? override.group(1) : physical.group(1));
        String restore = overridden ? "wm density " + density : "wm density reset";
        int largerDensity = density + density / 4;

        double ratio;
        try {
            WebViewActivityScenarioHelper.shell("wm density " + largerDensity);
            ratio = waitForDevicePixelRatio(scenario, webViewClient, largerDensity / 160.0);
        } finally {
            WebViewActivityScenarioHelper.shell(restore);
        }
        assertEquals("The page did not get the new display size", largerDensity / 160.0, ratio, 0.01);
        assertEquals("The page did not get the display size back", density / 160.0,
                     waitForDevicePixelRatio(scenario, webViewClient, density / 160.0), 0.01);
    }
}
