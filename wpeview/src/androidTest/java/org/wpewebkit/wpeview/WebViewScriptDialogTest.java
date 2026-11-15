package org.wpewebkit.wpeview;

import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inspector.WindowInspector;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    // Answers on a thread of its own, as an app may, and waits for that thread. What goes wrong is kept for the test
    // to report, since the dialog's callbacks run on the main thread.
    private static void answerOnAnotherThread(@NonNull Runnable answer, @NonNull AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                answer.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException exception) {
            failure.compareAndSet(null, exception);
        }
    }

    /**
     * The app may answer a dialog from another thread. The answer reaches the page on the main thread, so not before
     * the call that asked returns.
     */
    @Test
    public void testAnswersFromAnotherThread() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger answeredInTheCall = new AtomicInteger();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
                // confirm('Yes') is confirmed, and the others are cancelled.
                @Override
                public boolean onJsConfirm(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                           @NonNull WPEJsResult result) {
                    answerOnAnotherThread("Yes".equals(message) ? result::confirm : result::cancel, failure);
                    if (((WebPage.ScriptDialog)result).isAnswered())
                        answeredInTheCall.incrementAndGet();
                    return true;
                }

                @Override
                public boolean onJsPrompt(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                          @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
                    answerOnAnotherThread(() -> result.confirm("From another thread"), failure);
                    if (((WebPage.ScriptDialog)result).isAnswered())
                        answeredInTheCall.incrementAndGet();
                    return true;
                }
            }))
            .moveToState(Lifecycle.State.RESUMED);

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        assertEquals("true", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                              "confirm('Yes')"));
        assertEquals("false", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                               "confirm('No')"));
        assertEquals("\"From another thread\"", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                                                    scenario, webViewClient, "prompt('Question', 'answer')"));
        assertNull(failure.get());
        assertEquals("A dialog was answered before the call that asked returned", 0, answeredInTheCall.get());
    }

    /**
     * Only the first answer to reach the main thread counts, from whichever thread it comes. The later ones do nothing,
     * also once the page has its answer, and do not release the dialog again.
     */
    @Test
    public void testOnlyTheFirstAnswerFromAnyThreadCounts() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WPEJsResult> firstResult = new AtomicReference<>();
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsConfirm(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                           @NonNull WPEJsResult result) {
                    if (!firstResult.compareAndSet(null, result)) {
                        result.confirm();
                        return true;
                    }
                    // The answers of one thread reach the main thread in their order.
                    answerOnAnotherThread(() -> {
                        result.cancel();
                        result.confirm();
                    }, failure);
                    return true;
                }
            }))
            .moveToState(Lifecycle.State.RESUMED);

        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, EMPTY_PAGE);
        assertEquals("false", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                               "confirm('First')"));
        answerOnAnotherThread(() -> firstResult.get().confirm(), failure);
        scenario.onActivity(activity -> firstResult.get().confirm());
        assertEquals("true", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                              "confirm('Second')"));
        assertNull(failure.get());
    }

    /** Destroying the WebView closes its built-in dialog, which would otherwise stay over the activity. */
    @Test
    public void testDestroyClosesTheBuiltInDialog() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<WebContext> webContext = new AtomicReference<>();
        AtomicReference<WebView> webView = new AtomicReference<>();
        try {
            // Built-in dialogs need a WebView made with the activity.
            scenario.onActivity(activity -> {
                webContext.set(new WebContext(activity, false, true));
                webView.set(new WebView(activity, webContext.get()));
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
        } finally {
            // Destroying the WebView again does nothing.
            scenario.onActivity(activity -> {
                if (webView.get() != null)
                    webView.get().destroy();
                if (webContext.get() != null)
                    webContext.get().destroy();
            });
        }
    }

    // The text field of the built-in prompt dialog that is shown, if any.
    private static EditText shownPromptField() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            EditText field = root.findViewById(org.wpewebkit.R.id.value);
            if (field != null)
                return field;
        }
        return null;
    }

    // Shows the built-in prompt dialog of a new WebView, and tells whether the input method may learn from its field.
    private boolean promptFieldMayLearn(boolean learningEnabled) throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<WebContext> webContext = new AtomicReference<>();
        AtomicReference<WebView> webView = new AtomicReference<>();
        AtomicReference<EditText> field = new AtomicReference<>();
        try {
            // Built-in dialogs need a WebView made with the activity.
            scenario.onActivity(activity -> {
                webContext.set(new WebContext(activity, false, true));
                webView.set(new WebView(activity, webContext.get()));
                webView.get().getSettings().setPersonalizedLearningEnabled(learningEnabled);
                webView.get().setWebViewClient(webViewClient);
                activity.addContentView(webView.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                                  ViewGroup.LayoutParams.MATCH_PARENT));
                webView.get().loadHtml(EMPTY_PAGE, null);
            });
            webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
            waitForActivityWindowFocus(scenario, true);

            scenario.onActivity(activity -> webView.get().evaluateJavascript("prompt('Question', 'answer')", null));
            for (int i = 0; i < 100 && field.get() == null; i++) {
                Thread.sleep(100);
                scenario.onActivity(activity -> field.set(shownPromptField()));
            }
            assertNotNull("The prompt dialog was not shown", field.get());
            return (field.get().getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        } finally {
            scenario.onActivity(activity -> {
                if (webView.get() != null)
                    webView.get().destroy();
                if (webContext.get() != null)
                    webContext.get().destroy();
            });
            waitForActivityWindowFocus(scenario, true);
        }
    }

    /** The answer to the built-in prompt dialog follows the setting for what the input method may learn. */
    @Test
    public void testBuiltInPromptFollowsTheLearningSetting() throws Throwable {
        assertTrue("The input method may not learn from the prompt", promptFieldMayLearn(true));
        assertFalse("The input method may learn from the prompt", promptFieldMayLearn(false));
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

    /**
     * WebDriver answers the dialogs of the pages it controls: neither the app nor the built-in dialog gets them, and
     * the page waits for WebDriver's answer.
     */
    @Test
    public void testDialogUnderAutomationWaitsForWebDriver() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicBoolean appAsked = new AtomicBoolean();
        CountDownLatch scriptsRun = new CountDownLatch(1);
        AtomicReference<String> scriptResult = new AtomicReference<>();
        AtomicReference<WebContext> webContext = new AtomicReference<>();
        AtomicReference<WebView> webView = new AtomicReference<>();
        try {
            // Made as the WebDriver app makes its views, with the application context, where a built-in dialog cannot
            // be shown and would be cancelled at once.
            scenario.onActivity(activity -> {
                webContext.set(new WebContext(activity, true, true));
                webView.set(new WebView(webContext.get(), true));
                webView.get().setWebViewClient(webViewClient);
                webView.get().setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onJsAlert(@NonNull WebView view, @Nullable String url, @Nullable String message,
                                             @NonNull WPEJsResult result) {
                        appAsked.set(true);
                        return false;
                    }
                });
                webView.get().loadHtml(EMPTY_PAGE, null);
            });
            webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);

            // The page runs scripts, so a script that does not end is held by its dialog.
            scenario.onActivity(
                activity -> webView.get().evaluateJavascript("'Ready'", result -> scriptsRun.countDown()));
            assertTrue("The page does not run scripts", scriptsRun.await(30, TimeUnit.SECONDS));

            scenario.onActivity(
                activity -> webView.get().evaluateJavascript("alert('Hello'); 'Answered'", scriptResult::set));
            // A dialog that is answered at once lets the script end within milliseconds.
            Thread.sleep(1000);
            assertFalse(appAsked.get());
            assertNull(scriptResult.get());
        } finally {
            // Destroying the WebView closes the dialog that waits for WebDriver.
            scenario.onActivity(activity -> {
                if (webView.get() != null)
                    webView.get().destroy();
                if (webContext.get() != null)
                    webContext.get().destroy();
            });
        }
    }

    /**
     * An answer after the WebView is destroyed does nothing, from any thread. Neither does one sent from another thread
     * before the destroy that reaches the main thread after it.
     */
    @Test
    public void testAnswerAfterDestroy() throws Throwable {
        TestWebViewClient webViewClient = new TestWebViewClient();
        AtomicReference<Throwable> failure = new AtomicReference<>();
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
            answerOnAnotherThread(() -> heldResult.get().confirm(), failure);
            activity.getWebView().destroy();
            heldResult.get().confirm();
            heldResult.get().cancel();
        });
        answerOnAnotherThread(() -> heldResult.get().confirm(), failure);
        // The answers from the other threads reach the main thread.
        scenario.onActivity(activity -> {});
        assertNull(failure.get());
    }
}
