package org.wpewebkit.wpeview;

import android.app.UiModeManager;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebViewActivityScenarioHelper {
    // A tenth of a second of silence, as a WAV file in a data: URL.
    public static String silentWavUrl() {
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

    /**
     * How many times Android started each running service of the app again after its process ended while the service
     * was bound, which it does only after a delay. The activity manager counts these restarts in the record of each
     * service.
     */
    public static Map<String, Integer> serviceRestarts() throws IOException {
        String packageName = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        Pattern serviceRecord = Pattern.compile("\\* ServiceRecord\\{\\S+ \\S+ ([^\\s}]+)");
        Pattern restartCount = Pattern.compile("\\brestartCount=(\\d+)");
        Map<String, Integer> restarts = new HashMap<>();
        String service = null;
        for (String line : shell("dumpsys activity services " + packageName).split("\n")) {
            Matcher matcher = serviceRecord.matcher(line);
            if (matcher.find()) {
                service = matcher.group(1);
                restarts.put(service, 0);
                continue;
            }
            matcher = restartCount.matcher(line);
            if (service != null && matcher.find())
                restarts.put(service, Integer.parseInt(matcher.group(1)));
        }
        // Called while services of the app run, so finding none means that the output was not understood.
        Assert.assertFalse("No service of " + packageName + " in the output of dumpsys", restarts.isEmpty());
        return restarts;
    }

    // Whether the device is in dark mode, which the application follows until a test gives it a night mode of its
    // own. The resources of the system are not asked: in the process of the app, they may have the app's night mode.
    private static Boolean deviceInDarkMode;

    /**
     * Gives the app a night mode, MODE_NIGHT_NO, MODE_NIGHT_YES or MODE_NIGHT_AUTO, which follows the device again, as
     * UiModeManager takes it, and waits until the application has it. Android keeps it after the app ends, so a test
     * that sets it sets MODE_NIGHT_AUTO again at its end.
     */
    public static void setNightMode(int mode) throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        if (deviceInDarkMode == null)
            deviceInDarkMode = context.getResources().getConfiguration().isNightModeActive();
        context.getSystemService(UiModeManager.class).setApplicationNightMode(mode);
        boolean dark =
            mode == UiModeManager.MODE_NIGHT_YES || mode == UiModeManager.MODE_NIGHT_AUTO && deviceInDarkMode;
        for (int i = 0; i < 50 && context.getResources().getConfiguration().isNightModeActive() != dark; i++)
            Thread.sleep(100);
        Assert.assertEquals(dark, context.getResources().getConfiguration().isNightModeActive());
    }

    public static void loadHtmlSync(ActivityScenario<WebViewTestActivity> scenario, TestWebViewClient webViewClient,
                                    String html) {
        loadHtmlSync(scenario, webViewClient, html, null);
    }

    /**
     * Loads the page as if it came from the base address. WebKit treats a page without one as blank, which in dark mode
     * is dark, as before the first page.
     */
    public static void loadHtmlSync(ActivityScenario<WebViewTestActivity> scenario, TestWebViewClient webViewClient,
                                    String html, String baseUri) {
        scenario.onActivity(activity -> {
            activity.getWebView().setWebViewClient(webViewClient);
            activity.getWebView().loadHtml(html, baseUri);
        });
        webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
    }

    public static void loadUrlSync(ActivityScenario<WebViewTestActivity> scenario, TestWebViewClient webViewClient,
                                   String url) {
        scenario.onActivity(activity -> {
            activity.getWebView().setWebViewClient(webViewClient);
            activity.getWebView().loadUrl(url);
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
