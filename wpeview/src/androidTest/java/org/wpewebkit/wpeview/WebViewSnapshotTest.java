package org.wpewebkit.wpeview;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class WebViewSnapshotTest {
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
    public void snapshotPreservesRedAndBlueAndUsesMainThread() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(
            activityRule.getScenario(), new TestWebViewClient(),
            "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden}"
                + "body{background:linear-gradient(to right,#f00 50%,#00f 50%)}</style>");

        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicBoolean onMainThread = new AtomicBoolean();
        activityRule.getScenario().onActivity(activity -> activity.getWebView().captureSnapshot(bitmap -> {
            result.set(bitmap);
            onMainThread.set(Looper.myLooper() == Looper.getMainLooper());
            captured.countDown();
        }));
        Assert.assertTrue("Snapshot callback timed out", captured.await(30, TimeUnit.SECONDS));
        Bitmap bitmap = result.get();
        Assert.assertNotNull("Snapshot failed", bitmap);
        try {
            Assert.assertTrue("Callback must use main thread", onMainThread.get());
            Assert.assertTrue(bitmap.getWidth() >= 4 && bitmap.getHeight() >= 2);
            Assert.assertEquals(Color.RED, bitmap.getPixel(bitmap.getWidth() / 4, bitmap.getHeight() / 2));
            Assert.assertEquals(Color.BLUE, bitmap.getPixel(3 * bitmap.getWidth() / 4, bitmap.getHeight() / 2));
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void fullDocumentSnapshotIncludesContentOutsideViewport() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(
            activityRule.getScenario(), new TestWebViewClient(),
            "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:200vh;background:linear-gradient(to bottom,#f00 50%,#00f 50%)}"
                + "</style><body></body>");

        CountDownLatch captured = new CountDownLatch(2);
        AtomicReference<Bitmap> visibleResult = new AtomicReference<>();
        AtomicReference<Bitmap> fullResult = new AtomicReference<>();
        AtomicBoolean onMainThread = new AtomicBoolean(true);
        try {
            activityRule.getScenario().onActivity(activity -> {
                WebView view = activity.getWebView();
                view.captureSnapshot(WebView.SNAPSHOT_REGION_VISIBLE, bitmap -> {
                    visibleResult.set(bitmap);
                    onMainThread.compareAndSet(true, Looper.myLooper() == Looper.getMainLooper());
                    captured.countDown();
                });
                view.captureSnapshot(WebView.SNAPSHOT_REGION_FULL_DOCUMENT, bitmap -> {
                    fullResult.set(bitmap);
                    onMainThread.compareAndSet(true, Looper.myLooper() == Looper.getMainLooper());
                    captured.countDown();
                });
            });
            Assert.assertTrue("Snapshot callbacks timed out", captured.await(30, TimeUnit.SECONDS));
            Assert.assertTrue("Callbacks must use main thread", onMainThread.get());
            Bitmap visible = visibleResult.get();
            Bitmap full = fullResult.get();
            Assert.assertNotNull("Visible snapshot failed", visible);
            Assert.assertNotNull("Full-document snapshot failed", full);
            Assert.assertEquals(visible.getWidth(), full.getWidth());
            Assert.assertTrue("Full-document snapshot must extend past the viewport",
                              full.getHeight() > visible.getHeight());
            Assert.assertEquals(Color.RED, visible.getPixel(visible.getWidth() / 2, visible.getHeight() / 2));
            Assert.assertEquals(Color.RED, full.getPixel(full.getWidth() / 2, full.getHeight() / 4));
            Assert.assertEquals(Color.BLUE, full.getPixel(full.getWidth() / 2, 3 * full.getHeight() / 4));
        } finally {
            if (visibleResult.get() != null)
                visibleResult.get().recycle();
            if (fullResult.get() != null)
                fullResult.get().recycle();
        }
    }

    @Test
    public void destroyedViewReturnsNullAsynchronouslyOnMainThread() throws InterruptedException {
        CountDownLatch captured = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean nullOnMainThreadAfterReturn = new AtomicBoolean();
        activityRule.getScenario().onActivity(activity -> {
            WebView view = activity.getWebView();
            view.destroy();
            view.captureSnapshot(bitmap -> {
                nullOnMainThreadAfterReturn.set(bitmap == null && returned.get() &&
                                                Looper.myLooper() == Looper.getMainLooper());
                captured.countDown();
            });
            returned.set(true);
        });
        Assert.assertTrue("Snapshot callback timed out", captured.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(nullOnMainThreadAfterReturn.get());
    }

    @Test
    @SuppressLint("WrongConstant")
    public void invalidRegionIsRejected() {
        activityRule.getScenario().onActivity(activity -> {
            try {
                activity.getWebView().captureSnapshot(-1, bitmap -> Assert.fail("Unexpected callback"));
                Assert.fail("Invalid region was accepted");
            } catch (IllegalArgumentException expected) {
                // Invalid input must not reach WebKit's enum-based API.
            }
        });
    }

    @Test
    public void internalInvalidRegionReturnsNullAsynchronouslyOnMainThread() throws InterruptedException {
        CountDownLatch captured = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean nullOnMainThreadAfterReturn = new AtomicBoolean();
        activityRule.getScenario().onActivity(activity -> {
            // Native code rejects the region synchronously; the callback must still be queued.
            activity.getWebView().getInternalWebKitWebView().captureSnapshot(-1, bitmap -> {
                nullOnMainThreadAfterReturn.set(bitmap == null && returned.get() &&
                                                Looper.myLooper() == Looper.getMainLooper());
                if (bitmap != null)
                    bitmap.recycle();
                captured.countDown();
            });
            returned.set(true);
        });
        Assert.assertTrue("Snapshot callback timed out", captured.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(nullOnMainThreadAfterReturn.get());
    }
}
