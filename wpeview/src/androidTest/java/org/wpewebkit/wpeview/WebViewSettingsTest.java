package org.wpewebkit.wpeview;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WebViewSettingsTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    /**
     * Two WebViews of one WebContext, like two tabs, start from the same defaults, and changing the
     * settings of one leaves the other as it was.
     */
    @Test
    public void testWebViewsOfOneContextHaveTheirOwnSettings() {
        final String changedUserAgent = "WPEWebViewSettingsTest/1.0";
        AtomicReference<String> firstUserAgent = new AtomicReference<>();
        AtomicReference<String> secondUserAgentBefore = new AtomicReference<>();
        AtomicReference<String> secondUserAgentAfter = new AtomicReference<>();
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            WebContext context = new WebContext(activity, false, true);
            WebView first = null;
            WebView second = null;
            try {
                first = new WebView(activity, context);
                second = new WebView(activity, context);
                secondUserAgentBefore.set(second.getSettings().getUserAgentString());
                first.getSettings().setUserAgentString(changedUserAgent);
                firstUserAgent.set(first.getSettings().getUserAgentString());
                secondUserAgentAfter.set(second.getSettings().getUserAgentString());
            } finally {
                if (first != null)
                    first.destroy();
                if (second != null)
                    second.destroy();
                context.destroy();
            }
        });

        assertTrue("A new WebView did not get the mobile User-Agent of its WebContext: " + secondUserAgentBefore.get(),
                   secondUserAgentBefore.get().contains("Mobile"));
        assertEquals(changedUserAgent, firstUserAgent.get());
        assertEquals("Changing the settings of one WebView changed another one", secondUserAgentBefore.get(),
                     secondUserAgentAfter.get());
    }

    /**
     * The settings of a destroyed WebView keep their last values, and can still be read and changed, as code
     * that tears a tab down may do.
     */
    @Test
    public void testSettingsOfADestroyedWebView() {
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> userAgentAfterDestroy = new AtomicReference<>();
        AtomicBoolean developerExtrasAfterDestroy = new AtomicBoolean();
        AtomicBoolean developerExtrasChanged = new AtomicBoolean(true);
        webViewActivityTestRule.getScenario().onActivity(activity -> {
            WebContext context = new WebContext(activity, false, true);
            WebView webView = null;
            try {
                webView = new WebView(activity, context);
                WebSettings settings = webView.getSettings();
                settings.setDeveloperExtrasEnabled(true);
                userAgent.set(settings.getUserAgentString());
                webView.destroy();

                userAgentAfterDestroy.set(settings.getUserAgentString());
                developerExtrasAfterDestroy.set(settings.getDeveloperExtrasEnabled());
                settings.setDeveloperExtrasEnabled(false);
                developerExtrasChanged.set(settings.getDeveloperExtrasEnabled());
            } finally {
                if (webView != null)
                    webView.destroy();
                context.destroy();
            }
        });

        assertEquals(userAgent.get(), userAgentAfterDestroy.get());
        assertTrue(developerExtrasAfterDestroy.get());
        assertFalse(developerExtrasChanged.get());
    }
}
