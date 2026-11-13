package org.wpewebkit.wpeview;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WPEToplevel;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The layers that a toplevel puts in its native window: a root, which shows the background color, and the layers of
 * the pages under it.
 *
 * The tests here give the surface of a SurfaceView of their own to a toplevel, and check what the display shows and
 * the layers that SurfaceFlinger lists.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewToplevelLayersTest {
    private static final int BACKGROUND = Color.BLUE;
    private static final String ROOT_LAYER = "WPEToplevelRoot";
    private static final long TIMEOUT_MS = 10000;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private SurfaceView surfaceView;
    private WPEToplevel toplevel;

    @After
    public void destroyToplevel() {
        activityRule.getScenario().onActivity(activity -> {
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

    private int colorOnScreen() {
        int[] location = new int[2];
        int[] size = new int[2];
        activityRule.getScenario().onActivity(activity -> {
            surfaceView.getLocationOnScreen(location);
            size[0] = surfaceView.getWidth();
            size[1] = surfaceView.getHeight();
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
}
