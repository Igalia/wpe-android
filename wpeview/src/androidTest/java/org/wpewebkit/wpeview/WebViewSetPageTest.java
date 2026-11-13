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

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.evaluate;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadHtml;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitFor;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitForResult;

/**
 * One view that shows several pages in turn with {@link WebView#setPage}, as the tabs of a browser, and pages that
 * outlive their activity: each page shows on screen when it is shown, keeps its state while it is not, and leaves no
 * layers or files behind; a page outlives the activity that showed it, which can then be collected, and moves to the
 * view of another activity.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewSetPageTest {
    private static final String PAGE_LAYER = "WPEWebLayer";
    private static final long TIMEOUT_MS = 10000;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private WebContext context;
    private final List<WebPage> pages = new ArrayList<>();

    @Before
    public void setUp() {
        activityRule.getScenario().moveToState(Lifecycle.State.RESUMED);
        activityRule.getScenario().onActivity(activity -> context = new WebContext(activity, false, true));
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (WebPage page : pages)
                page.destroy();
            pages.clear();
            context.destroy();
        });
    }

    private static WebViewTestActivity activityOf(ActivityScenario<WebViewTestActivity> scenario) {
        WebViewTestActivity[] activity = new WebViewTestActivity[1];
        scenario.onActivity(current -> activity[0] = current);
        return activity[0];
    }

    private static String pageOfColor(int color) {
        return String.format("<!doctype html><html style='background:#%06x'><body></body></html>", color & 0xffffff);
    }

    private WebPage loadedPage(String html) throws InterruptedException {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        loadHtml(page, client, html);
        return page;
    }

    private static int colorOnScreen(WebView view) {
        int[] location = new int[2];
        int[] size = new int[2];
        onMain(() -> {
            view.getLocationOnScreen(location);
            size[0] = view.getWidth();
            size[1] = view.getHeight();
        });
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        // The capture can fail on a busy device, and the callers try again.
        if (screenshot == null)
            return Color.TRANSPARENT;
        if (screenshot.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = screenshot.copy(Bitmap.Config.ARGB_8888, false);
            screenshot.recycle();
            screenshot = copy;
        }
        try {
            return screenshot.getPixel(location[0] + size[0] / 2, location[1] + size[1] / 2);
        } finally {
            screenshot.recycle();
        }
    }

    private static boolean sameColor(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) <= 8 && Math.abs(Color.green(a) - Color.green(b)) <= 8 &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= 8;
    }

    private static String colorName(int color) { return String.format("#%06x", color & 0xffffff); }

    private static void assertColorOnScreen(String message, WebView view, int expected) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        int actual;
        do {
            actual = colorOnScreen(view);
            if (sameColor(actual, expected))
                return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(message, colorName(expected), colorName(actual));
    }

    // The layers of pages, as SurfaceFlinger lists them.
    private static int pageLayers() throws IOException {
        Pattern layer = Pattern.compile("\\b" + PAGE_LAYER + "#\\d+(\\s|\\}|$)");
        int layers = 0;
        for (String line : WebViewActivityScenarioHelper.shell("dumpsys SurfaceFlinger --list").split("\n")) {
            if (layer.matcher(line).find())
                layers++;
        }
        return layers;
    }

    private static int openFiles() {
        String[] files = new File("/proc/self/fd").list();
        return files != null ? files.length : 0;
    }

    /**
     * One view shows page A, then B, then A again: each shows on screen and is visible while shown, hidden while not,
     * and page A keeps its scripts, form contents and scroll position meanwhile.
     */
    @Test
    public void testPagesTakeTurnsInOneView() throws Exception {
        WebPage first = loadedPage("<!doctype html><html style='background:#ff0000'><body>"
                                   + "<input id='field'><div style='height:5000px'></div></body></html>");
        WebPage second = loadedPage(pageOfColor(Color.GREEN));
        WebView view = activityOf(activityRule.getScenario()).getWebView();

        onMain(() -> view.setPage(first));
        assertColorOnScreen("Page A is not on screen", view, Color.RED);
        assertEquals("\"visible\"", waitForResult(first, "document.visibilityState", "\"visible\""));
        evaluate(first, "window.kept = 42; document.getElementById('field').value = 'typed'; scrollTo(0, 1234)");
        assertEquals("1234", waitForResult(first, "scrollY", "1234"));

        onMain(() -> view.setPage(second));
        assertColorOnScreen("Page B is not on screen", view, Color.GREEN);
        assertEquals("\"hidden\"", waitForResult(first, "document.visibilityState", "\"hidden\""));
        assertEquals("\"visible\"", waitForResult(second, "document.visibilityState", "\"visible\""));

        onMain(() -> view.setPage(first));
        assertColorOnScreen("Page A is not on screen again", view, Color.RED);
        assertEquals("\"hidden\"", waitForResult(second, "document.visibilityState", "\"hidden\""));
        assertEquals("42", evaluate(first, "window.kept"));
        assertEquals("\"typed\"", evaluate(first, "document.getElementById('field').value"));
        assertEquals("1234", evaluate(first, "scrollY"));
    }

    /** Pages that take turns in a view many times leave no layers and no open files behind. */
    @Test
    public void testSwitchingLeavesNothingBehind() throws Exception {
        WebPage first = loadedPage(pageOfColor(Color.RED));
        WebPage second = loadedPage(pageOfColor(Color.GREEN));
        WebView view = activityOf(activityRule.getScenario()).getWebView();
        onMain(() -> view.setPage(first));
        assertColorOnScreen("Page A is not on screen", view, Color.RED);
        onMain(() -> view.setPage(second));
        assertColorOnScreen("Page B is not on screen", view, Color.GREEN);
        int layers = pageLayers();
        int files = openFiles();

        for (int i = 0; i < 20; i++) {
            WebPage shown = i % 2 == 0 ? first : second;
            onMain(() -> view.setPage(shown));
            assertColorOnScreen("The page is not on screen after " + (i + 1) + " switches", view,
                                i % 2 == 0 ? Color.RED : Color.GREEN);
        }
        // SurfaceFlinger destroys a layer a moment after its last user lets it go.
        waitFor("Layers are left behind", () -> {
            try {
                return pageLayers() <= layers;
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        int newFiles = openFiles() - files;
        assertTrue(newFiles + " more files are open after 20 switches", newFiles < 10);
    }

    /**
     * A page outlives the activity that showed it, which Android rebuilds: the new activity shows it with its history,
     * form contents, scroll position and scripts, and the old activity can be collected. The view of the old activity
     * is made with it, and the activity does not destroy it, so that only the view letting the page go frees the
     * activity.
     */
    @Test
    public void testPageOutlivesItsActivity() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        HttpServer server = HttpServer.start();
        try {
            WebPageTestHelper.loadUrl(page, client, WebPageTestHelper.servePage(server, "/first", "<p>First</p>"));
            WebPageTestHelper.loadUrl(
                page, client,
                WebPageTestHelper.servePage(server, "/second",
                                            "<body><input id='field'><div style='height:5000px'></div></body>"));
        } finally {
            server.shutdown();
        }
        assertTrue(onMainGet(page::canGoBack));
        ActivityScenario<WebViewTestActivity> scenario = activityRule.getScenario();
        // The test keeps no reference to the old activity or its views, only a weak one.
        scenario.onActivity(activity -> {
            WebView view = WebView.createEmpty(activity);
            activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                     ViewGroup.LayoutParams.MATCH_PARENT));
            view.setPage(page);
        });
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
        evaluate(page, "window.kept = 42; document.getElementById('field').value = 'typed'; scrollTo(0, 1234)");
        assertEquals("1234", waitForResult(page, "scrollY", "1234"));

        WeakReference<Activity> oldActivity = new WeakReference<>(activityOf(scenario));
        scenario.recreate();
        // The view destroyed itself with its activity, which let the page go.
        assertNull(onMainGet(page::getView));
        scenario.onActivity(activity -> activity.getWebView().setPage(page));
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
        assertEquals("42", evaluate(page, "window.kept"));
        assertEquals("\"typed\"", evaluate(page, "document.getElementById('field').value"));
        assertEquals("1234", evaluate(page, "scrollY"));
        assertTrue(onMainGet(page::canGoBack));

        // Nothing in the page holds the old activity.
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (oldActivity.get() != null && SystemClock.uptimeMillis() < deadline) {
            Runtime.getRuntime().gc();
            System.runFinalization();
            byte[][] garbage = new byte[64][];
            for (int i = 0; i < garbage.length; i++)
                garbage[i] = new byte[64 * 1024];
            SystemClock.sleep(100);
        }
        assertNull("The old activity was not collected", oldActivity.get());
    }

    /**
     * An activity that ends without destroying the view that shows a page of the app: the view lets the page go, so
     * that the new activity can show it.
     */
    @Test
    public void testViewLetsThePageGoWhenItsActivityEnds() throws Exception {
        WebPage page = loadedPage(pageOfColor(Color.RED));
        ActivityScenario<WebViewTestActivity> scenario = activityRule.getScenario();
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity, context);
            activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                     ViewGroup.LayoutParams.MATCH_PARENT));
            view.setPage(page);
        });
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));

        scenario.recreate();
        assertNull(onMainGet(page::getView));
        scenario.onActivity(activity -> activity.getWebView().setPage(page));
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
    }

    /**
     * A view that left the window before its activity ended, and was never destroyed, still holds the page of the app:
     * the view of the new activity takes the page from it.
     */
    @Test
    public void testPageIsTakenFromTheViewOfAnEndedActivity() throws Exception {
        WebPage page = loadedPage(pageOfColor(Color.RED));
        ActivityScenario<WebViewTestActivity> scenario = activityRule.getScenario();
        scenario.onActivity(activity -> {
            WebView view = new WebView(activity, context);
            activity.addContentView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                     ViewGroup.LayoutParams.MATCH_PARENT));
            view.setPage(page);
            ((ViewGroup)view.getParent()).removeView(view);
        });
        scenario.recreate();
        WeakReference<WebView> oldView = new WeakReference<>(onMainGet(page::getView));
        assertTrue("The page left the view that left the window", oldView.get() != null);

        scenario.onActivity(activity -> activity.getWebView().setPage(page));
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
        WebView taken = oldView.get();
        if (taken != null)
            assertNull("The view of the ended activity was not destroyed", onMainGet(taken::getPage));
    }

    /**
     * A page moves from the view of one activity to the view of another, which starts while the first is still there,
     * and goes on once that one ends too.
     */
    @Test
    public void testPageMovesBetweenActivities() throws Exception {
        WebPage page = loadedPage(pageOfColor(Color.RED));
        WebView firstView = activityOf(activityRule.getScenario()).getWebView();
        onMain(() -> firstView.setPage(page));
        assertColorOnScreen("The page is not on screen", firstView, Color.RED);

        // The first activity starts the second one, and stays under it.
        WebViewTestActivity first = activityOf(activityRule.getScenario());
        onMain(() -> first.startActivity(new Intent(first, WebViewTestActivity.class)));
        AtomicReference<WebViewTestActivity> second = new AtomicReference<>();
        waitFor("The second activity did not start", () -> {
            for (Activity activity :
                 ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (activity != first && activity instanceof WebViewTestActivity)
                    second.set((WebViewTestActivity)activity);
            }
            return second.get() != null;
        });
        WebView secondView = second.get().getWebView();
        onMain(() -> {
            firstView.setPage(null);
            secondView.setPage(page);
        });
        assertSame(secondView, onMainGet(page::getView));
        assertColorOnScreen("The page is not on screen in the second activity", secondView, Color.RED);
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));

        // The second activity ends, and destroys its WebView, which lets the page go.
        onMain(() -> second.get().finish());
        waitFor("The second activity did not end", () -> page.getView() == null);
        onMain(() -> firstView.setPage(page));
        assertColorOnScreen("The page is not on screen back in the first activity", firstView, Color.RED);
        assertEquals("\"visible\"", waitForResult(page, "document.visibilityState", "\"visible\""));
    }
}
