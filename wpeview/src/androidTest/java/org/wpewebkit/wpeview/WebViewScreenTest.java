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

import android.app.Presentation;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Display;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.WPERuntime;
import org.wpewebkit.wpe.WPEScreen;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WebViewScreenTest {
    private static final String PAGE = "<html><body></body></html>";
    private static final String SCREEN = "[screen.width, screen.height, screen.availWidth, screen.availHeight, "
                                         + "devicePixelRatio].join(' ')";

    // A second display of 600 by 1000 pixels at 240 dpi, a density of 1.5: 400 by 667 CSS pixels.
    private static final int DISPLAY_WIDTH = 600;
    private static final int DISPLAY_HEIGHT = 1000;
    private static final int DISPLAY_DPI = 240;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // The screen a page should see on a display of the given size in pixels and density, as SCREEN gives it.
    private static String screenOf(int width, int height, float density) {
        int cssWidth = Math.round(width / density);
        int cssHeight = Math.round(height / density);
        return cssWidth + " " + cssHeight + " " + cssWidth + " " + cssHeight + " " + Math.round(density * 1000);
    }

    // Reads the result of SCREEN like screenOf() writes it, with the device pixel ratio in thousandths.
    private static String screenFromPage(String result) {
        String[] values = result.replace("\"", "").split(" ");
        if (values.length != 5)
            return result;
        values[4] = String.valueOf(Math.round(Double.parseDouble(values[4]) * 1000));
        return String.join(" ", values);
    }

    // Polls the screen that the page of a WebView sees until it is the expected one, and returns the last value.
    private static String waitForScreen(ActivityScenario<WebViewTestActivity> scenario, WebView webView,
                                        TestWebViewClient webViewClient, String expected) throws Exception {
        TestWebViewClient.OnEvaluateJavaScriptResultHelper result = webViewClient.getOnEvaluateJavascriptResultHelper();
        String screen = null;
        for (int i = 0; i < 50; i++) {
            scenario.onActivity(activity -> result.evaluateJavascript(webView, SCREEN));
            assertTrue("JavaScript evaluation did not finish", result.waitUntilHasValue(5, TimeUnit.SECONDS));
            screen = screenFromPage(result.getResultAndClear());
            if (expected.equals(screen))
                break;
            Thread.sleep(100);
        }
        return screen;
    }

    private static boolean waitFor(ActivityScenario<WebViewTestActivity> scenario, Supplier<Boolean> condition)
        throws InterruptedException {
        AtomicReference<Boolean> met = new AtomicReference<>(false);
        for (int i = 0; i < 50 && !met.get(); i++) {
            scenario.onActivity(activity -> met.set(condition.get()));
            if (!met.get())
                Thread.sleep(100);
        }
        return met.get();
    }

    private static boolean hasSize(WPEScreen screen, int width, int height, float scale) {
        return screen.getWidth() == width && screen.getHeight() == height &&
            Math.abs(screen.getScale() - scale) < 0.01F;
    }

    private static VirtualDisplay createDisplay(WebViewTestActivity activity, ImageReader imageReader) {
        DisplayManager displayManager = activity.getSystemService(DisplayManager.class);
        return displayManager.createVirtualDisplay(
            "WebViewScreenTest", DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI, imageReader.getSurface(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
    }

    /**
     * Pages see the size of the whole screen in CSS pixels: the size of the display divided by the device pixel
     * ratio, which is the density of the display.
     */
    @Test
    public void testPageSeesTheScreenOfTheDisplay() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, PAGE);

        AtomicReference<String> expected = new AtomicReference<>();
        scenario.onActivity(activity -> {
            Rect bounds = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            float density = activity.getResources().getDisplayMetrics().density;
            expected.set(screenOf(bounds.width(), bounds.height(), density));
        });

        assertEquals("The page did not get the screen of the display", expected.get(),
                     screenFromPage(WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                         scenario, webViewClient, SCREEN)));
    }

    /**
     * A page on another display sees the screen and the density of that display, which WebKit gets as the display
     * comes.
     */
    @Test
    public void testPageOnAnotherDisplaySeesItsScreen() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);

        ImageReader imageReader = ImageReader.newInstance(DISPLAY_WIDTH, DISPLAY_HEIGHT, PixelFormat.RGBA_8888, 2);
        AtomicReference<VirtualDisplay> virtualDisplay = new AtomicReference<>();
        AtomicReference<Presentation> presentation = new AtomicReference<>();
        AtomicReference<WebContext> webContext = new AtomicReference<>();
        AtomicReference<WebView> webView = new AtomicReference<>();
        TestWebViewClient webViewClient = new TestWebViewClient();
        try {
            scenario.onActivity(activity -> {
                virtualDisplay.set(createDisplay(activity, imageReader));
                presentation.set(new Presentation(activity, virtualDisplay.get().getDisplay()));
                webContext.set(new WebContext(activity, false, true));
                webView.set(new WebView(presentation.get().getContext(), webContext.get()));
                webView.get().setWebViewClient(webViewClient);
                presentation.get().setContentView(webView.get());
                presentation.get().show();
                webView.get().loadHtml(PAGE, null);
            });
            webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);

            String expected = screenOf(DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI / 160F);
            assertEquals("The page did not get the screen of its display", expected,
                         waitForScreen(scenario, webView.get(), webViewClient, expected));
        } finally {
            scenario.onActivity(activity -> {
                if (presentation.get() != null)
                    presentation.get().dismiss();
                if (webView.get() != null)
                    webView.get().destroy();
                if (webContext.get() != null)
                    webContext.get().destroy();
                if (virtualDisplay.get() != null)
                    virtualDisplay.get().release();
            });
            imageReader.close();
        }
    }

    /**
     * A WebView can outlive the display it shows on: the display goes while the WebView still shows a page on it,
     * and the WebView is destroyed after its screen has gone.
     */
    @Test
    public void testWebViewOutlivesItsDisplay() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);

        ImageReader imageReader = ImageReader.newInstance(DISPLAY_WIDTH, DISPLAY_HEIGHT, PixelFormat.RGBA_8888, 2);
        AtomicReference<VirtualDisplay> virtualDisplay = new AtomicReference<>();
        AtomicReference<Display> display = new AtomicReference<>();
        AtomicReference<Presentation> presentation = new AtomicReference<>();
        AtomicReference<WebContext> webContext = new AtomicReference<>();
        AtomicReference<WebView> webView = new AtomicReference<>();
        TestWebViewClient webViewClient = new TestWebViewClient();
        try {
            scenario.onActivity(activity -> {
                virtualDisplay.set(createDisplay(activity, imageReader));
                display.set(virtualDisplay.get().getDisplay());
                presentation.set(new Presentation(activity, display.get()));
                webContext.set(new WebContext(activity, false, true));
                webView.set(new WebView(presentation.get().getContext(), webContext.get()));
                webView.get().setWebViewClient(webViewClient);
                presentation.get().setContentView(webView.get());
                presentation.get().show();
                webView.get().loadHtml(PAGE, null);
            });
            webViewClient.getOnPageFinishedHelper().waitForCallback(30, TimeUnit.SECONDS);
            String expected = screenOf(DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DPI / 160F);
            assertEquals("The page did not get the screen of its display", expected,
                         waitForScreen(scenario, webView.get(), webViewClient, expected));

            scenario.onActivity(activity -> virtualDisplay.getAndSet(null).release());
            assertTrue("The screen did not go with its display",
                       waitFor(scenario, () -> WPERuntime.getInstance().getDisplay().getScreen(display.get()) == null));
            scenario.onActivity(activity -> webView.getAndSet(null).destroy());
        } finally {
            scenario.onActivity(activity -> {
                if (presentation.get() != null)
                    presentation.get().dismiss();
                if (webView.get() != null)
                    webView.get().destroy();
                if (webContext.get() != null)
                    webContext.get().destroy();
                if (virtualDisplay.get() != null)
                    virtualDisplay.get().release();
            });
            imageReader.close();
        }
    }

    /**
     * The screen of a display follows it when its size changes, as when the device turns or folds, and goes with
     * it.
     */
    @Test
    public void testScreenFollowsItsDisplay() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);

        ImageReader imageReader = ImageReader.newInstance(DISPLAY_WIDTH, DISPLAY_HEIGHT, PixelFormat.RGBA_8888, 2);
        AtomicReference<VirtualDisplay> virtualDisplay = new AtomicReference<>();
        AtomicReference<Display> display = new AtomicReference<>();
        AtomicReference<WPEScreen> screen = new AtomicReference<>();
        try {
            scenario.onActivity(activity -> {
                virtualDisplay.set(createDisplay(activity, imageReader));
                display.set(virtualDisplay.get().getDisplay());
                screen.set(WPERuntime.getInstance().getDisplay().getScreen(display.get()));
            });
            assertNotNull("The display has no screen", screen.get());
            assertTrue("The screen does not have the size of its display",
                       waitFor(scenario, () -> hasSize(screen.get(), 400, 667, 1.5F)));

            // Turned, at a new density: 1000 by 600 pixels at 320 dpi are 500 by 300 CSS pixels.
            scenario.onActivity(activity -> virtualDisplay.get().resize(DISPLAY_HEIGHT, DISPLAY_WIDTH, 320));
            assertTrue("The screen did not follow the display",
                       waitFor(scenario, () -> hasSize(screen.get(), 500, 300, 2)));

            scenario.onActivity(activity -> virtualDisplay.get().release());
            assertTrue("The screen did not go with its display",
                       waitFor(scenario, () -> WPERuntime.getInstance().getDisplay().getScreen(display.get()) == null));
            scenario.onActivity(activity -> assertEquals(-1, screen.get().getWidth()));
        } finally {
            scenario.onActivity(activity -> {
                if (virtualDisplay.get() != null)
                    virtualDisplay.get().release();
            });
            imageReader.close();
        }
    }
}
