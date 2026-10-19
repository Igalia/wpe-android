package org.wpewebkit.wpeview;

import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class WebViewScreenSyncTest {
    private static final String TAG = "WebViewScreenSyncTest";
    private static final int WARM_UP_CYCLES = 3;
    private static final int CYCLES = 30;
    private static final int MAX_FD_GROWTH = CYCLES / 2;

    // One requestAnimationFrame starts the screen sync observer. WebKit stops it again once the display link has
    // fired 20 times with nothing to do, so the pause between frames must be longer than 20 frames.
    private static final String CYCLES_PAGE = "<html><body><script>\n"
                                              + "window.cyclesDone = 0;\n"
                                              + "function runCycles(count) {\n"
                                              + "  requestAnimationFrame(() => setTimeout(() => {\n"
                                              + "    window.cyclesDone++;\n"
                                              + "    if (window.cyclesDone < count)\n"
                                              + "      runCycles(count);\n"
                                              + "  }, 1000));\n"
                                              + "}\n"
                                              + "</script></body></html>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    @Test
    public void testStartingAndStoppingAnimationsDoesNotLeakFileDescriptors() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), CYCLES_PAGE);

        runAnimationCycles(scenario, WARM_UP_CYCLES);
        int fdsBefore = countEntries("/proc/self/fd");
        int threadsBefore = countEntries("/proc/self/task");

        runAnimationCycles(scenario, CYCLES);
        int fdsAfter = countEntries("/proc/self/fd");
        int threadsAfter = countEntries("/proc/self/task");

        String counts = String.format("After %d animation cycles: file descriptors %d -> %d, threads %d -> %d", CYCLES,
                                      fdsBefore, fdsAfter, threadsBefore, threadsAfter);
        Log.i(TAG, counts);
        assertTrue(counts, fdsAfter - fdsBefore < MAX_FD_GROWTH);
    }

    private static void runAnimationCycles(ActivityScenario<WebViewTestActivity> scenario, int count)
        throws InterruptedException {
        evaluateJavascript(scenario, "window.cyclesDone = 0; runCycles(" + count + "); true");
        String finished = "window.cyclesDone === " + count;
        for (int i = 0; i < count * 4; i++) {
            if ("true".equals(evaluateJavascript(scenario, finished)))
                return;
            Thread.sleep(500);
        }
        fail("The page did not finish " + count + " animation cycles");
    }

    private static String evaluateJavascript(ActivityScenario<WebViewTestActivity> scenario, String script)
        throws InterruptedException {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> activity.getWebView().evaluateJavascript(script, value -> {
            result.set(value);
            latch.countDown();
        }));
        assertTrue("JavaScript evaluation did not finish", latch.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private static int countEntries(String directory) {
        String[] entries = new File(directory).list();
        return entries != null ? entries.length : 0;
    }
}
