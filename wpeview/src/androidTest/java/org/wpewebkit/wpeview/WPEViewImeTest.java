package org.wpewebkit.wpeview;

import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WPEViewImeTest {
    @Rule
    public ActivityScenarioRule<WPEViewTestActivity> wpeViewActivityTestRule =
        new ActivityScenarioRule<>(WPEViewTestActivity.class);

    @Test
    public void testIMEVisible() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        ActivityScenario<WPEViewTestActivity> scenario = wpeViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWPEView().setWPEViewClient(new WPEViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WPEView view, @NonNull String url) {
                        String focusScript = "function onDocumentFocused() {\n"
                                             + "  document.getElementById('editor').focus();\n"
                                             + "  test.onEditorFocused();\n"
                                             + "}\n"
                                             + "(function() {\n"
                                             + "if (document.hasFocus()) {\n"
                                             + "  onDocumentFocused();"
                                             + "} else {\n"
                                             + "  window.addEventListener('focus', onDocumentFocused);\n"
                                             + "}})();";
                        view.evaluateJavascript(focusScript, value -> latch.countDown());
                    }
                });

                String htmlDocument = "<html><body contenteditable id='editor'></body></html>";
                activity.getWPEView().loadHtml(htmlDocument, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            // Give IME time to popup
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        CountDownLatch secondLatch = new CountDownLatch(1);

        AtomicBoolean keyboardShown = new AtomicBoolean(false);
        scenario.onActivity(activity -> {
            keyboardShown.set(activity.getWPEView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()));

            secondLatch.countDown();
        });

        try {
            secondLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertTrue(keyboardShown.get());
    }
}
