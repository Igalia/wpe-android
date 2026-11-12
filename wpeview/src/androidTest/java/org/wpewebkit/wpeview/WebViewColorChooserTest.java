package org.wpewebkit.wpeview;

import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A color field asks the chrome client for a color, and the page gets the chosen one with an input and a change event.
 * A cancelled request leaves the field as it was, and a field that goes away ends the request.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewColorChooserTest {
    private static final String PAGE = "<!doctype html><title>Color chooser</title>"
                                       + "<input id=color type=color value='#123456'>"
                                       + "<input id=alpha type=color alpha value='#11223344'>"
                                       + "<script>let events = [];"
                                       + "for (const input of document.querySelectorAll('input')) {"
                                       + "  input.oninput = () => events.push('input ' + input.value);"
                                       + "  input.onchange = () => events.push('change ' + input.value);"
                                       + "}</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private final TestWebViewClient webViewClient = new TestWebViewClient();

    private static class ColorChooserClient extends WebChromeClient {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger hidden = new AtomicInteger();
        final AtomicReference<ColorChooserCallback> callback = new AtomicReference<>();
        final AtomicReference<ColorChooserParams> params = new AtomicReference<>();

        @Override
        public boolean onShowColorChooser(@NonNull WebView view, @NonNull ColorChooserParams colorParams,
                                          @NonNull ColorChooserCallback colorCallback) {
            callback.set(colorCallback);
            params.set(colorParams);
            requests.incrementAndGet();
            return true;
        }

        @Override
        public void onHideColorChooser(@NonNull WebView view) {
            hidden.incrementAndGet();
        }
    }

    private ActivityScenario<WebViewTestActivity> loadPage(WebChromeClient client) {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(client))
            .moveToState(Lifecycle.State.RESUMED);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, PAGE);
        return scenario;
    }

    private String run(ActivityScenario<WebViewTestActivity> scenario, String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, script);
    }

    // A script WebView runs counts as a user gesture, which a color field needs to open.
    private void click(ActivityScenario<WebViewTestActivity> scenario, String id) throws Throwable {
        run(scenario, "document.getElementById('" + id + "').click()");
    }

    private static void waitFor(AtomicInteger counter, int value, String what) throws InterruptedException {
        for (int i = 0; i < 100 && counter.get() < value; i++)
            Thread.sleep(100);
        Assert.assertEquals(what, value, counter.get());
    }

    private String events(ActivityScenario<WebViewTestActivity> scenario, String expected) throws Throwable {
        return WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "events.join(',')", expected);
    }

    @Test
    public void testFieldAsksWithItsColor() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        Assert.assertEquals(0xff123456, client.params.get().getColor());
        Assert.assertFalse(client.params.get().supportsAlpha());
    }

    @Test
    public void testConfirmReachesThePage() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        scenario.onActivity(activity -> client.callback.get().confirm(Color.BLUE));
        String expected = "\"input #0000ff,change #0000ff\"";
        Assert.assertEquals(expected, events(scenario, expected));
        Assert.assertEquals("\"#0000ff\"", run(scenario, "document.getElementById('color').value"));
        // An answer after the end does nothing.
        scenario.onActivity(activity -> client.callback.get().confirm(Color.GREEN));
        Thread.sleep(500);
        Assert.assertEquals("\"#0000ff\"", run(scenario, "document.getElementById('color').value"));
        Assert.assertEquals(0, client.hidden.get());
    }

    @Test
    public void testFieldWithoutAlphaGetsAnOpaqueColor() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        scenario.onActivity(activity -> client.callback.get().confirm(0x40ff0000));
        String expected = "\"input #ff0000,change #ff0000\"";
        Assert.assertEquals(expected, events(scenario, expected));
    }

    @Test
    public void testCancelLeavesTheField() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        scenario.onActivity(activity -> client.callback.get().cancel());
        // The field asks again once it is answered.
        click(scenario, "color");
        waitFor(client.requests, 2, "The field did not ask again");
        Assert.assertEquals("\"#123456\"", run(scenario, "document.getElementById('color').value"));
        Assert.assertEquals("\"\"", run(scenario, "events.join(',')"));
    }

    @Test
    public void testFieldWithAlpha() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "alpha");
        waitFor(client.requests, 1, "The field did not ask for a color");
        Assert.assertTrue(client.params.get().supportsAlpha());
        Assert.assertEquals(0x44112233, client.params.get().getColor());
    }

    @Test
    public void testConfirmWithAlphaReachesThePage() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "alpha");
        waitFor(client.requests, 1, "The field did not ask for a color");
        scenario.onActivity(activity -> client.callback.get().confirm(0x330000ff));
        String expected = "\"input color(srgb 0 0 1 / 0.2),change color(srgb 0 0 1 / 0.2)\"";
        Assert.assertEquals(expected, events(scenario, expected));
    }

    @Test
    public void testConfirmFromAnotherThreadReachesThePage() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        Thread thread = new Thread(() -> client.callback.get().confirm(Color.BLUE));
        thread.start();
        thread.join();
        String expected = "\"input #0000ff,change #0000ff\"";
        Assert.assertEquals(expected, events(scenario, expected));
    }

    @Test
    public void testPageEndsTheRequestWhenTheFieldGoesAway() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        run(scenario, "document.getElementById('color').remove()");
        waitFor(client.hidden, 1, "The client was not told that the page ended the request");
        // The callback of an ended request does nothing.
        scenario.onActivity(activity -> client.callback.get().confirm(Color.RED));
        Thread.sleep(500);
        Assert.assertEquals("\"\"", run(scenario, "events.join(',')"));
    }

    @Test
    public void testAnswerAfterDestroyIsDropped() throws Throwable {
        ColorChooserClient client = new ColorChooserClient();
        ActivityScenario<WebViewTestActivity> scenario = loadPage(client);
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask for a color");
        // Destroying the view cancels the request, and the answers that come later have nothing to answer.
        scenario.onActivity(activity -> {
            activity.getWebView().destroy();
            client.callback.get().confirm(Color.BLUE);
            client.callback.get().cancel();
        });
        Thread thread = new Thread(() -> client.callback.get().confirm(Color.GREEN));
        thread.start();
        thread.join();
        Thread.sleep(500);
    }

    @Test
    public void testFieldWithoutClientKeepsItsColor() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = loadPage(new WebChromeClient());
        click(scenario, "color");
        ColorChooserClient client = new ColorChooserClient();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(client));
        click(scenario, "color");
        waitFor(client.requests, 1, "The field did not ask again");
        Assert.assertEquals("\"#123456\"", run(scenario, "document.getElementById('color').value"));
        Assert.assertEquals("\"\"", run(scenario, "events.join(',')"));
    }
}
