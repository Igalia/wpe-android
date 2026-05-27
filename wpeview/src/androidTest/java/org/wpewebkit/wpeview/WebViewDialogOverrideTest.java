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
public class WebViewDialogOverrideTest {
    private static final String EMPTY_PAGE = "<!doctype html>"
                                             + "<title>Dialog Test</title><p>Testcase.</p>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    @Test
    public void testOverrideAlertHandling() throws Throwable {
        final String alertText = "Hello World!";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onJsAlert(@NonNull WebView view, @NonNull String url, @NonNull String message,
                                             @NonNull WPEJsResult result) {
                        callbackCalled.set(true);
                        result.confirm();
                        return true;
                    }
                });
            })
            .moveToState(Lifecycle.State.RESUMED);

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                         "alert('" + alertText + "')");

        Assert.assertTrue(callbackCalled.get());
    }

    @Test
    public void testOverrideConfirmHandlingConfirmed() throws Throwable {
        final String confirmText = "Would you like to confirm?";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onJsConfirm(@NonNull WebView view, @NonNull String url, @NonNull String message,
                                               @NonNull WPEJsResult result) {
                        callbackCalled.set(true);
                        result.confirm();
                        return true;
                    }
                });
                activity.getWebView().loadHtml(EMPTY_PAGE, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        String result = WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, webViewClient, "confirm('" + confirmText + "')");

        Assert.assertTrue(callbackCalled.get());
        Assert.assertEquals("true", result);
    }

    @Test
    public void testOverrideConfirmHandlingCancelled() throws Throwable {
        final String confirmText = "Would you like to confirm?";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onJsConfirm(@NonNull WebView view, @NonNull String url, @NonNull String message,
                                               @NonNull WPEJsResult result) {
                        callbackCalled.set(true);
                        result.cancel();
                        return true;
                    }
                });
                activity.getWebView().loadHtml(EMPTY_PAGE, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        String result = WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, webViewClient, "confirm('" + confirmText + "')");

        Assert.assertTrue(callbackCalled.get());
        Assert.assertEquals("false", result);
    }

    @Test
    public void testOverridePromptHandling() throws Throwable {
        final String promptText = "How is the weather today?";
        final String promptDefault = "It's sunny and warm today.";
        final String promptResult = "It’s so hot.";

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);

        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onJsPrompt(@NonNull WebView view, @NonNull String url, @NonNull String message,
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
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        String result = WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, webViewClient, "prompt(\"" + promptText + "\", \"" + promptDefault + "\")");
        Assert.assertTrue(callbackCalled.get());
        Assert.assertEquals("\"" + promptResult + "\"", result);
    }
}
