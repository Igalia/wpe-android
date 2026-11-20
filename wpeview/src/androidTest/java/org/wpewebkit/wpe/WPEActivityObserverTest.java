package org.wpewebkit.wpe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpeview.TestWebViewClient;
import org.wpewebkit.wpeview.WebViewTestActivity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebViewActivityScenarioHelper.loadHtmlSync;
import static org.wpewebkit.wpeview.WebViewActivityScenarioHelper.shell;

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

    private static int appAwayHolds() {
        AtomicInteger count = new AtomicInteger();
        getInstrumentation().runOnMainSync(
            () -> count.set(WPERuntime.getInstance().auxiliaryProcesses.getAppAwayHoldCount()));
        return count.get();
    }

    private static void waitForAppAwayHolds(int expected) throws InterruptedException {
        for (int i = 0; i < 100 && appAwayHolds() != expected; i++)
            Thread.sleep(100);
        assertEquals(expected, appAwayHolds());
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

    /**
     * When the app leaves the front, the process of the page it showed keeps the priority of the app until WebKit holds
     * it again once the app is back, so that Android does not rank it below the app. Another activity of the app over
     * the page leaves the app in front.
     */
    @Test
    public void testPageShownWhenTheAppLeftKeepsThePriorityOfTheApp() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicReference<Activity> activity = new AtomicReference<>();
        scenario.onActivity(activity::set);
        loadHtmlSync(scenario, new TestWebViewClient(), "<p>page</p>");

        shell("input keyevent KEYCODE_HOME");
        waitForStage(activity.get(), Stage.STOPPED);
        assertTrue("The page shown has no hold while the app is away", appAwayHolds() > 0);

        shell("am start --activity-reorder-to-front -n " + activity.get().getPackageName() + "/" +
              WebViewTestActivity.class.getName());
        waitForStage(activity.get(), Stage.RESUMED);
        waitForAppAwayHolds(0);

        // The scenario stops the activity by starting an empty activity of its own, in this process, over it.
        scenario.moveToState(Lifecycle.State.CREATED);
        assertEquals("An activity of the app over the page left it away", 0, appAwayHolds());
    }

    /** When the user leaves with Back, the activity finishes and its page closes, so it keeps no priority. */
    @Test
    public void testPageOfAFinishingActivityDoesNotKeepThePriorityOfTheApp() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicReference<Activity> activity = new AtomicReference<>();
        scenario.onActivity(activity::set);
        loadHtmlSync(scenario, new TestWebViewClient(), "<p>page</p>");

        // The activity destroys its view once it stopped, which ends the process of the page and its holds, so the
        // holds are counted as the activity stops.
        AtomicInteger holdsAtStop = new AtomicInteger(-1);
        activity.get().registerActivityLifecycleCallbacks(new EmptyActivityLifecycleCallbacks() {
            @Override
            public void onActivityPostStopped(@NonNull Activity a) {
                holdsAtStop.set(WPERuntime.getInstance().auxiliaryProcesses.getAppAwayHoldCount());
            }
        });
        getInstrumentation().runOnMainSync(() -> activity.get().finish());
        waitForStage(activity.get(), Stage.DESTROYED);
        assertNull("The app left the front", currentActivity());
        assertEquals(0, holdsAtStop.get());
    }

    private static class EmptyActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}

        @Override
        public void onActivityStarted(@NonNull Activity a) {}

        @Override
        public void onActivityResumed(@NonNull Activity a) {}

        @Override
        public void onActivityPaused(@NonNull Activity a) {}

        @Override
        public void onActivityStopped(@NonNull Activity a) {}

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}

        @Override
        public void onActivityDestroyed(@NonNull Activity a) {}
    }
}
