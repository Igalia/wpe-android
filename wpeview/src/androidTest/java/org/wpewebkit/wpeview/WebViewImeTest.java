package org.wpewebkit.wpeview;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WebViewImeTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    /**
     * Verifies that focusing an editable element in the page makes the WebView an active text
     * editor and produces a valid {@link InputConnection} through the WPE input-method bridge
     * (WebKit input-method-context focus-in -> WPEInputMethodContext focus listener ->
     * WebView.onImeFieldFocused). This is the part of IME handling this widget owns; whether the
     * soft keyboard is actually rendered additionally depends on the window holding focus, which is
     * not guaranteed under ActivityScenario instrumentation.
     */
    @Test
    public void testEditableFocusEngagesInputConnection() throws Throwable {
        CountDownLatch focusScriptDone = new CountDownLatch(1);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                activity.getWebView().setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(@NonNull WebView view, @NonNull String url) {
                        // hasFocus() is true once the view has focus; otherwise focus the editor as
                        // soon as the document gains focus.
                        String focusScript = "(function() {\n"
                                             +
                                             "  function focusEditor() { document.getElementById('editor').focus(); }\n"
                                             + "  if (document.hasFocus()) { focusEditor(); }\n"
                                             + "  else { window.addEventListener('focus', focusEditor); }\n"
                                             + "})();";
                        view.evaluateJavascript(focusScript, value -> focusScriptDone.countDown());
                    }
                });

                // A real user focuses the view by tapping it; the test focuses the editable via JS,
                // so it must give the WebView focus first for the IME to engage.
                activity.getWebView().requestFocus();

                String htmlDocument = "<html><body contenteditable id='editor'></body></html>";
                activity.getWebView().loadHtml(htmlDocument, null);
            })
            .moveToState(Lifecycle.State.RESUMED);

        assertTrue("Focus script never ran", focusScriptDone.await(30, TimeUnit.SECONDS));

        // The editable focus travels WebKit -> WPEInputMethodContext -> WebView asynchronously; poll
        // until the WebView reports itself as a text editor.
        AtomicBoolean isTextEditor = new AtomicBoolean(false);
        for (int i = 0; i < 60 && !isTextEditor.get(); i++) {
            CountDownLatch pollLatch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                isTextEditor.set(activity.getWebView().onCheckIsTextEditor());
                pollLatch.countDown();
            });
            assertTrue(pollLatch.await(5, TimeUnit.SECONDS));
            if (!isTextEditor.get())
                Thread.sleep(250);
        }
        assertTrue("WebView did not become a text editor after the editable was focused", isTextEditor.get());

        // A valid InputConnection with a web-text input type proves the IME bridge is wired up.
        AtomicReference<InputConnection> connection = new AtomicReference<>();
        EditorInfo editorInfo = new EditorInfo();
        CountDownLatch icLatch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            connection.set(activity.getWebView().onCreateInputConnection(editorInfo));
            icLatch.countDown();
        });
        assertTrue(icLatch.await(5, TimeUnit.SECONDS));

        assertNotNull("Focused editable did not yield an InputConnection", connection.get());
        assertTrue("InputConnection is not configured for text input",
                   (editorInfo.inputType & EditorInfo.TYPE_CLASS_TEXT) == EditorInfo.TYPE_CLASS_TEXT);
    }
}
