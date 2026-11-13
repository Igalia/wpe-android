package org.wpewebkit.wpeview;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WPEToplevel;
import org.wpewebkit.wpe.WPEView;
import org.wpewebkit.wpe.WebKitWebView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The layers that a toplevel puts in its native window: a root, which shows the background color, and under it a layer
 * for each page, which keeps the last frame of the page. When a page takes the place of another in a toplevel, as when
 * a WebView shows several pages in turn, the layer of the old page stays until the new page shows something, for a
 * short time at most.
 *
 * Most tests here give the surface of a SurfaceView of their own to a toplevel, show in it the pages of WebViews that
 * are in no window, and check what the display shows and the layers that SurfaceFlinger lists.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewToplevelLayersTest {
    private static final int BACKGROUND = Color.BLUE;
    private static final int RED = Color.RED;
    private static final int GREEN = Color.GREEN;
    private static final int YELLOW = Color.YELLOW;
    private static final String ROOT_LAYER = "WPEToplevelRoot";
    private static final String PAGE_LAYER = "WPEWebLayer";
    private static final long TIMEOUT_MS = 10000;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private SurfaceView surfaceView;
    private WPEToplevel toplevel;
    // The WebContext of the pages of a test, made with its first page.
    private WebContext webContext;
    private final List<WebView> pages = new ArrayList<>();

    @After
    public void destroyPagesAndToplevel() {
        activityRule.getScenario().onActivity(activity -> {
            for (WebView page : pages)
                page.destroy();
            pages.clear();
            if (webContext != null) {
                webContext.destroy();
                webContext = null;
            }
            if (toplevel != null) {
                toplevel.destroy();
                toplevel = null;
            }
        });
    }

    // Shows a SurfaceView instead of the WebView of the activity, and gives its surface to a toplevel with the
    // background color.
    private void showToplevel() throws InterruptedException {
        CountDownLatch surfaceCreated = new CountDownLatch(1);
        activityRule.getScenario().onActivity(activity -> {
            toplevel = new WPEToplevel(WPERuntime.getInstance().getDisplay(), null);
            toplevel.setBackgroundColor(BACKGROUND);
            surfaceView = new SurfaceView(activity);
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    if (toplevel != null)
                        toplevel.onSurfaceCreated(holder.getSurface());
                    surfaceCreated.countDown();
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    if (toplevel != null)
                        toplevel.setSurface(holder.getSurface());
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    if (toplevel != null)
                        toplevel.onSurfaceDestroyed();
                }
            });
            FrameLayout layout = new FrameLayout(activity);
            layout.addView(surfaceView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                                                                     FrameLayout.LayoutParams.MATCH_PARENT));
            activity.setContentView(layout);
        });
        assertTrue("The SurfaceView got no surface", surfaceCreated.await(10, TimeUnit.SECONDS));
        assertColorOnScreen("The toplevel does not show its background color", BACKGROUND);
    }

    private static String pageOfColor(int color) {
        return String.format("<!doctype html><html style='background:#%06x'><body></body></html>", color & 0xffffff);
    }

    // A WebView in no window, whose page is in no toplevel until a test puts it in one.
    private WebView loadPage(int color) {
        TestWebViewClient client = new TestWebViewClient();
        activityRule.getScenario().onActivity(activity -> {
            if (webContext == null)
                webContext = new WebContext(activity, false, true);
            WebView page = new WebView(activity, webContext);
            pages.add(page);
            page.setWebViewClient(client);
            page.loadHtml(pageOfColor(color), null);
        });
        client.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
        return pages.get(pages.size() - 1);
    }

    private static WPEView viewOf(WebView page) {
        WebKitWebView webKitWebView = page.getInternalWebKitWebView();
        assertNotNull("The page has no WebKit web view", webKitWebView);
        return webKitWebView.getWPEView();
    }

    // What WebView does when its page joins its toplevel, and what a WebView that shows several pages would do. A page
    // that is not mapped draws nothing.
    private void join(WebView page, boolean mapped) {
        WPEView view = viewOf(page);
        view.setToplevel(toplevel);
        int[] size = new int[2];
        toplevel.getSize(size);
        view.resized(size[0], size[1]);
        if (mapped)
            view.setMapped(true);
    }

    private void leave(WebView page) {
        WPEView view = viewOf(page);
        view.setMapped(false);
        view.setToplevel(null);
    }

    private void show(WebView page) { activityRule.getScenario().onActivity(activity -> join(page, true)); }

    private void switchPages(WebView from, WebView to) {
        activityRule.getScenario().onActivity(activity -> {
            leave(from);
            join(to, true);
        });
    }

    private void setPageColor(WebView page, int color) {
        TestWebViewClient client = new TestWebViewClient();
        activityRule.getScenario().onActivity(activity -> {
            page.setWebViewClient(client);
            client.getOnEvaluateJavascriptResultHelper().evaluateJavascript(
                page, "document.documentElement.style.background = '" + colorName(color) + "'");
        });
    }

    // The center of the SurfaceView on the screen.
    private int[] surfaceCenter() {
        int[] center = new int[2];
        activityRule.getScenario().onActivity(activity -> {
            surfaceView.getLocationOnScreen(center);
            center[0] += surfaceView.getWidth() / 2;
            center[1] += surfaceView.getHeight() / 2;
        });
        return center;
    }

    private int colorOnScreen() { return colorAt(surfaceCenter()); }

    // The display shows the window without the main thread, so this works also while the main thread is busy.
    private static int colorAt(int[] point) {
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
            return screenshot.getPixel(point[0], point[1]);
        } finally {
            screenshot.recycle();
        }
    }

    private static boolean sameColor(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) <= 8 && Math.abs(Color.green(a) - Color.green(b)) <= 8 &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= 8;
    }

    private static String colorName(int color) { return String.format("#%06x", color & 0xffffff); }

    private void assertColorOnScreen(String message, int expected) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        int actual;
        do {
            actual = colorOnScreen();
            if (sameColor(actual, expected))
                return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(message, colorName(expected), colorName(actual));
    }

    // The screen keeps showing the color for a while.
    private void assertColorStaysOnScreen(String message, int expected, long durationMs) {
        long end = SystemClock.uptimeMillis() + durationMs;
        while (SystemClock.uptimeMillis() < end) {
            int actual = colorOnScreen();
            if (actual == Color.TRANSPARENT)
                continue;
            assertEquals(message, colorName(expected),
                         sameColor(actual, expected) ? colorName(expected) : colorName(actual));
            SystemClock.sleep(50);
        }
    }

    // The screen does not show the color for a while.
    private void assertColorNeverOnScreen(String message, int unexpected, long durationMs) {
        long end = SystemClock.uptimeMillis() + durationMs;
        while (SystemClock.uptimeMillis() < end) {
            assertTrue(message, !sameColor(colorOnScreen(), unexpected));
            SystemClock.sleep(20);
        }
    }

    // The layers with the name, as SurfaceFlinger lists them, followed by their number. A layer with a color also has
    // a child of its own, whose name continues after the number.
    private static int layers(String name) throws IOException {
        Pattern layer = Pattern.compile("\\b" + name + "#\\d+(\\s|\\}|$)");
        int layers = 0;
        for (String line : WebViewActivityScenarioHelper.shell("dumpsys SurfaceFlinger --list").split("\n")) {
            if (layer.matcher(line).find())
                layers++;
        }
        return layers;
    }

    // SurfaceFlinger lists a layer until it has destroyed it, a moment after its last user lets it go, as for the
    // WebView of the activity or the pages of an earlier test.
    private static int settledLayers(String name) throws IOException {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        int layers = layers(name);
        for (int same = 0; same < 10 && SystemClock.uptimeMillis() < deadline; same++) {
            SystemClock.sleep(100);
            int current = layers(name);
            if (current != layers) {
                layers = current;
                same = -1;
            }
        }
        return layers;
    }

    private static void waitFor(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            assertTrue(message, SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(50);
        }
    }

    private static void assertLayers(String message, String name, int expected) {
        waitFor(message, () -> {
            try {
                return layers(name) == expected;
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static int openFiles() {
        String[] files = new File("/proc/self/fd").list();
        return files != null ? files.length : 0;
    }

    // The layers of pages out of the window are listed only by the SurfaceFlinger of Android 15 and later.
    private static void assertLayersAtMost(String message, String name, int expected) {
        waitFor(message, () -> {
            try {
                return layers(name) <= expected;
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    // A released layer would stay on screen as long as the SurfaceView does.
    @Test
    public void testDestroyedToplevelLeavesTheWindow() throws Exception {
        showToplevel();
        int roots = settledLayers(ROOT_LAYER);

        activityRule.getScenario().onActivity(activity -> {
            toplevel.destroy();
            toplevel = null;
        });
        assertLayers("The root of the destroyed toplevel stayed", ROOT_LAYER, roots - 1);
    }

    @Test
    public void testOldRootLeavesTheWindowWithItsSurface() throws Exception {
        showToplevel();
        int roots = settledLayers(ROOT_LAYER);

        activityRule.getScenario().onActivity(activity -> {
            toplevel.onSurfaceDestroyed();
            toplevel.onSurfaceCreated(surfaceView.getHolder().getSurface());
        });
        assertLayers("The old root stayed", ROOT_LAYER, roots);
        assertColorOnScreen("The new root does not show the background color", BACKGROUND);
    }

    @Test
    public void testNewPageTakesThePlaceOfTheOldOne() throws Exception {
        showToplevel();
        int layers = settledLayers(PAGE_LAYER);
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);

        show(red);
        assertColorOnScreen("The page does not show", RED);
        switchPages(red, green);
        assertColorOnScreen("The new page does not show", GREEN);
        assertLayersAtMost("A page that drew has more than one layer", PAGE_LAYER, layers + 2);
    }

    @Test
    public void testPageThatDoesNotDrawShowsTheBackgroundInsteadOfTheOldPage() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        // The old page stays only until the time limit, which is a tenth of a second.
        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            join(green, false);
        });
        long switched = SystemClock.uptimeMillis();
        assertColorOnScreen("The old page stayed on screen", BACKGROUND);
        long shown = SystemClock.uptimeMillis() - switched;
        assertTrue("The old page stayed for " + shown + " ms", shown < 1000);
        assertColorStaysOnScreen("The old page came back", BACKGROUND, 500);

        activityRule.getScenario().onActivity(activity -> viewOf(green).setMapped(true));
        assertColorOnScreen("The new page does not show", GREEN);
    }

    @Test
    public void testPageThatLeavesShowsTheBackground() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        activityRule.getScenario().onActivity(activity -> leave(red));
        assertColorOnScreen("The page stayed on screen after it left", BACKGROUND);
    }

    // The page shows its last frame again at once, without drawing.
    @Test
    public void testPageShowsItsLastFrameWhenItComesBack() throws Exception {
        showToplevel();
        int layers = settledLayers(PAGE_LAYER);
        WebView red = loadPage(RED);
        show(red);
        assertColorOnScreen("The page does not show", RED);
        activityRule.getScenario().onActivity(activity -> leave(red));
        assertColorOnScreen("The page stayed on screen after it left", BACKGROUND);

        activityRule.getScenario().onActivity(activity -> join(red, false));
        assertColorOnScreen("The page did not show its last frame", RED);
        assertLayers("The page does not have one layer", PAGE_LAYER, layers + 1);

        activityRule.getScenario().onActivity(activity -> viewOf(red).setMapped(true));
        setPageColor(red, YELLOW);
        assertColorOnScreen("The page does not draw anymore", YELLOW);
    }

    // A page that comes back takes the place of the old one with its last frame, in the same transaction.
    @Test
    public void testPageThatComesBackShowsItsLastFrameInsteadOfTheOldPage() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        show(red);
        assertColorOnScreen("The page does not show", RED);
        switchPages(red, green);
        assertColorOnScreen("The new page does not show", GREEN);

        activityRule.getScenario().onActivity(activity -> {
            leave(green);
            join(red, false);
        });
        assertColorOnScreen("The page that came back does not show", RED);
        assertColorStaysOnScreen("The page that came back did not stay", RED, 500);
    }

    // A page that leaves and comes back at once keeps its layer on screen.
    @Test
    public void testPageTakesItsLayerBack() throws Exception {
        showToplevel();
        int layers = settledLayers(PAGE_LAYER);
        WebView red = loadPage(RED);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            join(red, true);
        });
        assertColorStaysOnScreen("The page left the screen", RED, 500);
        assertLayers("The page does not have one layer", PAGE_LAYER, layers + 1);

        setPageColor(red, YELLOW);
        assertColorOnScreen("The page does not draw anymore", YELLOW);
    }

    @Test
    public void testQuickSwitchesShowTheLastPage() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        WebView yellow = loadPage(YELLOW);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        switchPages(red, green);
        switchPages(green, yellow);
        assertColorOnScreen("The last page does not show", YELLOW);

        switchPages(yellow, red);
        switchPages(red, yellow);
        assertColorOnScreen("The page that came back does not show", YELLOW);
        assertColorStaysOnScreen("Another page came back", YELLOW, 500);
    }

    // Each page gets back the buffers it showed, also when it shows its last frame again, so WebKit does not make new
    // buffers for it. Each page draws after each switch.
    @Test
    public void testPagesKeepTheirBuffersAfterManySwitches() throws Exception {
        showToplevel();
        int layers = settledLayers(PAGE_LAYER);
        WebView[] twoPages = {loadPage(RED), loadPage(GREEN)};
        int[] pageColors = {RED, GREEN};
        int[] newColors = {YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.BLACK};
        show(twoPages[0]);
        assertColorOnScreen("The page does not show", RED);

        int files = 0;
        int shown = 0;
        for (int round = 0; round < 16; round++) {
            // WebKit makes the buffers of the pages as they draw the first times.
            if (round == 4)
                files = Math.min(openFiles(), openFiles());
            int next = 1 - shown;
            switchPages(twoPages[shown], twoPages[next]);
            assertColorOnScreen("The page that came back does not show", pageColors[next]);
            int color = newColors[round % newColors.length];
            setPageColor(twoPages[next], color);
            assertColorOnScreen("The page does not draw anymore", color);
            pageColors[next] = color;
            shown = next;
        }
        // Fences come and go, so the lower of two counts.
        int newFiles = Math.min(openFiles(), openFiles()) - files;
        assertTrue("The process opened " + newFiles + " files more", newFiles < 6);
        assertLayersAtMost("A page has more than one layer", PAGE_LAYER, layers + 2);
    }

    // Leaving does not take the old page away: it stays while the new page has not drawn. Here the main thread, which
    // runs the main loop of WPE, is busy for longer than the time limit, so neither the first frame of the new page nor
    // the limit can take the old page away.
    @Test
    public void testOldPageStaysWhileTheNewPageHasNotDrawn() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        show(red);
        assertColorOnScreen("The page does not show", RED);
        int[] center = surfaceCenter();

        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            join(green, true);
            new Handler(Looper.getMainLooper()).post(() -> {
                busy.countDown();
                try {
                    done.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        try {
            assertTrue("The main thread did not get busy", busy.await(10, TimeUnit.SECONDS));
            SystemClock.sleep(300);
            int actual = Color.TRANSPARENT;
            for (int attempt = 0; attempt < 5 && actual == Color.TRANSPARENT; attempt++)
                actual = colorAt(center);
            assertEquals("The old page left before the new page showed", colorName(RED),
                         sameColor(actual, RED) ? colorName(RED) : colorName(actual));
        } finally {
            done.countDown();
        }
        assertColorOnScreen("The new page does not show", GREEN);
    }

    // The old page is destroyed while its layer waits for the new page, so the toplevel holds the last reference to
    // it. Its layer goes, and the new page shows.
    @Test
    public void testOldPageIsDestroyedWhenTheNewPageDraws() throws Exception {
        showToplevel();
        int layers = settledLayers(PAGE_LAYER);
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            join(green, true);
            pages.remove(red);
            red.destroy();
        });
        assertColorOnScreen("The new page does not show", GREEN);
        assertLayers("The layer of the destroyed page did not go", PAGE_LAYER, layers + 1);
    }

    @Test
    public void testOldPageIsDestroyedBeforeTheNewPageDraws() throws Exception {
        showToplevel();
        int layers = settledLayers(PAGE_LAYER);
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            join(green, false);
            pages.remove(red);
            red.destroy();
        });
        assertColorOnScreen("The destroyed page stayed on screen", BACKGROUND);
        assertLayers("The layer of the destroyed page did not go", PAGE_LAYER, layers);

        activityRule.getScenario().onActivity(activity -> viewOf(green).setMapped(true));
        assertColorOnScreen("The new page does not show", GREEN);
    }

    // The layer of the old page shows its page in the old window only.
    @Test
    public void testSurfaceIsReplacedDuringASwitch() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        WebView green = loadPage(GREEN);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            join(green, false);
            toplevel.onSurfaceDestroyed();
            toplevel.onSurfaceCreated(surfaceView.getHolder().getSurface());
        });
        // The new window maps the new page, which draws there. The display shows the change from its next frames on.
        SystemClock.sleep(100);
        assertColorNeverOnScreen("The old page came to the new window", RED, 500);
        assertColorOnScreen("The new page does not show", GREEN);

        // The old page kept its last frame.
        activityRule.getScenario().onActivity(activity -> {
            leave(green);
            join(red, false);
        });
        assertColorOnScreen("The old page did not show its last frame", RED);
    }

    // A last frame of another size, here from before the window got smaller, would show the page in a corner.
    @Test
    public void testLastFrameOfAnotherSizeWaitsForTheNextFrame() throws Exception {
        showToplevel();
        WebView red = loadPage(RED);
        show(red);
        assertColorOnScreen("The page does not show", RED);
        activityRule.getScenario().onActivity(activity -> leave(red));
        assertColorOnScreen("The page stayed on screen after it left", BACKGROUND);

        int[] size = new int[2];
        activityRule.getScenario().onActivity(activity -> {
            toplevel.getSize(size);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)surfaceView.getLayoutParams();
            params.height = surfaceView.getHeight() / 2;
            surfaceView.setLayoutParams(params);
        });
        int[] newSize = new int[2];
        waitFor("The toplevel did not get smaller", () -> {
            activityRule.getScenario().onActivity(activity -> toplevel.getSize(newSize));
            return newSize[1] > 0 && newSize[1] < size[1];
        });

        activityRule.getScenario().onActivity(activity -> join(red, false));
        assertColorStaysOnScreen("The page showed its last frame of another size", BACKGROUND, 500);

        activityRule.getScenario().onActivity(activity -> viewOf(red).setMapped(true));
        assertColorOnScreen("The page does not draw at the new size", RED);
    }

    @Test
    public void testToplevelIsDestroyedWhileThePageLeaves() throws Exception {
        showToplevel();
        int roots = settledLayers(ROOT_LAYER);
        WebView red = loadPage(RED);
        show(red);
        assertColorOnScreen("The page does not show", RED);

        activityRule.getScenario().onActivity(activity -> {
            leave(red);
            toplevel.destroy();
            toplevel = new WPEToplevel(WPERuntime.getInstance().getDisplay(), surfaceView.getHolder().getSurface());
            toplevel.setBackgroundColor(BACKGROUND);
        });
        assertColorOnScreen("The page stayed on screen after its toplevel went", BACKGROUND);
        assertLayers("The root of the destroyed toplevel stayed", ROOT_LAYER, roots);

        // The page shows in another toplevel, and draws there.
        show(red);
        assertColorOnScreen("The page does not show in the new toplevel", RED);
        setPageColor(red, YELLOW);
        assertColorOnScreen("The page does not draw in the new toplevel", YELLOW);
    }

    // The page leaves the toplevel of its WebView when the activity stops, and shows again and draws when the
    // activity starts again.
    @Test
    public void testPageShowsAgainWhenTheActivityStartsAgain() throws Exception {
        ActivityScenario<WebViewTestActivity> scenario = activityRule.getScenario();
        scenario.onActivity(activity -> {
            surfaceView = activity.getWebView().getChildAt(0) instanceof SurfaceView
                              ? (SurfaceView)activity.getWebView().getChildAt(0)
                              : null;
            activity.getWebView().setBackgroundColor(BACKGROUND);
        });
        assertTrue("The WebView has no SurfaceView", surfaceView != null);
        TestWebViewClient client = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, client, pageOfColor(RED));
        assertColorOnScreen("The page does not show", RED);

        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        assertColorOnScreen("The page does not show again", RED);
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
            scenario, client, "document.documentElement.style.background = '" + colorName(YELLOW) + "'");
        assertColorOnScreen("The page does not draw anymore", YELLOW);

        // And again, when the layer has been in a window before.
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        assertColorOnScreen("The page does not show again a second time", YELLOW);
    }
}
