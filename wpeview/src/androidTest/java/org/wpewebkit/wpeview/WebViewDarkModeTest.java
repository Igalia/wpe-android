/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpeview;

import android.app.UiModeManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class WebViewDarkModeTest {
    private static final String PAGE = "<!doctype html><html><body>Text</body></html>";
    private static final String DARK_MODE = "matchMedia('(prefers-color-scheme: dark)').matches";
    // The color of the text of a page, which is white in dark mode if the page allows a dark color scheme.
    private static final String TEXT_COLOR = "getComputedStyle(document.body).color";
    private static final String DARK_PAGE =
        "<!doctype html><html><head><meta name='color-scheme' content='light dark'></head><body>Text</body></html>";
    // Pages from an address, rather than blank ones, which are dark in dark mode.
    private static final String BASE = "https://example.test/";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private ActivityScenario<WebViewTestActivity> scenario;

    @Before
    public void setUp() {
        scenario = activityRule.getScenario();
    }

    @After
    public void tearDown() throws InterruptedException {
        scenario.onActivity(activity -> {
            if (activity.getWebView().getInternalWebKitWebView() != null)
                activity.getWebView().destroy();
        });
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_AUTO);
    }

    @Test
    public void pagesFollowTheDarkModeOfTheApp() throws Exception {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), PAGE, BASE);
        assertEquals("false", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, DARK_MODE, "false"));
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, DARK_MODE, "true"));
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_NO);
        assertEquals("false", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, DARK_MODE, "false"));
    }

    @Test
    public void pagesStartInTheDarkModeOfTheApp() throws Exception {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        TestWebViewClient client = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, client, DARK_PAGE, BASE);
        // The first answer, before the web process could hear of any change.
        assertEquals("true",
                     WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, DARK_MODE));
        assertEquals("\"rgb(255, 255, 255)\"",
                     WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, TEXT_COLOR));
    }

    @Test
    public void pagesThatDoNotAllowDarkStayLight() throws Exception {
        WebViewActivityScenarioHelper.setNightMode(UiModeManager.MODE_NIGHT_YES);
        TestWebViewClient client = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, client, PAGE, BASE);
        assertEquals("true",
                     WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, DARK_MODE));
        assertEquals("\"rgb(0, 0, 0)\"",
                     WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, client, TEXT_COLOR));
    }
}
