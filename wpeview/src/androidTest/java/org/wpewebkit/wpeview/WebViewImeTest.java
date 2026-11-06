package org.wpewebkit.wpeview;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.WPEInputMethodContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * These tests act as the input method: they get the WebView's InputConnection and send text through it. So their
 * activity keeps Android's input method away from its window, see {@link WebViewTestActivity#withoutInputMethod}.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewImeTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule = new ActivityScenarioRule<>(
        WebViewTestActivity.withoutInputMethod(InstrumentationRegistry.getInstrumentation().getTargetContext()));

    /**
     * Focusing an editable element in the page makes the WebView a text editor with an {@link InputConnection} for
     * text, through the input method context of WPE (its focus-in reaches WebView.onImeFieldFocused). This needs the
     * focus of the view, not that of the window, which only decides whether the keyboard shows.
     */
    @Test
    public void testEditableFocusEngagesInputConnection() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        // A user focuses the view by tapping it, and the test focuses the editable from a script.
        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(),
                                                   "<html><body contenteditable id='editor'></body></html>");
        scenario.moveToState(Lifecycle.State.RESUMED);

        EditorInfo editorInfo = new EditorInfo();
        focusEditor(scenario, "editor", editorInfo);
        assertTrue("InputConnection is not configured for text input",
                   (editorInfo.inputType & EditorInfo.TYPE_CLASS_TEXT) == EditorInfo.TYPE_CLASS_TEXT);
    }

    /**
     * A WebView destroyed while an input method is connected to its page: the connection the input method still
     * holds takes text without reaching the destroyed page, the WebView gives out no new connection, and a focus
     * change that arrives late does not make it a text editor again.
     */
    @Test
    public void testInputMethodOfADestroyedWebView() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicReference<WebView> webView = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        try {
            scenario.onActivity(activity -> {
                webView.set(new WebView(activity, WebViewActivityScenarioHelper.sharedWebContext(activity)));
                webView.get().setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                        loaded.countDown();
                    }
                });
                activity.addContentView(webView.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                                  ViewGroup.LayoutParams.MATCH_PARENT));
                webView.get().requestFocus();
                webView.get().loadHtml("<html><body contenteditable id='editor'></body></html>", null);
            });
            assertTrue("The page did not load", loaded.await(30, TimeUnit.SECONDS));

            AtomicBoolean isTextEditor = new AtomicBoolean(false);
            for (int i = 0; i < 60 && !isTextEditor.get(); i++) {
                scenario.onActivity(activity -> {
                    webView.get().evaluateJavascript("document.getElementById('editor').focus()", null);
                    isTextEditor.set(webView.get().onCheckIsTextEditor());
                });
                if (!isTextEditor.get())
                    Thread.sleep(250);
            }
            assertTrue("The WebView did not become a text editor", isTextEditor.get());

            String marker = "testInputMethodOfADestroyedWebView " + System.nanoTime();
            Log.i("WebViewImeTest", marker);
            AtomicReference<InputConnection> connection = new AtomicReference<>();
            AtomicBoolean textEditorAfterDestroy = new AtomicBoolean(true);
            AtomicReference<InputConnection> connectionAfterDestroy = new AtomicReference<>();
            scenario.onActivity(activity -> {
                WebView view = webView.get();
                connection.set(view.onCreateInputConnection(new EditorInfo()));
                view.destroy();
                if (connection.get() != null) {
                    connection.get().commitText("text", 1);
                    connection.get().deleteSurroundingText(1, 1);
                }
                view.onImeFieldFocused(true);
                textEditorAfterDestroy.set(view.onCheckIsTextEditor());
                connectionAfterDestroy.set(view.onCreateInputConnection(new EditorInfo()));
            });

            assertNotNull("The focused editable did not yield an InputConnection", connection.get());
            assertFalse("A late focus change made a destroyed WebView a text editor", textEditorAfterDestroy.get());
            assertNull("A destroyed WebView gave out an InputConnection", connectionAfterDestroy.get());
            String log = WebViewActivityScenarioHelper.shell("logcat -d -v raw --pid=" + Process.myPid());
            String afterMarker = log.substring(Math.max(0, log.lastIndexOf(marker)));
            assertFalse("Text sent to a destroyed WebView reached native code:\n" + afterMarker,
                        afterMarker.contains("assertion '"));
        } finally {
            scenario.onActivity(activity -> {
                WebView view = webView.get();
                if (view != null) {
                    if (view.getParent() != null)
                        ((ViewGroup)view.getParent()).removeView(view);
                    view.destroy();
                }
            });
        }
    }

    private static final String COMPOSITION_PAGE =
        "<html><body><input id='editor'><input id='other'><script>\n"
        + "window.events = [];\n"
        + "const editor = document.getElementById('editor');\n"
        + "for (const type of ['compositionstart', 'compositionupdate', 'compositionend'])\n"
        + "  editor.addEventListener(type, e => window.events.push(type + ':' + e.data));\n"
        + "</script></body></html>";

    // Focuses the page's #editor and returns the WebView's connection to it.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        return focusEditor(scenario, "editor");
    }

    // Focuses the element with the given id and returns the WebView's connection to it.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario, String id)
        throws Throwable {
        return focusEditor(scenario, id, new EditorInfo());
    }

    // Like focusEditor(scenario, id), and fills editorInfo for the connection.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario, String id,
                                               EditorInfo editorInfo) throws Throwable {
        AtomicBoolean isTextEditor = new AtomicBoolean(false);
        for (int i = 0; i < 60 && !isTextEditor.get(); i++) {
            scenario.onActivity(activity -> {
                activity.getWebView().evaluateJavascript("document.getElementById('" + id + "').focus()", null);
                isTextEditor.set(activity.getWebView().onCheckIsTextEditor());
            });
            if (!isTextEditor.get())
                Thread.sleep(250);
        }
        assertTrue("The WebView did not become a text editor", isTextEditor.get());
        AtomicReference<InputConnection> connection = new AtomicReference<>();
        scenario.onActivity(activity -> connection.set(activity.getWebView().onCreateInputConnection(editorInfo)));
        assertNotNull("The focused editable did not yield an InputConnection", connection.get());
        return connection.get();
    }

    /**
     * Composing text reaches the page as a composition, a commit while composing replaces the composing text, and
     * finishing the composition keeps its text.
     */
    @Test
    public void testComposingTextIsAComposition() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), COMPOSITION_PAGE);
        scenario.moveToState(Lifecycle.State.RESUMED);
        InputConnection connection = focusEditor(scenario);

        scenario.onActivity(activity -> {
            connection.setComposingText("h", 1);
            connection.setComposingText("he", 1);
            connection.setComposingText("hel", 1);
        });
        String composing = "document.getElementById('editor').value === 'hel' && window.events.join() === "
                           + "'compositionstart:,compositionupdate:h,compositionupdate:he,compositionupdate:hel'";
        assertComposition(scenario, "The composing text is not a composition", composing);

        scenario.onActivity(activity -> {
            connection.commitText("hello ", 1);
            connection.setComposingText("wor", 1);
            connection.finishComposingText();
        });
        String finished = "document.getElementById('editor').value === 'hello wor' && window.events.join().endsWith("
                          + "'compositionend:hello ,compositionstart:,compositionupdate:wor,compositionend:wor')";
        assertComposition(scenario, "Committing or finishing the composition went wrong", finished);

        scenario.onActivity(activity -> {
            connection.setComposingText("ld", 1);
            connection.commitText("", 1);
        });
        String cleared = "document.getElementById('editor').value === 'hello wor' && "
                         + "window.events.join().endsWith('compositionstart:,compositionupdate:ld,compositionend:')";
        assertComposition(scenario, "Committing no text did not remove the composing text", cleared);
    }

    /**
     * Composing text that WebKit ignores because no field is focused (an input method that still holds the connection
     * of a blurred field) is not committed into the field focused next when that connection finishes composing.
     */
    @Test
    public void testComposingWithoutAFocusedFieldIsForgotten() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), COMPOSITION_PAGE);
        scenario.moveToState(Lifecycle.State.RESUMED);
        InputConnection blurred = focusEditor(scenario);

        AtomicBoolean isTextEditor = new AtomicBoolean(true);
        for (int i = 0; i < 60 && isTextEditor.get(); i++) {
            scenario.onActivity(activity -> {
                activity.getWebView().evaluateJavascript("document.getElementById('editor').blur()", null);
                isTextEditor.set(activity.getWebView().onCheckIsTextEditor());
            });
            if (isTextEditor.get())
                Thread.sleep(250);
        }
        assertFalse("The WebView is still a text editor after the editable was blurred", isTextEditor.get());

        scenario.onActivity(activity -> blurred.setComposingText("stale", 1));
        InputConnection other = focusEditor(scenario, "other");
        scenario.onActivity(activity -> {
            blurred.finishComposingText();
            // Commits go through the same queue, so this lands after anything the line above commits.
            other.commitText("new", 1);
        });
        assertComposition(scenario, "Composing text from the blurred field reached the next one",
                          "document.getElementById('other').value === 'new' && "
                              + "document.getElementById('editor').value === ''");
    }

    /**
     * When a tap ends the composition, WebKit keeps its text and the WebView restarts the input method, which has the
     * old connection finish its composition right away. That must not commit the text a second time.
     */
    @Test
    public void testCompositionEndedByATapIsNotCommittedAgain() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        // Each field fills half of the view, so a tap at three quarters of its height lands on #other.
        WebViewActivityScenarioHelper.loadHtmlSync(
            scenario, new TestWebViewClient(),
            COMPOSITION_PAGE.replace("<html>", "<html><style>html, body { margin: 0 } input { display: block; "
                                                   + "box-sizing: border-box; width: 100%; height: 50vh }</style>"));
        scenario.moveToState(Lifecycle.State.RESUMED);
        InputConnection connection = focusEditor(scenario);

        // Stands in for Android closing the old connection when the input method restarts, since no input method
        // connects to this window.
        CountDownLatch reset = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            view.getInternalWebKitWebView().getInputMethodContext().setListener(new WPEInputMethodContext.Listener() {
                @Override
                public void onFocusIn() {
                    view.onImeFieldFocused(true);
                }
                @Override
                public void onFocusOut() {
                    view.onImeFieldFocused(false);
                }
                @Override
                public void onPreeditReset() {
                    reset.countDown();
                    connection.finishComposingText();
                    view.onImePreeditReset();
                }
            });
            connection.setComposingText("wor", 1);
        });
        assertComposition(scenario, "The composing text is not a composition",
                          "document.getElementById('editor').value === 'wor'");

        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            float x = view.getWidth() / 2f;
            float y = view.getHeight() * 3 / 4f;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        });
        assertTrue("The tap did not end the composition", reset.await(10, TimeUnit.SECONDS));

        InputConnection other = focusEditor(scenario, "other");
        // Commits go through the same queue, so this lands after anything finishing the composition commits.
        scenario.onActivity(activity -> other.commitText("x", 1));
        assertComposition(scenario, "The composition ended by the tap was committed again",
                          "document.getElementById('other').value === 'x' && "
                              + "document.getElementById('editor').value === 'wor'");
    }

    /** Text with an unpaired surrogate (half an emoji) still reaches the page, with U+FFFD for the bad unit. */
    @Test
    public void testUnpairedSurrogateBecomesAReplacementCharacter() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), COMPOSITION_PAGE);
        scenario.moveToState(Lifecycle.State.RESUMED);
        InputConnection connection = focusEditor(scenario);

        scenario.onActivity(activity -> connection.setComposingText("ab\uD83D", 1));
        assertComposition(scenario, "Composing text with an unpaired surrogate was dropped",
                          "document.getElementById('editor').value === 'ab\\uFFFD'");
        scenario.onActivity(activity -> connection.commitText("cd\uDE00", 1));
        assertComposition(scenario, "Committed text with an unpaired surrogate was dropped",
                          "document.getElementById('editor').value === 'cd\\uFFFD'");
    }

    // Waits for the condition to hold in the page and reports the editor's value and events if it never does.
    private static void assertComposition(ActivityScenario<WebViewTestActivity> scenario, String message,
                                          String condition) throws Throwable {
        if ("true".equals(WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, condition, "true")))
            return;
        TestWebViewClient client = new TestWebViewClient();
        scenario.onActivity(activity -> activity.getWebView().setWebViewClient(client));
        fail(message + ": " +
             WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                 scenario, client,
                 "JSON.stringify([document.getElementById('editor').value, document.getElementById('other').value, "
                     + "window.events])"));
    }
}
