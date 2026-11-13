/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpeview;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.evaluate;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadHtml;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitForResult;

/**
 * The JavaScript dialogs of a page. The app's chrome client goes first; otherwise the view that shows the page shows
 * its built-in dialog while it is on screen. A page that is not shown gets an answer at once, and keeps the message of
 * an alert for the next view. A built-in dialog closes when the page stops being shown, except a beforeunload
 * confirmation, which the next view asks again.
 */
@RunWith(AndroidJUnit4.class)
public class WebPageDialogTest {
    private static final String EMPTY_PAGE = "<!doctype html><title>Dialog Test</title><p>Testcase.</p>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private WebContext context;
    private final List<WebPage> pages = new ArrayList<>();
    private final List<WebView> views = new ArrayList<>();

    @Before
    public void setUp() {
        activityRule.getScenario().moveToState(Lifecycle.State.RESUMED);
        activityRule.getScenario().onActivity(activity -> context = new WebContext(activity, false, true));
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (WebView view : views) {
                if (view.getParent() != null)
                    ((ViewGroup)view.getParent()).removeView(view);
                view.destroy();
            }
            views.clear();
            for (WebPage page : pages)
                page.destroy();
            pages.clear();
            context.destroy();
        });
    }

    private WebPage loadedPage(WebPageTestHelper.RecordingClient client) throws InterruptedException {
        WebPage page = newPage(context, client);
        pages.add(page);
        loadHtml(page, client, EMPTY_PAGE);
        return page;
    }

    private static WebViewTestActivity activityOf(ActivityScenario<WebViewTestActivity> scenario) {
        WebViewTestActivity[] activity = new WebViewTestActivity[1];
        scenario.onActivity(current -> activity[0] = current);
        return activity[0];
    }

    // A view made with the activity, which can show the built-in dialogs, over the WebView of the activity.
    private WebView viewWithActivity() {
        WebViewTestActivity activity = activityOf(activityRule.getScenario());
        return onMainGet(() -> {
            WebView view = new WebView(activity, context);
            views.add(view);
            activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                     ViewGroup.LayoutParams.MATCH_PARENT));
            return view;
        });
    }

    // Shows the page in the view, and waits until it is on screen.
    private static void show(WebView view, WebPage page) throws InterruptedException {
        onMain(() -> view.setPage(page));
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
    }

    private boolean activityHasWindowFocus() {
        WebViewTestActivity activity = activityOf(activityRule.getScenario());
        return onMainGet(activity::hasWindowFocus);
    }

    // The activity loses the focus of its window to a dialog, and gets it back when the dialog closes.
    private void waitForActivityWindowFocus(boolean expected) throws InterruptedException {
        for (int i = 0; i < 100 && activityHasWindowFocus() != expected; i++)
            Thread.sleep(100);
        assertEquals(expected ? "A dialog is still open" : "No dialog opened", expected, activityHasWindowFocus());
    }

    // Starts a script that may wait for a dialog, and returns where its result goes.
    private static BlockingQueue<String> start(WebPage page, String script) {
        BlockingQueue<String> result = new LinkedBlockingQueue<>();
        onMain(() -> page.evaluateJavascript(script, result::add));
        return result;
    }

    private static String resultOf(BlockingQueue<String> result) throws InterruptedException {
        String value = result.poll(WebPageTestHelper.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The script did not end", value);
        return value;
    }

    // A tap in the middle of the view, which gives the page the user activation that beforeunload needs.
    private static void tap(WebView view) {
        int[] location = new int[2];
        int[] size = new int[2];
        onMain(() -> {
            view.getLocationOnScreen(location);
            size[0] = view.getWidth();
            size[1] = view.getHeight();
        });
        float x = location[0] + size[0] / 2f;
        float y = location[1] + size[1] / 2f;
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0);
        InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
        InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
        down.recycle();
        up.recycle();
    }

    /**
     * A page that no view shows gets an answer at once: an alert is answered, and its message shows when a view shows
     * the page, until the user closes it; confirm() and prompt() are cancelled.
     */
    @Test
    public void testDialogsOfAPageWithoutView() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        assertEquals("\"after\"", evaluate(page, "alert('Hello'); 'after'"));
        assertEquals("false", evaluate(page, "confirm('Question')"));
        assertEquals("null", evaluate(page, "prompt('Question', 'answer')"));

        WebView view = viewWithActivity();
        show(view, page);
        waitForActivityWindowFocus(false);
        // Leaving closes the message, which the page keeps for the next view.
        onMain(() -> view.setPage(null));
        waitForActivityWindowFocus(true);
        show(view, page);
        waitForActivityWindowFocus(false);
        // The user closes it, and it does not come back.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForActivityWindowFocus(true);
        onMain(() -> view.setPage(null));
        show(view, page);
        Thread.sleep(500);
        assertTrue("The message of the alert came back", activityHasWindowFocus());
    }

    /** The app's chrome client takes the dialogs of a page with no view, and only its first answer counts. */
    @Test
    public void testChromeClientTakesTheDialogsOfAPageWithoutView() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        onMain(() -> page.setChromeClient(new WebPageChromeClient() {
            @Override
            public boolean onJsConfirm(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                       @NonNull WPEJsResult result) {
                result.confirm();
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                      @NonNull String defaultValue, @NonNull WPEJsPromptResult result) {
                result.confirm("from the app");
                return true;
            }
        }));
        assertEquals("true", evaluate(page, "confirm('Question')"));
        assertEquals("\"from the app\"", evaluate(page, "prompt('Question', 'answer')"));
    }

    /**
     * A built-in dialog closes when its page leaves the view, with the answer of a page that is not shown: confirm()
     * and prompt() are cancelled, and an alert is answered, and its message shows again in the next view.
     */
    @Test
    public void testBuiltInDialogClosesWhenThePageLeaves() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        WebView view = viewWithActivity();
        String[][] cases = {{"confirm('Question')", "false"},
                            {"prompt('Question', 'answer')", "null"},
                            {"alert('Hello'); 'answered'", "\"answered\""}};
        for (String[] dialog : cases) {
            show(view, page);
            BlockingQueue<String> result = start(page, dialog[0]);
            waitForActivityWindowFocus(false);
            assertNull(dialog[0] + " was answered before its page left", result.poll());
            onMain(() -> view.setPage(null));
            waitForActivityWindowFocus(true);
            assertEquals(dialog[0], dialog[1], resultOf(result));
        }
        onMain(() -> view.setPage(page));
        waitForActivityWindowFocus(false);
    }

    // Shows the page, which the user taps, as WebKit asks beforeunload only of pages that the user interacted with, and
    // gives it a beforeunload handler that asks.
    private void showPageThatAsksBeforeUnload(WebView view, WebPage page) throws Exception {
        show(view, page);
        tap(view);
        evaluate(page, "window.onbeforeunload = event => { event.preventDefault(); event.returnValue = ''; }");
    }

    /**
     * A beforeunload confirmation that is open when its page leaves the view is cancelled, so that the page stays as
     * it was, with what the user typed, and the next view does not ask again.
     */
    @Test
    public void testBeforeUnloadClosesWhenThePageLeaves() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        WebView view = viewWithActivity();
        showPageThatAsksBeforeUnload(view, page);
        evaluate(page, "window.typed = 'kept'");
        client.finishedUrls.clear();
        start(page, "location.reload()");
        waitForActivityWindowFocus(false);

        onMain(() -> view.setPage(null));
        waitForActivityWindowFocus(true);
        assertEquals("\"kept\"", evaluate(page, "window.typed"));
        assertNull("The page reloaded", client.finishedUrls.poll());

        WebView other = viewWithActivity();
        show(other, page);
        Thread.sleep(500);
        assertTrue("The next view asked again", activityHasWindowFocus());
    }

    /**
     * A beforeunload confirmation of a page that is not shown is cancelled, unless the chrome client of the page lets
     * the navigation go on.
     */
    @Test
    public void testBeforeUnloadOfAPageNotShown() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        WebView view = viewWithActivity();
        showPageThatAsksBeforeUnload(view, page);
        evaluate(page, "window.typed = 'kept'");
        onMain(() -> view.setPage(null));

        client.finishedUrls.clear();
        onMain(page::reload);
        Thread.sleep(1000);
        assertNull("The page reloaded", client.finishedUrls.poll());
        assertEquals("\"kept\"", evaluate(page, "window.typed"));

        onMain(() -> page.setChromeClient(new WebPageChromeClient() {
            @Override
            public boolean onJsBeforeUnload(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                            @NonNull WPEJsResult result) {
                result.confirm();
                return true;
            }
        }));
        onMain(page::reload);
        client.waitForFinishedLoad();
        assertEquals("\"undefined\"", evaluate(page, "typeof window.typed"));
    }

    /**
     * A WebView made with one of its constructors, as apps did before pages could leave their views: when its activity
     * stops, its built-in confirm() is cancelled, an alert() of the stopped activity is answered at once, and its
     * message shows when the activity starts again.
     */
    @Test
    public void testDialogsOfAWebViewWhoseActivityStops() throws Exception {
        WebView view = viewWithActivity();
        WebPage page = onMainGet(view::getPage);
        onMain(() -> view.loadHtml(EMPTY_PAGE, null));
        assertEquals("\"complete\"", waitForResult(page, "document.readyState", "\"complete\""));
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
        BlockingQueue<String> result = start(page, "confirm('Question')");
        waitForActivityWindowFocus(false);

        activityRule.getScenario().moveToState(Lifecycle.State.CREATED);
        assertEquals("false", resultOf(result));
        assertEquals("\"after\"", evaluate(page, "alert('While stopped'); 'after'"));

        activityRule.getScenario().moveToState(Lifecycle.State.RESUMED);
        waitForActivityWindowFocus(false);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForActivityWindowFocus(true);
    }

    /** A built-in dialog closes when Android rebuilds the activity, and the page that asked goes on. */
    @Test
    public void testBuiltInDialogClosesWhenTheActivityIsRebuilt() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        WebView view = viewWithActivity();
        show(view, page);
        BlockingQueue<String> result = start(page, "confirm('Question')");
        waitForActivityWindowFocus(false);

        activityRule.getScenario().recreate();
        assertEquals("false", resultOf(result));
        assertEquals("2", evaluate(page, "1 + 1"));
    }

    /** Destroying the page cancels a dialog that the app holds, and a later answer does nothing. */
    @Test
    public void testAnswerAfterThePageIsDestroyed() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client);
        AtomicReference<WPEJsResult> held = new AtomicReference<>();
        onMain(() -> page.setChromeClient(new WebPageChromeClient() {
            @Override
            public boolean onJsConfirm(@NonNull WebPage source, @Nullable String url, @Nullable String message,
                                       @NonNull WPEJsResult result) {
                held.set(result);
                return true;
            }
        }));
        start(page, "confirm('Question')");
        for (int i = 0; i < 100 && held.get() == null; i++)
            Thread.sleep(100);
        assertNotNull(held.get());
        onMain(() -> {
            page.destroy();
            held.get().confirm();
            held.get().cancel();
        });
    }
}
