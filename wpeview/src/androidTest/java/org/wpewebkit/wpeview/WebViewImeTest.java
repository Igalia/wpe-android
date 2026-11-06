package org.wpewebkit.wpeview;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.WPEInputMethodContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

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

    // Focuses the page's #editor and returns the WebView's connection to it.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        return focusEditor(scenario, "editor");
    }

    // Focuses the element with the given id until the condition holds for the WebView, and tells whether it did.
    private static boolean focusUntil(ActivityScenario<WebViewTestActivity> scenario, String id,
                                      Predicate<WebView> condition) throws Throwable {
        AtomicBoolean holds = new AtomicBoolean(false);
        for (int i = 0; i < 60 && !holds.get(); i++) {
            scenario.onActivity(activity -> {
                activity.getWebView().evaluateJavascript("document.getElementById('" + id + "').focus()", null);
                holds.set(condition.test(activity.getWebView()));
            });
            if (!holds.get())
                Thread.sleep(250);
        }
        return holds.get();
    }

    // Focuses the element with the given id and returns the WebView's connection to it.
    private static InputConnection focusEditor(ActivityScenario<WebViewTestActivity> scenario, String id)
        throws Throwable {
        assertTrue("The WebView did not become a text editor", focusUntil(scenario, id, WebView::onCheckIsTextEditor));
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
            tap(view, view.getWidth() / 2f, view.getHeight() * 3 / 4f);
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

    private static void tap(WebView view, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static final String PURPOSE_PAGE =
        "<html><body><input id='text'><input id='password' type='password'><input id='email' type='email'>"
        + "<input id='tel' type='tel'><input id='url' type='url'><input id='number' type='number'>"
        + "<input id='numeric' inputmode='numeric'><input id='revealed' type='password'>"
        + "<input id='revealed-pin' type='password' inputmode='numeric'><script>"
        + "document.getElementById('revealed').type = document.getElementById('revealed-pin').type = 'text'"
        + "</script></body></html>";

    private static int inputTypeOf(WebView view) {
        EditorInfo editorInfo = new EditorInfo();
        return view.onCreateInputConnection(editorInfo) != null ? editorInfo.inputType : 0;
    }

    private static void assertInputType(ActivityScenario<WebViewTestActivity> scenario, String id, int expected)
        throws Throwable {
        if (focusUntil(scenario, id, view -> inputTypeOf(view) == expected))
            return;
        AtomicInteger inputType = new AtomicInteger();
        scenario.onActivity(activity -> inputType.set(inputTypeOf(activity.getWebView())));
        assertEquals("Wrong input type for #" + id, Integer.toHexString(expected),
                     Integer.toHexString(inputType.get()));
    }

    private static boolean inputMethodMayLearn(ActivityScenario<WebViewTestActivity> scenario) throws Throwable {
        AtomicBoolean mayLearn = new AtomicBoolean();
        scenario.onActivity(activity -> {
            EditorInfo editorInfo = new EditorInfo();
            activity.getWebView().onCreateInputConnection(editorInfo);
            mayLearn.set((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0);
        });
        return mayLearn.get();
    }

    /**
     * Each kind of field gets the keyboard for it: password fields a password keyboard, and so on. The input method
     * may not learn what is typed in a password field, also when it shows its text.
     */
    @Test
    public void testFieldsGetTheirInputType() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().requestFocus());
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), PURPOSE_PAGE);
        scenario.moveToState(Lifecycle.State.RESUMED);

        assertInputType(scenario, "text", EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);
        assertTrue("The input method may not learn from #text", inputMethodMayLearn(scenario));
        assertInputType(scenario, "password", EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        assertFalse("The input method may learn from #password", inputMethodMayLearn(scenario));
        assertInputType(scenario, "email",
                        EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        assertInputType(scenario, "tel", EditorInfo.TYPE_CLASS_PHONE);
        assertInputType(scenario, "url", EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI);
        assertInputType(scenario, "number",
                        EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_FLAG_SIGNED |
                            EditorInfo.TYPE_NUMBER_FLAG_DECIMAL);
        assertInputType(scenario, "numeric", EditorInfo.TYPE_CLASS_NUMBER);
        assertInputType(scenario, "revealed",
                        EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        assertFalse("The input method may learn from #revealed", inputMethodMayLearn(scenario));
        assertInputType(scenario, "revealed-pin",
                        EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD);
        assertFalse("The input method may learn from #revealed-pin", inputMethodMayLearn(scenario));
    }

    // Records the input type of each connection that Android asks the WebView for, as the input method gets it.
    private static class InputTypeRecordingWebView extends WebView {
        final List<Integer> inputTypes = new ArrayList<>();

        InputTypeRecordingWebView(@NonNull Context context, @NonNull WebContext webContext) {
            super(context, webContext);
        }

        @Override
        public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
            InputConnection connection = super.onCreateInputConnection(outAttrs);
            if (connection != null)
                inputTypes.add(outAttrs.inputType);
            return connection;
        }
    }

    // Waits for Android to ask the WebView for a connection with the input type, after the first `from` ones.
    private static boolean waitForRestart(ActivityScenario<WebViewTestActivity> scenario,
                                          AtomicReference<InputTypeRecordingWebView> webView, int from, int inputType,
                                          @Nullable String focusId) throws Throwable {
        AtomicBoolean restarted = new AtomicBoolean(false);
        for (int i = 0; i < 40 && !restarted.get(); i++) {
            scenario.onActivity(activity -> {
                List<Integer> inputTypes = webView.get().inputTypes;
                restarted.set(inputTypes.size() > from &&
                              inputTypes.subList(from, inputTypes.size()).contains(inputType));
                if (!restarted.get() && focusId != null)
                    webView.get().evaluateJavascript("document.getElementById('" + focusId + "').focus()", null);
            });
            if (!restarted.get())
                Thread.sleep(250);
        }
        return restarted.get();
    }

    // Shows the page in a new InputTypeRecordingWebView that has the focus, and waits for it to load.
    private static void loadInRecordingWebView(ActivityScenario<WebViewTestActivity> scenario,
                                               AtomicReference<InputTypeRecordingWebView> webView, String html)
        throws Throwable {
        CountDownLatch loaded = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            webView.set(
                new InputTypeRecordingWebView(activity, WebViewActivityScenarioHelper.sharedWebContext(activity)));
            webView.get().setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                    loaded.countDown();
                }
            });
            activity.addContentView(webView.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                              ViewGroup.LayoutParams.MATCH_PARENT));
            webView.get().requestFocus();
            webView.get().loadHtml(html, null);
        });
        assertTrue("The page did not load", loaded.await(30, TimeUnit.SECONDS));
    }

    private static void destroyRecordingWebView(ActivityScenario<WebViewTestActivity> scenario,
                                                AtomicReference<InputTypeRecordingWebView> webView) {
        scenario.onActivity(activity -> {
            WebView view = webView.get();
            if (view != null) {
                if (view.getParent() != null)
                    ((ViewGroup)view.getParent()).removeView(view);
                view.destroy();
            }
        });
    }

    /**
     * A script turns the focused password field into a text field and back, as a "show password" button that keeps
     * the focus on the field does. WebKit tells the new purpose of the field, and the WebView restarts the input
     * method, which gets the new input type: a visible password one while the field shows its text. Nothing taps the
     * field, and the test never asks for a connection itself: each one it sees comes from Android.
     */
    @Test
    public void testChangingTheTypeOfTheFocusedFieldRestartsTheInputMethod() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        int password = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        int revealed = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        AtomicReference<InputTypeRecordingWebView> webView = new AtomicReference<>();
        try {
            loadInRecordingWebView(scenario, webView,
                                   "<html><body><input id='password' type='password'></body></html>");
            assumeTrue("No input method connected to the WebView",
                       waitForRestart(scenario, webView, 0, password, "password"));

            for (String type : new String[] {"text", "password"}) {
                AtomicInteger from = new AtomicInteger();
                scenario.onActivity(activity -> {
                    InputTypeRecordingWebView view = webView.get();
                    from.set(view.inputTypes.size());
                    view.evaluateJavascript("document.getElementById('password').type = '" + type + "'", null);
                });
                assertTrue(
                    "The input method was not restarted with the input type for type " + type,
                    waitForRestart(scenario, webView, from.get(), type.equals("text") ? revealed : password, null));
            }
        } finally {
            destroyRecordingWebView(scenario, webView);
        }
    }

    /**
     * The focus moves from a text field to a password field, back, and then to a password field shown as text.
     * WebKit tells no focus out or focus in for that, only the new purpose and hints, and the WebView restarts the
     * input method with the input type of each field. The text field and the password shown as text have the same
     * purpose: only the private hint tells them apart.
     */
    @Test
    public void testFocusingAFieldOfAnotherTypeRestartsTheInputMethod() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        int text = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
        int password = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        int revealed = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        Map<String, Integer> inputTypes = Map.of("text", text, "password", password, "revealed", revealed);
        AtomicReference<InputTypeRecordingWebView> webView = new AtomicReference<>();
        try {
            loadInRecordingWebView(scenario, webView,
                                   "<html><body><input id='text'><input id='password' type='password'>"
                                       + "<input id='revealed' type='password'><script>"
                                       + "document.getElementById('revealed').type = 'text'</script></body></html>");
            assumeTrue("No input method connected to the WebView", waitForRestart(scenario, webView, 0, text, "text"));

            for (String id : new String[] {"password", "text", "revealed", "text"}) {
                AtomicInteger from = new AtomicInteger();
                scenario.onActivity(activity -> from.set(webView.get().inputTypes.size()));
                assertTrue("The input method was not restarted with the input type of #" + id,
                           waitForRestart(scenario, webView, from.get(), inputTypes.get(id), id));
            }
        } finally {
            destroyRecordingWebView(scenario, webView);
        }
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
