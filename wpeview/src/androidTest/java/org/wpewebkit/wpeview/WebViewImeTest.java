package org.wpewebkit.wpeview;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
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

    // A tap: the finger moves a few pixels and comes back, well within the touch slop.
    private static final float[][] TAP = {{0, 0}, {2, 1}, {3, 3}, {1, 2}, {1, 2}};

    // A drag of 600 pixels upwards, which scrolls the page down.
    private static final float[][] DRAG = {{0, 0},    {0, -100}, {0, -200}, {0, -300},
                                           {0, -400}, {0, -500}, {0, -600}, {0, -600}};

    // The finger goes far and comes back to where it started before it is lifted: not a tap.
    private static final float[][] AWAY_AND_BACK = {{0, 0}, {0, -150}, {0, -300}, {0, -150}, {0, 0}, {0, 0}};

    // Event times go on from the last touch, so that a touch never starts before the one before it ended.
    private long lastEventTime;

    /**
     * A tap on the page brings back the keyboard of the focused field, which Back may have hidden. A scroll does not,
     * nor a touch of two fingers, nor a long press. The page is all one field, tall enough to scroll.
     */
    @Test
    public void testOnlyATapShowsTheKeyboard() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicReference<WebView> webView = new AtomicReference<>();
        AtomicInteger keyboardRequests = new AtomicInteger();
        CountDownLatch loaded = new CountDownLatch(1);
        try {
            scenario.onActivity(activity -> {
                webView.set(new WebView(activity, WebViewActivityScenarioHelper.sharedWebContext(activity)) {
                    @Override
                    void showSoftInput() {
                        keyboardRequests.incrementAndGet();
                        super.showSoftInput();
                    }
                });
                webView.get().setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                        loaded.countDown();
                    }
                });
                activity.addContentView(webView.get(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                                  ViewGroup.LayoutParams.MATCH_PARENT));
                webView.get().requestFocus();
                webView.get().loadHtml("<html><head><meta name=viewport content='width=device-width'><style>"
                                           + "body { margin: 0; height: 5000px }</style></head>"
                                           + "<body contenteditable id='editor'></body></html>",
                                       null);
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

            // Each touch is sent and counted in one go on the main thread, so that only its own requests count.
            AtomicInteger dragRequests = new AtomicInteger();
            scenario.onActivity(activity -> {
                keyboardRequests.set(0);
                moveFinger(webView.get(), DRAG);
                dragRequests.set(keyboardRequests.get());
            });
            assertEquals("The drag did not scroll the page", "true",
                         WebViewActivityScenarioHelper.waitForJavaScriptResult(
                             scenario, activity -> webView.get(), "scrollY > 0", "true"));
            assertEquals("A scroll showed the keyboard", 0, dragRequests.get());

            AtomicInteger twoFingerRequests = new AtomicInteger();
            scenario.onActivity(activity -> {
                keyboardRequests.set(0);
                touchWithTwoFingers(webView.get());
                twoFingerRequests.set(keyboardRequests.get());
            });
            assertEquals("A touch of two fingers showed the keyboard", 0, twoFingerRequests.get());

            AtomicInteger awayAndBackRequests = new AtomicInteger();
            scenario.onActivity(activity -> {
                keyboardRequests.set(0);
                moveFinger(webView.get(), AWAY_AND_BACK);
                awayAndBackRequests.set(keyboardRequests.get());
            });
            assertEquals("A finger that went away and came back showed the keyboard", 0, awayAndBackRequests.get());

            // The finger stays down past the long press timeout, on the clock of the main thread's messages.
            long pressTime = ViewConfiguration.getLongPressTimeout() + 500;
            AtomicLong pressDownTime = new AtomicLong();
            AtomicInteger longPressRequests = new AtomicInteger();
            scenario.onActivity(activity -> {
                keyboardRequests.set(0);
                pressDownTime.set(Math.max(SystemClock.uptimeMillis(), lastEventTime + 50));
                WebView view = webView.get();
                sendTouch(view, pressDownTime.get(), 0, MotionEvent.ACTION_DOWN, new int[] {0},
                          new float[] {view.getWidth() / 2f}, view.getHeight() / 2f);
            });
            Thread.sleep(pressTime + pressDownTime.get() - SystemClock.uptimeMillis());
            scenario.onActivity(activity -> {
                WebView view = webView.get();
                sendTouch(view, pressDownTime.get(), pressTime, MotionEvent.ACTION_UP, new int[] {0},
                          new float[] {view.getWidth() / 2f}, view.getHeight() / 2f);
                longPressRequests.set(keyboardRequests.get());
            });
            assertEquals("A long press showed the keyboard", 0, longPressRequests.get());

            // Two taps in a row, as quick as a double tap, each bring back the keyboard.
            AtomicInteger tapRequests = new AtomicInteger();
            scenario.onActivity(activity -> {
                keyboardRequests.set(0);
                moveFinger(webView.get(), TAP);
                moveFinger(webView.get(), TAP);
                tapRequests.set(keyboardRequests.get());
            });
            assertEquals("Taps did not show the keyboard", 2, tapRequests.get());
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

    /**
     * Moves a finger over the view: down at the first point, through the others, and up at the last one. Each point
     * is an offset in pixels from the middle of the view.
     */
    private void moveFinger(WebView view, float[][] path) {
        long downTime = Math.max(SystemClock.uptimeMillis(), lastEventTime + 50);
        for (int i = 0; i < path.length; i++) {
            int action = i == 0                 ? MotionEvent.ACTION_DOWN
                         : i == path.length - 1 ? MotionEvent.ACTION_UP
                                                : MotionEvent.ACTION_MOVE;
            sendTouch(view, downTime, 16L * i, action, new int[] {0}, new float[] {view.getWidth() / 2f + path[i][0]},
                      view.getHeight() / 2f + path[i][1]);
        }
    }

    // Puts two fingers on the view, as a pinch starts, and lifts them without moving.
    private void touchWithTwoFingers(WebView view) {
        long downTime = Math.max(SystemClock.uptimeMillis(), lastEventTime + 50);
        float y = view.getHeight() / 2f;
        float left = view.getWidth() / 4f;
        float right = view.getWidth() * 3 / 4f;
        int second = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        sendTouch(view, downTime, 0, MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {left}, y);
        sendTouch(view, downTime, 16, MotionEvent.ACTION_POINTER_DOWN | second, new int[] {0, 1},
                  new float[] {left, right}, y);
        sendTouch(view, downTime, 32, MotionEvent.ACTION_POINTER_UP | second, new int[] {0, 1},
                  new float[] {left, right}, y);
        sendTouch(view, downTime, 48, MotionEvent.ACTION_UP, new int[] {0}, new float[] {left}, y);
    }

    private void sendTouch(WebView view, long downTime, long offset, int action, int[] ids, float[] xs, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = y;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        lastEventTime = downTime + offset;
        MotionEvent event = MotionEvent.obtain(downTime, lastEventTime, action, ids.length, properties, coords, 0, 0, 1,
                                               1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
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
