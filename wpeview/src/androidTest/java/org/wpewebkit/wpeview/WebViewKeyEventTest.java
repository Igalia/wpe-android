package org.wpewebkit.wpeview;

import android.view.KeyEvent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class WebViewKeyEventTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    /**
     * The volume keys, Back and the other keys that Android handles are not sent to the page, and the WebView does
     * not consume them, so the app and the system get them. Other keys go to the page.
     */
    @Test
    public void keysForAndroidAreNotSentToThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = activityRule.getScenario();
        WebViewActivityScenarioHelper.loadHtmlSync(
            scenario, new TestWebViewClient(),
            "<script>var keys = []; addEventListener('keydown', function(e) { keys.push(e.key); });</script>");

        int[] keysForAndroid = {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
                                KeyEvent.KEYCODE_BACK,      KeyEvent.KEYCODE_FORWARD,     KeyEvent.KEYCODE_MENU,
                                KeyEvent.KEYCODE_SEARCH,    KeyEvent.KEYCODE_HEADSETHOOK};
        AtomicReference<String> consumed = new AtomicReference<>();
        AtomicBoolean pageKeyConsumed = new AtomicBoolean();
        scenario.onActivity(activity -> {
            WebView view = activity.getWebView();
            view.requestFocus();
            StringBuilder keys = new StringBuilder();
            for (int keyCode : keysForAndroid) {
                boolean down = view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                boolean up = view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                if (down || up)
                    keys.append(KeyEvent.keyCodeToString(keyCode)).append(' ');
            }
            consumed.set(keys.toString().trim());
            pageKeyConsumed.set(view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
            view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
        });
        Assert.assertEquals("Keys consumed by the WebView", "", consumed.get());
        Assert.assertTrue("The A key was not consumed", pageKeyConsumed.get());

        // The page gets the keys in order, so once it has the A key, it would have any key sent before.
        Assert.assertEquals("\"a\"",
                            WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "keys.join(' ')", "\"a\""));
    }
}
