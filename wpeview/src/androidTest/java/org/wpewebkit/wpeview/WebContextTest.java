package org.wpewebkit.wpeview;

import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.PreferredLanguages;
import org.wpewebkit.wpe.httpserver.HttpRequest;
import org.wpewebkit.wpe.httpserver.HttpResponse;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class WebContextTest {
    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    // Polls window.devicePixelRatio until it is the expected one, and returns the last value.
    private static double waitForDevicePixelRatio(ActivityScenario<WebViewTestActivity> scenario,
                                                  TestWebViewClient webViewClient, double expected) throws Throwable {
        double ratio = 0;
        for (int i = 0; i < 100; i++) {
            ratio = Double.parseDouble(WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                scenario, webViewClient, "window.devicePixelRatio"));
            if (Math.abs(ratio - expected) < 0.01)
                break;
            Thread.sleep(100);
        }
        return ratio;
    }

    /**
     * Every WebContext uses the display of the process, and destroying one leaves that display
     * working for the others.
     */
    @Test
    public void testContextsShareTheDisplay() throws Throwable {
        AtomicBoolean sameDisplay = new AtomicBoolean(false);
        AtomicLong displayAfterDestroy = new AtomicLong(0);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario
            .onActivity(activity -> {
                WebContext first = new WebContext(activity, false, true);
                WebContext second = new WebContext(activity, false, true);
                try {
                    sameDisplay.set(first.getWPEDisplay() == second.getWPEDisplay());
                    first.destroy();
                    first = null;
                    displayAfterDestroy.set(second.getWPEDisplay().getNativePtr());
                } finally {
                    if (first != null)
                        first.destroy();
                    second.destroy();
                }
            })
            .moveToState(Lifecycle.State.RESUMED);

        assertTrue("Two WebContexts got different displays", sameDisplay.get());
        assertNotEquals("Destroying a WebContext destroyed the shared display", 0, displayAfterDestroy.get());

        // The activity's own WebView uses the same display, and still loads pages and runs scripts.
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, "<html><body></body></html>");
        assertEquals(
            "2", WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient, "1 + 1"));
    }

    /**
     * A new display size reaches the pages of an activity that handles that configuration change itself, as the
     * test activity does, and keeps its WebViews.
     */
    @Test
    public void testDisplaySizeChangeReachesThePage() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, webViewClient, "<html><body></body></html>");

        String densities = WebViewActivityScenarioHelper.shell("wm density");
        Matcher physical = Pattern.compile("Physical density: (\\d+)").matcher(densities);
        Matcher override = Pattern.compile("Override density: (\\d+)").matcher(densities);
        assertTrue("Unexpected output of wm density: " + densities, physical.find());
        boolean overridden = override.find();
        int density = Integer.parseInt(overridden ? override.group(1) : physical.group(1));
        String restore = overridden ? "wm density " + density : "wm density reset";
        int largerDensity = density + density / 4;

        double ratio;
        try {
            WebViewActivityScenarioHelper.shell("wm density " + largerDensity);
            ratio = waitForDevicePixelRatio(scenario, webViewClient, largerDensity / 160.0);
        } finally {
            WebViewActivityScenarioHelper.shell(restore);
        }
        assertEquals("The page did not get the new display size", largerDensity / 160.0, ratio, 0.01);
        assertEquals("The page did not get the display size back", density / 160.0,
                     waitForDevicePixelRatio(scenario, webViewClient, density / 160.0), 0.01);
    }

    /**
     * Pages get the languages of the device, and not the "C" of the locale of the process: the first one in
     * navigator.languages, and all of them, in order, in the Accept-Language header.
     */
    @Test
    public void testPagesGetTheDeviceLanguages() throws Throwable {
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicReference<String[]> deviceLanguages = new AtomicReference<>();
        scenario.onActivity(activity
                            -> deviceLanguages.set(PreferredLanguages.toLanguageTags(
                                activity.getApplicationContext().getResources().getConfiguration().getLocales())));

        HttpServer httpServer = HttpServer.start();
        try {
            HttpResponse response = new HttpResponse();
            response.setStatusCode(200);
            response.setStatusMessage("OK");
            response.addHeader("Content-Type", "text/html; charset=utf-8");
            response.setBody("<html><body></body></html>");
            String url = httpServer.setResponse("/languages", response);

            TestWebViewClient webViewClient = new TestWebViewClient();
            WebViewActivityScenarioHelper.loadUrlSync(scenario, webViewClient, url);

            // WebKit shows pages only the first language, for privacy.
            assertEquals("\"" + deviceLanguages.get()[0] + "\"",
                         WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(
                             scenario, webViewClient, "navigator.languages.join(',')"));

            HttpRequest request = httpServer.getLastRequest("/languages");
            assertNotNull("The page was not requested", request);
            String acceptLanguage = null;
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Accept-Language"))
                    acceptLanguage = header.getValue();
            }
            assertNotNull("The request had no Accept-Language header", acceptLanguage);
            // The header has them all, in order, each after the first with a lower quality value.
            List<String> headerLanguages = new ArrayList<>();
            for (String language : acceptLanguage.split(","))
                headerLanguages.add(language.split(";")[0].trim());
            assertEquals("Unexpected Accept-Language header: " + acceptLanguage, Arrays.asList(deviceLanguages.get()),
                         headerLanguages);
        } finally {
            httpServer.shutdown();
        }
    }

    // The locales with another first day of the week on the first one, a regional preference that names no language.
    private static LocaleList withFirstDayOfWeek(LocaleList locales) {
        Locale[] result = new Locale[locales.size()];
        for (int i = 0; i < locales.size(); i++)
            result[i] = locales.get(i);
        // A day other than the current one, so that the locales change.
        String day = "mon".equals(result[0].getUnicodeLocaleType("fw")) ? "tue" : "mon";
        result[0] = new Locale.Builder().setLocale(result[0]).setUnicodeLocaleKeyword("fw", day).build();
        return new LocaleList(result);
    }

    /**
     * A new language for the app reaches the page already loaded, without a reload, as when the user changes the
     * languages of the device. A change of regional preferences alone, such as the first day of the week, does not
     * tell pages that the languages changed.
     */
    @Test
    public void testLanguageChangeReachesThePage() throws Throwable {
        assumeTrue("Languages per app need Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);
        ActivityScenario<WebViewTestActivity> scenario = webViewActivityTestRule.getScenario();
        scenario.moveToState(Lifecycle.State.RESUMED);
        TestWebViewClient webViewClient = new TestWebViewClient();
        WebViewActivityScenarioHelper.loadHtmlSync(
            scenario, webViewClient,
            "<html><body><script>var languageChanges = 0; addEventListener('languagechange', () => languageChanges++);"
                + "</script></body></html>");
        String language = WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, webViewClient,
                                                                                           "navigator.language");
        // A language other than the current one, so that the page sees a change.
        String newLanguage = language.equals("\"fr-CA\"") ? "de-AT" : "fr-CA";

        Context appContext = ApplicationProvider.getApplicationContext();
        LocaleManager localeManager = appContext.getSystemService(LocaleManager.class);
        LocaleList appLocales = localeManager.getApplicationLocales();
        String changedLanguage;
        String languageChanges;
        try {
            LocaleList regionalChange = withFirstDayOfWeek(appContext.getResources().getConfiguration().getLocales());
            localeManager.setApplicationLocales(regionalChange);
            for (int i = 0; i < 100; i++) {
                if (appContext.getResources().getConfiguration().getLocales().get(0).equals(regionalChange.get(0)))
                    break;
                Thread.sleep(100);
            }
            localeManager.setApplicationLocales(LocaleList.forLanguageTags(newLanguage));
            changedLanguage = WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "navigator.language",
                                                                                    "\"" + newLanguage + "\"");
            languageChanges = WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "languageChanges", "1");
        } finally {
            localeManager.setApplicationLocales(appLocales);
        }
        assertEquals("The page did not get the new language", "\"" + newLanguage + "\"", changedLanguage);
        assertEquals("The page heard of a language change when only a regional preference changed", "1",
                     languageChanges);
        assertEquals("The page did not get its language back", language,
                     WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "navigator.language", language));
    }
}
