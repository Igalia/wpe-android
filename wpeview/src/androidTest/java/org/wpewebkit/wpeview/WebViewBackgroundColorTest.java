package org.wpewebkit.wpeview;

import android.app.UiModeManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class WebViewBackgroundColorTest {
    private static final String BLANK_PAGE = "<!doctype html><html><body></body></html>";
    private static final String DARK_PAGE =
        "<!doctype html><html><head><meta name='color-scheme' content='light dark'></head><body></body></html>";
    // Pages from an address, rather than blank ones, which are dark in dark mode.
    private static final String BASE = "https://example.test/";
    // The canvas color of pages in a dark color scheme.
    private static final int DARK_CANVAS = 0xff1e1e1e;

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    @After
    public void destroyWebView() throws InterruptedException {
        activityRule.getScenario().onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_AUTO);
    }

    @Test
    public void defaultIsWhiteAndColorChangesWithoutNavigation() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), BLANK_PAGE);
        assertPageColor(Color.WHITE);
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setBackgroundColor(0xff123456));
        assertPageColor(0xff123456);
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setBackgroundColor(Color.WHITE));
        assertPageColor(Color.WHITE);
    }

    @Test
    public void colorSetBeforeNavigationDoesNotOverrideCss() throws Exception {
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setBackgroundColor(Color.BLUE));
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), BLANK_PAGE);
        assertPageColor(Color.BLUE);
        TestWebViewClient client = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(
            activityRule.getScenario(), client, "<!doctype html><html style='background:#ff0000'><body></body></html>");
        assertPageColor(Color.RED);
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setBackgroundColor(Color.GREEN));
        // Wait for the renderer to process the color update before checking the unchanged CSS background.
        WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(activityRule.getScenario(), client,
                                                                         "document.documentElement.offsetWidth");
        assertPageColor(Color.RED);
    }

    @Test
    public void nonOpaqueColorsAreRejectedWithoutChangingTheBackground() throws InterruptedException {
        activityRule.getScenario().onActivity(activity -> {
            WebView view = activity.getWebView();
            view.setBackgroundColor(Color.BLUE);
            for (int color : new int[] {Color.TRANSPARENT, 0x80123456}) {
                try {
                    view.setBackgroundColor(color);
                    Assert.fail("Nonopaque background was accepted");
                } catch (IllegalArgumentException expected) {
                    Assert.assertEquals(Color.BLUE, ((ColorDrawable)view.getBackground()).getColor());
                }
            }
        });
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), BLANK_PAGE);
        assertPageColor(Color.BLUE);
    }

    @Test
    public void defaultBeforeTheFirstFrameFollowsDarkMode() throws InterruptedException {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        assertViewColor(Color.WHITE);
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        assertViewColor(DARK_CANVAS);
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        assertViewColor(Color.WHITE);
    }

    @Test
    public void defaultFollowsDarkModeAlsoOutOfTheWindow() throws InterruptedException {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        activityRule.getScenario().onActivity(activity -> {
            WebView view = activity.getWebView();
            parent.set((ViewGroup)view.getParent());
            parent.get().removeView(view);
        });
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        activityRule.getScenario().onActivity(activity -> {
            Assert.assertEquals(DARK_CANVAS, ((ColorDrawable)activity.getWebView().getBackground()).getColor());
            parent.get().addView(activity.getWebView());
        });
    }

    @Test
    public void anotherBackgroundOfTheAppStaysInDarkMode() throws InterruptedException {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        GradientDrawable background = new GradientDrawable();
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setBackground(background));
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        // Once the activity, and the view with it, has the new mode.
        AtomicReference<Boolean> dark = new AtomicReference<>(false);
        for (int attempt = 0; attempt < 20 && !dark.get(); attempt++) {
            Thread.sleep(100);
            activityRule.getScenario().onActivity(
                activity -> dark.set(activity.getResources().getConfiguration().isNightModeActive()));
        }
        Assert.assertTrue(dark.get());
        activityRule.getScenario().onActivity(
            activity -> Assert.assertSame(background, activity.getWebView().getBackground()));
    }

    // Only pages that allow a dark color scheme are dark, whatever the color of the app.
    @Test
    public void colorOfTheAppStaysInDarkMode() throws InterruptedException {
        activityRule.getScenario().onActivity(activity -> activity.getWebView().setBackgroundColor(Color.BLUE));
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), BLANK_PAGE,
                                                   BASE);
        assertPageColor(Color.BLUE);
        assertViewColor(Color.BLUE);
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), DARK_PAGE,
                                                   BASE);
        assertPageColor(DARK_CANVAS);
    }

    @Test
    public void pagesThatAllowDarkGetTheDarkCanvasInDarkMode() throws InterruptedException {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), DARK_PAGE,
                                                   BASE);
        assertPageColor(Color.WHITE);
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        assertPageColor(DARK_CANVAS);
        // A page that does not allow a dark color scheme stays light.
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(), BLANK_PAGE,
                                                   BASE);
        assertPageColor(Color.WHITE);
    }

    // The color the view shows until the first frame of a page, which the native fill layer below the page has too.
    private void assertViewColor(int expected) throws InterruptedException {
        AtomicReference<Integer> actual = new AtomicReference<>();
        for (int attempt = 0; attempt < 20; attempt++) {
            activityRule.getScenario().onActivity(
                activity -> actual.set(((ColorDrawable)activity.getWebView().getBackground()).getColor()));
            if (actual.get() == expected)
                return;
            // The view hears of the new night mode a moment after the application has it.
            Thread.sleep(100);
        }
        Assert.assertEquals(expected, (int)actual.get());
    }

    // WebKit snapshots test its canvas color, not the native SurfaceControl fallback layer.
    private void assertPageColor(int expected) throws InterruptedException {
        int actual = Color.TRANSPARENT;
        for (int attempt = 0; attempt < 20; attempt++) {
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<Bitmap> result = new AtomicReference<>();
            activityRule.getScenario().onActivity(activity -> activity.getWebView().captureSnapshot(bitmap -> {
                result.set(bitmap);
                captured.countDown();
            }));
            Assert.assertTrue("Snapshot callback timed out", captured.await(10, TimeUnit.SECONDS));
            Bitmap bitmap = result.get();
            Assert.assertNotNull("Snapshot failed", bitmap);
            try {
                actual = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
            } finally {
                bitmap.recycle();
            }
            if (actual == expected)
                return;
            // Setting the color sends an asynchronous update to the web process.
            Thread.sleep(100);
        }
        Assert.assertEquals(expected, actual);
    }
}
