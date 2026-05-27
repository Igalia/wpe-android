package org.wpewebkit.wpeview;

import androidx.test.core.app.ActivityScenario;

import org.junit.Assert;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WebViewActivityScenarioHelper {
    public static void loadHtmlSync(ActivityScenario<WebViewTestActivity> scenario, TestWebViewClient webViewClient,
                                    String html) {
        scenario.onActivity(activity -> {
            activity.getWebView().setWebViewClient(webViewClient);
            activity.getWebView().loadHtml(html, null);
        });
        webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
    }

    public static String evaluateJavaScriptAndWaitForResult(ActivityScenario<WebViewTestActivity> scenario,
                                                            TestWebViewClient webViewClient, String script)
        throws TimeoutException {
        TestWebViewClient.OnEvaluateJavaScriptResultHelper onEvaluateJavaScriptResultHelper =
            webViewClient.getOnEvaluateJavascriptResultHelper();
        scenario.onActivity(
            activity -> { onEvaluateJavaScriptResultHelper.evaluateJavascript(activity.getWebView(), script); });
        onEvaluateJavaScriptResultHelper.waitUntilHasValue(30, TimeUnit.SECONDS);
        Assert.assertTrue("Failed to retrieve JavaScript evaluation results.",
                          onEvaluateJavaScriptResultHelper.hasValue());
        return webViewClient.getOnEvaluateJavascriptResultHelper().getResultAndClear();
    }
}
