package com.wpe.wpeview;

import androidx.test.core.app.ActivityScenario;

import org.junit.Assert;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WPEViewActivityScenarioHelper {
    public static void loadHtmlSync(ActivityScenario<WPEViewTestActivity> scenario, TestWPEViewClient wpeViewClient,
                                    String html) {
        scenario.onActivity(activity -> {
            activity.getWPEView().setWPEViewClient(wpeViewClient);
            activity.getWPEView().loadHtml(html, null);
        });
        wpeViewClient.getOnPageFinishedHelper().waitForCallback(2, TimeUnit.SECONDS);
    }

    public static String evaluateJavaScriptAndWaitForResult(ActivityScenario<WPEViewTestActivity> scenario,
                                                            TestWPEViewClient wpeViewClient, String script)
        throws TimeoutException {
        TestWPEViewClient.OnEvaluateJavaScriptResultHelper onEvaluateJavaScriptResultHelper =
            wpeViewClient.getOnEvaluateJavascriptResultHelper();
        scenario.onActivity(
            activity -> { onEvaluateJavaScriptResultHelper.evaluateJavascript(activity.getWPEView(), script); });
        onEvaluateJavaScriptResultHelper.waitUntilHasValue(5, TimeUnit.SECONDS);
        Assert.assertTrue("Failed to retrieve JavaScript evaluation results.",
                          onEvaluateJavaScriptResultHelper.hasValue());
        return wpeViewClient.getOnEvaluateJavascriptResultHelper().getResultAndClear();
    }
}
