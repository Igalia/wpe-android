package org.wpewebkit.wpeview;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;

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

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    @After
    public void destroyWebView() {
        activityRule.getScenario().onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
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
