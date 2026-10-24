package org.wpewebkit.wpeview;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@RunWith(AndroidJUnit4.class)
public class WebViewSnapshotTest {
    // Red on the left half and blue on the right, filling the view.
    private static final String RED_AND_BLUE_PAGE =
        "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden}"
        + "body{background:linear-gradient(to right,#f00 50%,#00f 50%)}</style>";
    // Red on the top half and blue on the bottom, two views tall.
    private static final String RED_OVER_BLUE_LONG_PAGE =
        "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<style>body{margin:0;height:200vh;background:linear-gradient(to bottom,#f00 50%,#00f 50%)}"
        + "</style><body></body>";
    private static final String VISIBILITY_STATE = "document.visibilityState";
    private static final String HIDDEN = "\"hidden\"";

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
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_AND_BLUE_PAGE);

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
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_OVER_BLUE_LONG_PAGE);

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
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicBoolean called = new AtomicBoolean();
        activityRule.getScenario().onActivity(activity -> {
            try {
                // Record the call instead of failing here: the callback runs on the main thread, where a
                // failed assertion would crash the app instead of failing the test.
                activity.getWebView().captureSnapshot(-1, bitmap -> called.set(true));
            } catch (IllegalArgumentException expected) {
                rejected.set(true);
            }
        });
        // Runs after anything the call queued on the main thread.
        activityRule.getScenario().onActivity(activity -> {});
        Assert.assertTrue("Invalid region was accepted", rejected.get());
        Assert.assertFalse("Callback ran for an invalid region", called.get());
    }

    @Test
    public void snapshotScaledToTheMaxWidthKeepsTheAspectRatioAndColors() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_AND_BLUE_PAGE);
        Bitmap full = null;
        Bitmap scaled = null;
        try {
            full = capture(WebView.SNAPSHOT_REGION_VISIBLE);
            scaled = capture(WebView.SNAPSHOT_REGION_VISIBLE, 100, 0);
            Assert.assertEquals(100, scaled.getWidth());
            Assert.assertEquals(Math.round(full.getHeight() * 100.0 / full.getWidth()), scaled.getHeight());
            Assert.assertEquals(Color.RED, scaled.getPixel(scaled.getWidth() / 4, scaled.getHeight() / 2));
            Assert.assertEquals(Color.BLUE, scaled.getPixel(3 * scaled.getWidth() / 4, scaled.getHeight() / 2));
        } finally {
            recycle(full, scaled);
        }
    }

    @Test
    public void snapshotScaledToTheMaxHeightKeepsTheAspectRatioAndColors() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_AND_BLUE_PAGE);
        Bitmap full = null;
        Bitmap scaled = null;
        try {
            full = capture(WebView.SNAPSHOT_REGION_VISIBLE);
            scaled = capture(WebView.SNAPSHOT_REGION_VISIBLE, 0, 100);
            Assert.assertEquals(100, scaled.getHeight());
            Assert.assertEquals(Math.round(full.getWidth() * 100.0 / full.getHeight()), scaled.getWidth());
            Assert.assertEquals(Color.RED, scaled.getPixel(scaled.getWidth() / 4, scaled.getHeight() / 2));
            Assert.assertEquals(Color.BLUE, scaled.getPixel(3 * scaled.getWidth() / 4, scaled.getHeight() / 2));
        } finally {
            recycle(full, scaled);
        }
    }

    @Test
    public void scaledFullDocumentSnapshotKeepsTheAspectRatioAndColors() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_OVER_BLUE_LONG_PAGE);
        Bitmap full = null;
        Bitmap scaled = null;
        try {
            full = capture(WebView.SNAPSHOT_REGION_FULL_DOCUMENT);
            scaled = capture(WebView.SNAPSHOT_REGION_FULL_DOCUMENT, 100, 0);
            Assert.assertEquals(100, scaled.getWidth());
            Assert.assertEquals(Math.round(full.getHeight() * 100.0 / full.getWidth()), scaled.getHeight());
            Assert.assertEquals(Color.RED, scaled.getPixel(scaled.getWidth() / 2, scaled.getHeight() / 4));
            Assert.assertEquals(Color.BLUE, scaled.getPixel(scaled.getWidth() / 2, 3 * scaled.getHeight() / 4));
        } finally {
            recycle(full, scaled);
        }
    }

    @Test
    public void tinySnapshotDoesNotOvertakeAnEarlierBigOne() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_OVER_BLUE_LONG_PAGE);
        // A full size snapshot of the whole page takes longer to copy than a tiny one of the visible part. WebKit
        // delivers them in the order they were asked for, and the callbacks keep that order.
        CountDownLatch captured = new CountDownLatch(2);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<Bitmap> bitmaps = Collections.synchronizedList(new ArrayList<>());
        activityRule.getScenario().onActivity(activity -> {
            activity.getWebView().captureSnapshot(WebView.SNAPSHOT_REGION_FULL_DOCUMENT, bitmap -> {
                order.add("full");
                bitmaps.add(bitmap);
                captured.countDown();
            });
            activity.getWebView().captureSnapshot(WebView.SNAPSHOT_REGION_VISIBLE, 10, 10, bitmap -> {
                order.add("tiny");
                bitmaps.add(bitmap);
                captured.countDown();
            });
        });
        try {
            Assert.assertTrue("Snapshot callbacks timed out", captured.await(30, TimeUnit.SECONDS));
            Assert.assertFalse("Snapshot failed", bitmaps.contains(null));
            Assert.assertEquals(Arrays.asList("full", "tiny"), order);
        } finally {
            recycle(bitmaps.toArray(new Bitmap[0]));
        }
    }

    @Test
    public void snapshotThatAlreadyFitsKeepsItsSizeAndPixels() throws InterruptedException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_AND_BLUE_PAGE);
        Bitmap full = null;
        Bitmap exactFit = null;
        Bitmap roomy = null;
        Bitmap unlimited = null;
        try {
            full = capture(WebView.SNAPSHOT_REGION_VISIBLE);
            exactFit = capture(WebView.SNAPSHOT_REGION_VISIBLE, full.getWidth(), full.getHeight());
            roomy = capture(WebView.SNAPSHOT_REGION_VISIBLE, full.getWidth() * 2, full.getHeight() * 2);
            unlimited = capture(WebView.SNAPSHOT_REGION_VISIBLE, 0, 0);
            Assert.assertTrue("Limits equal to the size must not change the snapshot", full.sameAs(exactFit));
            Assert.assertTrue("Limits larger than the size must not change the snapshot", full.sameAs(roomy));
            Assert.assertTrue("Limits of 0 must not change the snapshot", full.sameAs(unlimited));
        } finally {
            recycle(full, exactFit, roomy, unlimited);
        }
    }

    @Test
    public void negativeMaxSizeIsRejected() {
        AtomicBoolean called = new AtomicBoolean();
        for (int[] limits : new int[][] {{-1, 100}, {100, -1}, {-1, 0}, {0, -1}}) {
            AtomicBoolean rejected = new AtomicBoolean();
            activityRule.getScenario().onActivity(activity -> {
                try {
                    activity.getWebView().captureSnapshot(WebView.SNAPSHOT_REGION_VISIBLE, limits[0], limits[1],
                                                          bitmap -> called.set(true));
                } catch (IllegalArgumentException expected) {
                    rejected.set(true);
                }
            });
            Assert.assertTrue("Size limits " + limits[0] + "x" + limits[1] + " were accepted", rejected.get());
        }
        // Runs after anything the calls queued on the main thread.
        activityRule.getScenario().onActivity(activity -> {});
        Assert.assertFalse("Callback ran for invalid size limits", called.get());
    }

    /** WebKit paints the snapshot itself, so a page whose view is out of the window, like a hidden tab, works. */
    @Test
    public void snapshotOfAWebViewOutOfTheWindowShowsThePage() throws InterruptedException, TimeoutException {
        WebViewActivityScenarioHelper.loadHtmlSync(activityRule.getScenario(), new TestWebViewClient(),
                                                   RED_AND_BLUE_PAGE);
        activityRule.getScenario().onActivity(activity -> {
            WebView view = activity.getWebView();
            ((ViewGroup)view.getParent()).removeView(view);
        });
        Assert.assertEquals(HIDDEN, WebViewActivityScenarioHelper.waitForJavaScriptResult(activityRule.getScenario(),
                                                                                          VISIBILITY_STATE, HIDDEN));
        Bitmap scaled = null;
        try {
            scaled = capture(WebView.SNAPSHOT_REGION_VISIBLE, 100, 0);
            Assert.assertEquals(100, scaled.getWidth());
            Assert.assertEquals(Color.RED, scaled.getPixel(scaled.getWidth() / 4, scaled.getHeight() / 2));
            Assert.assertEquals(Color.BLUE, scaled.getPixel(3 * scaled.getWidth() / 4, scaled.getHeight() / 2));
        } finally {
            recycle(scaled);
        }
    }

    // A full size snapshot of the region.
    private Bitmap capture(@WebView.SnapshotRegion int region) throws InterruptedException {
        return capture((view, callback) -> view.captureSnapshot(region, callback));
    }

    // A snapshot of the region, scaled to fit in maxWidth by maxHeight, where 0 leaves that side unlimited.
    private Bitmap capture(@WebView.SnapshotRegion int region, int maxWidth, int maxHeight)
        throws InterruptedException {
        return capture((view, callback) -> view.captureSnapshot(region, maxWidth, maxHeight, callback));
    }

    private Bitmap capture(BiConsumer<WebView, WebView.SnapshotCallback> request) throws InterruptedException {
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        WebView.SnapshotCallback callback = bitmap -> {
            result.set(bitmap);
            captured.countDown();
        };
        activityRule.getScenario().onActivity(activity -> request.accept(activity.getWebView(), callback));
        Assert.assertTrue("Snapshot callback timed out", captured.await(30, TimeUnit.SECONDS));
        Assert.assertNotNull("Snapshot failed", result.get());
        return result.get();
    }

    private static void recycle(Bitmap... bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null)
                bitmap.recycle();
        }
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    @SuppressLint("WrongConstant")
    public void nullCallbackIsRejected() {
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicBoolean scaledRejected = new AtomicBoolean();
        activityRule.getScenario().onActivity(activity -> {
            try {
                activity.getWebView().captureSnapshot(null);
            } catch (NullPointerException expected) {
                rejected.set(true);
            }
            // The callback is checked first, as in the other overloads, even when the other arguments are invalid.
            try {
                activity.getWebView().captureSnapshot(-1, 0, 0, null);
            } catch (NullPointerException expected) {
                scaledRejected.set(true);
            }
        });
        Assert.assertTrue("Null callback was accepted", rejected.get());
        Assert.assertTrue("Null callback was not rejected first", scaledRejected.get());
    }
}
