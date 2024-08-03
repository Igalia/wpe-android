package com.wpe.wpeview;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.runner.AndroidJUnit4;

import com.wpe.wpe.Browser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WPEViewImeTest {
    boolean loaded = false;
    @Rule
    public ActivityScenarioRule<WPEViewTestActivity> wpeViewActivityTestRule =
        new ActivityScenarioRule<>(WPEViewTestActivity.class);
    @Test
    public void testIMEVisible() {
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> {
            final Object syncObject = new Object();
            Log.v("FOOBAR", "111");
            // activity.getWPEView()
            Log.v("FOOBAR", "222");

            Log.v("FOOBAR", "333");

            while (true) {
                Browser.getInstance().nativeInvokeOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Log.v("FOOBAR", "444");
                        if (!loaded) {

                            loaded = true;
                        }
                    }
                });
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
