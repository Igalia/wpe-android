package org.wpewebkit.wpeview;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class WebViewScriptDialogTest {
    private static final String EMPTY_PAGE = "<!doctype html><title>Dialog Test</title><p>Testcase.</p>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private static boolean activityHasWindowFocus(ActivityScenario<WebViewTestActivity> scenario) {
        AtomicBoolean focused = new AtomicBoolean();
        scenario.onActivity(activity -> focused.set(activity.hasWindowFocus()));
        return focused.get();
    }

    private static void waitForActivityWindowFocus(ActivityScenario<WebViewTestActivity> scenario, boolean expected)
        throws InterruptedException {
        for (int i = 0; i < 100 && activityHasWindowFocus(scenario) != expected; i++)
            Thread.sleep(100);
        assertEquals(expected, activityHasWindowFocus(scenario));
    }

    /** The page gets the first answer to a dialog. The others do nothing, and do not release it again. */
    @Test
    public void testOnlyTheFirstAnswerCounts() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsConfirm(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                           @NonNull WPEJsResult result) {
                    result.confirm();
                    result.cancel();
                    result.confirm();
                    return true;
                }
            }))
            .moveToState(Lifecycle.State.RESUMED);

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        assertEquals("true", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                              "confirm('First')"));
        assertEquals("true", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                              "confirm('Second')"));
    }

    /** Destroying the WebView closes its built-in dialog, which would otherwise stay over the activity. */
    @Test
    public void testDestroyClosesTheBuiltInDialog() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<WebView> webView = new AtomicReference<>();
        // Built-in dialogs need a WebView made with the activity.
        scenario.onActivity(activity -> {
            webView.set(new WebView(activity, WebViewActivityScenarioHelper.sharedWebContext(activity)));
            webView.get().setWebViewClient(webViewClient);
            activity.addContentView(webView.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                              ViewGroup.LayoutParams.MATCH_PARENT));
            webView.get().loadHtml(EMPTY_PAGE, null);
        });
        webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
        waitForActivityWindowFocus(scenario, true);

        scenario.onActivity(activity -> webView.get().evaluateJavascript("alert('Hello')", null));
        waitForActivityWindowFocus(scenario, false);

        scenario.onActivity(activity -> webView.get().destroy());
        waitForActivityWindowFocus(scenario, true);
    }

    /**
     * A WebView made with the application context cannot show its built-in dialogs: they are cancelled, and the page
     * goes on.
     */
    @Test
    public void testBuiltInDialogThatCannotBeShown() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);

        assertEquals("false", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                                  scenario, webViewClient, "alert('Hello'); confirm('Question')"));
        scenario.onActivity(activity -> activity.getWebView().destroy());
    }

    /** An answer that comes after the WebView is destroyed does nothing. */
    @Test
    public void testAnswerAfterDestroy() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<WPEJsResult> heldResult = new AtomicReference<>();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsConfirm(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                           @NonNull WPEJsResult result) {
                    heldResult.set(result);
                    return true;
                }
            }))
            .moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);

        scenario.onActivity(activity -> activity.getWebView().evaluateJavascript("confirm('Question')", null));
        for (int i = 0; i < 100 && heldResult.get() == null; i++)
            Thread.sleep(100);
        assertNotNull(heldResult.get());

        scenario.onActivity(activity -> {
            activity.getWebView().destroy();
            heldResult.get().confirm();
            heldResult.get().cancel();
        });
    }
}
