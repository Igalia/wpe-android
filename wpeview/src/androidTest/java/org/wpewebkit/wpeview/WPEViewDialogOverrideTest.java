package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class WPEViewDialogOverrideTest {
    private static final String EMPTY_PAGE = "<!doctype html>"
                                             + "<title>Dialog Test</title><p>Testcase.</p>";

    @Rule
    public ActivityScenarioRule<WPEViewTestActivity> wpeViewActivityTestRule =
        new ActivityScenarioRule<>(WPEViewTestActivity.class);

    @Test
    public void testOverrideAlertHandling() throws Throwable {
        final String alertText = "Hello World!";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWPEViewClient wpeViewClient = new TestWPEViewClient();
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWPEView().setWPEChromeClient(new WPEChromeClient() {
                    @Override
                    public boolean onJsAlert(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                                             @NonNull WPEJsResult result) {
                        callbackCalled.set(true);
                        result.confirm();
                        return true;
                    }
                });
            })
            .moveToState(Lifecycle.State.RESUMED);

        WPEViewActivityScenarioHelper.loadHtmlSync(scenario, wpeViewClient, EMPTY_PAGE);
        WPEViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, wpeViewClient,
                                                                         "alert('" + alertText + "')");

        Assert.assertTrue(callbackCalled.get());
    }

    @Test
    public void testOverrideConfirmHandlingConfirmed() throws Throwable {
        final String confirmText = "Would you like to confirm?";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWPEViewClient wpeViewClient = new TestWPEViewClient();
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWPEView().setWPEChromeClient(new WPEChromeClient() {
                    @Override
                    public boolean onJsConfirm(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                                               @NonNull WPEJsResult result) {
                        callbackCalled.set(true);
                        result.confirm();
                        return true;
                    }
                });
                activity.getWPEView().loadHtml(EMPTY_PAGE, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        WPEViewActivityScenarioHelper.loadHtmlSync(scenario, wpeViewClient, EMPTY_PAGE);
        String result = WPEViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, wpeViewClient, "confirm('" + confirmText + "')");

        Assert.assertTrue(callbackCalled.get());
        Assert.assertEquals("true", result);
    }

    @Test
    public void testOverrideConfirmHandlingCancelled() throws Throwable {
        final String confirmText = "Would you like to confirm?";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWPEViewClient wpeViewClient = new TestWPEViewClient();
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWPEView().setWPEChromeClient(new WPEChromeClient() {
                    @Override
                    public boolean onJsConfirm(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                                               @NonNull WPEJsResult result) {
                        callbackCalled.set(true);
                        result.cancel();
                        return true;
                    }
                });
                activity.getWPEView().loadHtml(EMPTY_PAGE, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        WPEViewActivityScenarioHelper.loadHtmlSync(scenario, wpeViewClient, EMPTY_PAGE);
        String result = WPEViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, wpeViewClient, "confirm('" + confirmText + "')");

        Assert.assertTrue(callbackCalled.get());
        Assert.assertEquals("false", result);
    }

    @Test
    public void testOverridePromptHandling() throws Throwable {
        final String promptText = "How is the weather today?";
        final String promptDefault = "It's sunny and warm today.";
        final String promptResult = "It’s so hot.";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWPEViewClient wpeViewClient = new TestWPEViewClient();
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWPEView().setWPEChromeClient(new WPEChromeClient() {
                    @Override
                    public boolean onJsPrompt(@NonNull WPEView view, @NonNull String url, @NonNull String message,
                                              @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
                        Assert.assertEquals(promptText, message);
                        Assert.assertEquals(promptDefault, defaultValue);

                        result.confirm(promptResult);
                        callbackCalled.set(true);
                        return true;
                    }
                });
            })
            .moveToState(Lifecycle.State.RESUMED);
        WPEViewActivityScenarioHelper.loadHtmlSync(scenario, wpeViewClient, EMPTY_PAGE);
        String result = WPEViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, wpeViewClient, "prompt(\"" + promptText + "\", \"" + promptDefault + "\")");
        Assert.assertTrue(callbackCalled.get());
        Assert.assertEquals("\"" + promptResult + "\"", result);
    }
}
