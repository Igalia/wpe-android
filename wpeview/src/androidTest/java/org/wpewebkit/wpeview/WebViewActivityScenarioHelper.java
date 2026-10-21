package org.wpewebkit.wpeview;

import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class WebViewActivityScenarioHelper {
    private static WebContext sharedWebContext;

    /**
     * A WebContext for the WebViews a test adds besides the activity's own. It lives as long as the test process:
     * destroying a WebContext after one of its pages has loaded crashes the process a moment later, a bug of its
     * own.
     */
    public static WebContext sharedWebContext(WebViewTestActivity activity) {
        if (sharedWebContext == null)
            sharedWebContext = new WebContext(activity.getApplicationContext(), false, true);
        return sharedWebContext;
    }

    /** Runs a shell command as the shell user, and returns its output. */
    public static String shell(String command) throws IOException {
        ParcelFileDescriptor output =
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int read; (read = stream.read(buffer)) != -1;)
                bytes.write(buffer, 0, read);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

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
        return evaluateJavaScriptAndWaitForResult(scenario, WebViewTestActivity::getWebView,
                                                  webViewClient.getOnEvaluateJavascriptResultHelper(), script);
    }

    private static String evaluateJavaScriptAndWaitForResult(
        ActivityScenario<WebViewTestActivity> scenario, Function<WebViewTestActivity, WebView> webView,
        TestWebViewClient.OnEvaluateJavaScriptResultHelper onEvaluateJavaScriptResultHelper, String script)
        throws TimeoutException {
        scenario.onActivity(
            activity -> { onEvaluateJavaScriptResultHelper.evaluateJavascript(webView.apply(activity), script); });
        onEvaluateJavaScriptResultHelper.waitUntilHasValue(30, TimeUnit.SECONDS);
        Assert.assertTrue("Failed to retrieve JavaScript evaluation results.",
                          onEvaluateJavaScriptResultHelper.hasValue());
        return onEvaluateJavaScriptResultHelper.getResultAndClear();
    }

    /**
     * Runs the script until it returns the expected result, for state that WebKit updates asynchronously,
     * and returns the last result.
     */
    public static String waitForJavaScriptResult(ActivityScenario<WebViewTestActivity> scenario, String script,
                                                 String expected) throws InterruptedException, TimeoutException {
        return waitForJavaScriptResult(scenario, WebViewTestActivity::getWebView, script, expected);
    }

    /** Like {@link #waitForJavaScriptResult(ActivityScenario, String, String)}, in another WebView. */
    public static String waitForJavaScriptResult(ActivityScenario<WebViewTestActivity> scenario,
                                                 Function<WebViewTestActivity, WebView> webView, String script,
                                                 String expected) throws InterruptedException, TimeoutException {
        TestWebViewClient.OnEvaluateJavaScriptResultHelper onEvaluateJavaScriptResultHelper =
            new TestWebViewClient.OnEvaluateJavaScriptResultHelper();
        String result = null;
        for (int i = 0; i < 100; i++) {
            result = evaluateJavaScriptAndWaitForResult(scenario, webView, onEvaluateJavaScriptResultHelper, script);
            if (expected.equals(result))
                break;
            Thread.sleep(100);
        }
        return result;
    }
}
