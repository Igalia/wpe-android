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

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.WPEInputMethodContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class WebViewImeTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    /**
     * Verifies that focusing an editable element in the page makes the WebView an active text
     * editor and produces a valid {@link InputConnection} through the WPE input-method bridge
     * (WebKit input-method-context focus-in -> WPEInputMethodContext.Listener ->
     * WebView.onImeFieldFocused). This is the part of IME handling this widget owns; whether the
     * soft keyboard is actually rendered additionally depends on the window holding focus, which is
     * not guaranteed under ActivityScenario instrumentation.
     */
    @Test
    public void testEditableFocusEngagesInputConnection() throws Throwable {
        CountDownLatch focusScriptDone = new CountDownLatch(1);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                        // hasFocus() is true once the view has focus; otherwise focus the editor as
                        // soon as the document gains focus.
                        String focusScript = "(function() {\n"
                                             +
                                             "  function focusEditor() { document.getElementById('editor').focus(); }\n"
                                             + "  if (document.hasFocus()) { focusEditor(); }\n"
                                             + "  else { window.addEventListener('focus', focusEditor); }\n"
                                             + "})();";
                        view.evaluateJavascript(focusScript, value -> focusScriptDone.countDown());
                    }
                });

                // A real user focuses the view by tapping it; the test focuses the editable via JS,
                // so it must give the WebView focus first for the IME to engage.
                activity.getWebView().requestFocus();

                String htmlDocument = "<html><body contenteditable id='editor'></body></html>";
                activity.getWebView().loadHtml(htmlDocument, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        assertTrue("Focus script never ran", focusScriptDone.await(30, TimeUnit.SECONDS));

        // The editable focus travels WebKit -> WPEInputMethodContext -> WebView asynchronously; poll
        // until the WebView reports itself as a text editor.
        AtomicBoolean isTextEditor = new AtomicBoolean(false);
        for (int i = 0; i < 60 && !isTextEditor.get(); i++) {
            CountDownLatch pollLatch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                isTextEditor.set(activity.getWebView().onCheckIsTextEditor());
                pollLatch.countDown();
            });
            assertTrue(pollLatch.await(5, TimeUnit.SECONDS));
            if (!isTextEditor.get())
                Thread.sleep(250);
        }
        assertTrue("WebView did not become a text editor after the editable was focused", isTextEditor.get());

        // A valid InputConnection with a web-text input type proves the IME bridge is wired up.
        AtomicReference<InputConnection> connection = new AtomicReference<>();
        EditorInfo editorInfo = new EditorInfo();
        CountDownLatch icLatch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            connection.set(activity.getWebView().onCreateInputConnection(editorInfo));
            icLatch.countDown();
        });
        assertTrue(icLatch.await(5, TimeUnit.SECONDS));

        assertNotNull("Focused editable did not yield an InputConnection", connection.get());
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

    // The test drives its own connection. The device's keyboard would get the selection updates too, take them for
    // the user moving the cursor, and edit the field as well: never start it.
    private static void keepKeyboardOut(WebView webView) { webView.inputMethodManager = null; }

    // Focuses the page's #editor and returns the WebView's connection to it.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        return focusEditor(scenario, "editor");
    }

    // Focuses the element with the given id and returns the WebView's connection to it.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario, String id)
        throws Throwable {
        scenario.onActivity(activity -> keepKeyboardOut(activity.getWebView()));
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
        scenario.onActivity(
            activity -> connection.set(activity.getWebView().onCreateInputConnection(new EditorInfo())));
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

        // Stands in for Android closing the old connection when the input method restarts, which an input method
        // under instrumentation may not do.
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

    /**
     * The input method decides how much to delete, and what to suggest, from the text it is told is around the
     * cursor. Without it, a keyboard like LatinIME deletes one Java char before the cursor, which is half of an
     * emoji.
     */
    @Test
    public void testInputConnectionSeesTextAroundCursor() throws Throwable {
        // "a" followed by U+1F600, two Java chars.
        InputConnection connection = focusEditorAndConnect(
            "<html><body><div contenteditable id='editor'></div></body></html>",
            "editor.textContent = 'a\\uD83D\\uDE00'; getSelection().collapse(editor.firstChild, 3);");

        assertTrue(
            "The input method was not told the text before the cursor",
            waitFor(() -> "a\uD83D\uDE00".equals(text(onUiThread(webView -> connection.getTextBeforeCursor(10, 0))))));

        // Delete the last code point, sized from what the input connection reports, as an input method would.
        CharSequence before = onUiThread(webView -> connection.getTextBeforeCursor(10, 0));
        int length = Character.charCount(Character.codePointBefore(before, before.length()));
        assertEquals(2, length);
        onUiThread(webView -> connection.deleteSurroundingText(length, 0));
        assertTrue("The emoji was not deleted", waitFor(() -> evaluate("editor.textContent === 'a'").equals("true")));
    }

    private InputConnection focusEditorAndConnect(@NonNull String html, @NonNull String setupScript) throws Throwable {
        CountDownLatch setupDone = new CountDownLatch(1);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                        String script = "(function() {\n"
                                        + "  function setUp() { editor.focus(); " + setupScript + " }\n"
                                        + "  if (document.hasFocus()) { setUp(); }\n"
                                        + "  else { window.addEventListener('focus', setUp); }\n"
                                        + "})();";
                        view.evaluateJavascript(script, value -> setupDone.countDown());
                    }
                });
                keepKeyboardOut(activity.getWebView());
                activity.getWebView().requestFocus();
                activity.getWebView().loadHtml(html, null);
            })
            .moveToState(Lifecycle.State.RESUMED);
        assertTrue("Setup script never ran", setupDone.await(30, TimeUnit.SECONDS));
        assertTrue("WebView did not become a text editor",
                   waitFor(() -> onUiThread(webView -> webView.onCheckIsTextEditor())));
        InputConnection connection = onUiThread(webView -> webView.onCreateInputConnection(new EditorInfo()));
        assertNotNull("Focused editable did not yield an InputConnection", connection);
        return connection;
    }

    private static String text(CharSequence text) { return text != null ? text.toString() : ""; }

    private interface WebViewTask<T> {
        T run(@NonNull WebView webView) throws Throwable;
    }

    // Runs on the UI thread, where InputConnection calls are made. Don't wait for the WebView in here.
    private <T> T onUiThread(@NonNull WebViewTask<T> task) throws Throwable {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            try {
                result.set(task.run(activity.getWebView()));
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null)
            throw error.get();
        return result.get();
    }

    // Returns the result as JSON. Keep results ASCII: they come back through modified UTF-8.
    private String evaluate(@NonNull String script) throws Throwable {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        webViewActivityTestRule.getScenario().onActivity(
            activity -> activity.getWebView().evaluateJavascript(script, value -> {
                result.set(value);
                done.countDown();
            }));
        assertTrue("Script did not finish: " + script, done.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private interface Condition {
        boolean check() throws Throwable;
    }

    // Runs on the test thread.
    private boolean waitFor(@NonNull Condition condition) throws Throwable {
        for (int i = 0; i < 60; i++) {
            if (condition.check())
                return true;
            Thread.sleep(250);
        }
        return false;
    }
}
