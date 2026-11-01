package org.wpewebkit.wpe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpeview.WebViewTestActivity;

import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

@RunWith(AndroidJUnit4.class)
public class WPEActivityObserverTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private static Activity currentActivity() {
        AtomicReference<Activity> current = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> current.set(WPEActivityObserver.getCurrentActivity()));
        return current.get();
    }

    private static Stage stageOf(Activity activity) {
        AtomicReference<Stage> stage = new AtomicReference<>();
        getInstrumentation().runOnMainSync(
            () -> stage.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)));
        return stage.get();
    }

    private static void waitForStage(Activity activity, Stage expected) throws InterruptedException {
        for (int i = 0; i < 100 && stageOf(activity) != expected; i++)
            Thread.sleep(100);
        assertEquals(expected, stageOf(activity));
    }

    /**
     * Android starts an activity before it stops the one that opened it, and starts the opener again before it stops
     * the activity that closes. The activity that started last stays the current one.
     */
    @Test
    public void testActivityThatStartedLastIsTheCurrentOne() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicReference<Activity> opener = new AtomicReference<>();
        scenario.onActivity(opener::set);
        assertSame(opener.get(), currentActivity());

        Instrumentation.ActivityMonitor monitor =
            getInstrumentation().addMonitor(WebViewTestActivity.class.getName(), null, false);
        scenario.onActivity(activity -> activity.startActivity(new Intent(activity, WebViewTestActivity.class)));
        Activity opened = getInstrumentation().waitForMonitorWithTimeout(monitor, 30000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull(opened);
        try {
            waitForStage(opener.get(), Stage.STOPPED);
            assertSame(opened, currentActivity());
        } finally {
            getInstrumentation().runOnMainSync(opened::finish);
        }

        waitForStage(opened, Stage.DESTROYED);
        assertSame(opener.get(), currentActivity());
    }
}
