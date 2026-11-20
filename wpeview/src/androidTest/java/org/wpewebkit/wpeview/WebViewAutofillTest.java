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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.UiAutomation;
import android.net.Uri;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.httpserver.HttpServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.TIMEOUT_SECONDS;
import static org.wpewebkit.wpeview.WebPageTestHelper.evaluate;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadUrl;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.servePage;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitFor;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitForResult;

/**
 * The login fields of a page reach Android's Autofill, with {@link TestAutofillService} set as the device's password
 * service while each test runs.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewAutofillTest {
    // Counts the input events the page gets, as a page that reads its fields from them does.
    private static final String LOGIN_FORM = "<!doctype html><meta name=viewport content='width=device-width'>"
                                             + "<script>var inputs = 0;"
                                             + "addEventListener('input', () => inputs++, true);</script>"
                                             + "<form action='/welcome' method='get'>"
                                             + "<input name=user autocomplete=username>"
                                             + "<input name=pass type=password autocomplete=current-password>"
                                             + "<button>Sign in</button></form>";
    private static final String WELCOME = "<!doctype html><title>Welcome</title>";
    private static final String SAVE_BUTTON_ID = "android:id/autofill_save_yes";
    // Cancels the submit and sends the fields itself, as many sign in pages do, and stays.
    private static final String SENT_BY_THE_PAGE = "<script>document.forms[0].addEventListener('submit', event => {"
                                                   + "event.preventDefault(); fetch('/welcome'); });</script>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private Activity activity;
    private WebContext context;
    private WebView view;
    private HttpServer server;
    private String previousService;
    private final List<WebPage> pages = new ArrayList<>();
    private final Map<WebPage, WebPageTestHelper.RecordingClient> clients = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        activityRule.getScenario().moveToState(Lifecycle.State.RESUMED);
        activityRule.getScenario().onActivity(resumed -> activity = resumed);
        AutofillManager manager = activity.getSystemService(AutofillManager.class);
        assumeTrue("The device has no Autofill", manager != null && manager.isAutofillSupported());

        TestAutofillService.reset();
        previousService = WebViewActivityScenarioHelper.shell("settings get secure autofill_service").trim();
        String service = InstrumentationRegistry.getInstrumentation().getContext().getPackageName() + "/" +
                         TestAutofillService.class.getName();
        WebViewActivityScenarioHelper.shell("settings put secure autofill_service " + service);
        waitFor("Autofill did not take the test service", manager::isEnabled);

        server = HttpServer.start();
        servePage(server, "/welcome", WELCOME);
        onMain(() -> {
            context = new WebContext(activity, false, true);
            view = WebView.createEmpty(activity);
            activity.setContentView(view);
        });
    }

    @After
    public void tearDown() throws Exception {
        if (activity == null)
            return;
        onMain(() -> {
            if (view != null)
                view.destroy();
            for (WebPage page : pages)
                page.destroy();
            pages.clear();
            if (context != null)
                context.destroy();
        });
        if (server != null)
            server.shutdown();
        if (previousService == null)
            return;
        if (previousService.isEmpty() || previousService.equals("null"))
            WebViewActivityScenarioHelper.shell("settings delete secure autofill_service");
        else
            WebViewActivityScenarioHelper.shell("settings put secure autofill_service " + previousService);
        TestAutofillService.reset();
    }

    private WebPage showPage(String path, String html) throws InterruptedException {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        clients.put(page, client);
        onMain(() -> view.setPage(page));
        loadUrl(page, client, servePage(server, path, html));
        waitFor("The page did not tell of its login fields", () -> page.autofill.getFields().size() == 2);
        return page;
    }

    private void focus(WebPage page, String name) throws InterruptedException {
        onMain(() -> view.requestFocus());
        evaluate(page, "document.querySelector('[name=" + name + "]').focus({preventScroll: true}); true");
    }

    // As the user would type it, with the input event that the page hears.
    private static void type(WebPage page, String name, String value) throws InterruptedException {
        evaluate(page, "(field => { field.value = '" + value + "';"
                           + "field.dispatchEvent(new Event('input', {bubbles: true})); return true; })"
                           + "(document.querySelector('[name=" + name + "]'))");
    }

    private static TestAutofillService.Request nextFillRequest() throws InterruptedException {
        TestAutofillService.Request request = TestAutofillService.fillRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The password service was not asked to fill", request);
        return request;
    }

    // On the main thread.
    private static PageAutofill.Field fieldWithRoleOnMain(WebPage page, String role) {
        for (PageAutofill.Field field : page.autofill.getFields()) {
            if (field.role.equals(role))
                return field;
        }
        return null;
    }

    private static PageAutofill.Field fieldWithRole(WebPage page, String role) {
        return onMainGet(() -> fieldWithRoleOnMain(page, role));
    }

    private static UiAutomation windowsAutomation() {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        return automation;
    }

    private static List<AccessibilityNodeInfo> saveButtons(UiAutomation automation) {
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null)
                buttons.addAll(root.findAccessibilityNodeInfosByViewId(SAVE_BUTTON_ID));
        }
        return buttons;
    }

    // Taps Save on Android's offer to save, if it shows within the time, and waits until the offer is gone. Returns
    // whether it showed.
    private static boolean tapSave(long timeoutMs) throws InterruptedException {
        UiAutomation automation = windowsAutomation();
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        boolean tapped = false;
        do {
            for (AccessibilityNodeInfo button : saveButtons(automation))
                tapped |= button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!tapped)
                Thread.sleep(100);
        } while (!tapped && SystemClock.uptimeMillis() < deadline);
        if (!tapped)
            return false;
        long goneDeadline = SystemClock.uptimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!saveButtons(automation).isEmpty()) {
            assertTrue("The offer to save stayed", SystemClock.uptimeMillis() < goneDeadline);
            Thread.sleep(100);
        }
        return true;
    }

    private static TestAutofillService.Request nextSaveRequest() throws InterruptedException {
        TestAutofillService.Request save = TestAutofillService.saveRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The password service was not asked to save", save);
        return save;
    }

    @Test
    public void thePasswordServiceGetsTheFieldsAndTheirSite() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        TestAutofillService.Request request = nextFillRequest();

        String host = Uri.parse(onMainGet(page::getUrl)).getHost();
        assertEquals("http", request.webScheme);
        assertEquals(host, request.webDomain);
        TestAutofillService.Field user = request.withHint(View.AUTOFILL_HINT_USERNAME);
        assertNotNull(user);
        assertEquals("user", user.attributes.get("name"));
        assertEquals("username", user.attributes.get("autocomplete"));
        assertTrue(user.focused);
        TestAutofillService.Field password = request.withHint(View.AUTOFILL_HINT_PASSWORD);
        assertNotNull(password);
        assertEquals("password", password.attributes.get("type"));
        assertFalse(password.focused);
    }

    @Test
    public void fillingSetsTheFieldsAsTyped() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();

        SparseArray<AutofillValue> values = new SparseArray<>();
        values.put(fieldWithRole(page, PageAutofill.ROLE_USERNAME).virtualId, AutofillValue.forText("test-user"));
        values.put(fieldWithRole(page, PageAutofill.ROLE_PASSWORD).virtualId, AutofillValue.forText("test-secret"));
        onMain(() -> view.autofill(values));

        assertEquals("\"test-user\"", waitForResult(page, "document.forms[0].user.value", "\"test-user\""));
        assertEquals("\"test-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"test-secret\""));
        assertEquals("true", evaluate(page, "document.forms[0].user.matches(':-webkit-autofill')"));
        assertEquals("true", evaluate(page, "inputs >= 2"));
    }

    private void assertSignInOffersToSave(String html, String submit) throws Exception {
        WebPage page = showPage("/login", html);
        focus(page, "user");
        nextFillRequest();
        type(page, "user", "typed-user");
        type(page, "pass", "typed-secret");
        evaluate(page, submit + "; true");

        assertTrue("Android did not offer to save", tapSave(TIMEOUT_SECONDS * 1000));
        TestAutofillService.Request save = nextSaveRequest();
        assertEquals("typed-user", save.withHint(View.AUTOFILL_HINT_USERNAME).value);
        assertEquals("typed-secret", save.withHint(View.AUTOFILL_HINT_PASSWORD).value);
        // One sign in, one offer, though WebKit tells of both the submit event and the form being sent.
        assertFalse("Android offered to save twice", tapSave(2000));
        assertNull(TestAutofillService.saveRequests.poll(1, TimeUnit.SECONDS));
    }

    @Test
    public void signingInOffersToSaveOnce() throws Exception {
        assertSignInOffersToSave(LOGIN_FORM, "document.forms[0].requestSubmit()");
    }

    @Test
    public void signingInFromAScriptOffersToSave() throws Exception {
        assertSignInOffersToSave(LOGIN_FORM, "document.forms[0].submit()");
    }

    @Test
    public void signingInThatThePageSendsItselfOffersToSave() throws Exception {
        assertSignInOffersToSave(LOGIN_FORM + SENT_BY_THE_PAGE, "document.forms[0].requestSubmit()");
    }

    // As when the page refused the password, and the user types another one in the same field.
    @Test
    public void typingAgainAfterASignInOffersToSaveAgain() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM + SENT_BY_THE_PAGE);
        focus(page, "user");
        nextFillRequest();
        type(page, "user", "typed-user");
        focus(page, "pass");
        type(page, "pass", "typed-secret");
        evaluate(page, "document.forms[0].requestSubmit(); true");
        assertTrue("Android did not offer to save", tapSave(TIMEOUT_SECONDS * 1000));
        assertEquals("typed-secret", nextSaveRequest().withHint(View.AUTOFILL_HINT_PASSWORD).value);

        type(page, "pass", "second-secret");
        // Typing starts a new session, whose password service must have answered before Android can offer to save,
        // as it has by the time a user signs in.
        nextFillRequest();
        evaluate(page, "document.forms[0].requestSubmit(); true");
        assertTrue("Android did not offer to save the second try", tapSave(TIMEOUT_SECONDS * 1000));
        assertEquals("second-secret", nextSaveRequest().withHint(View.AUTOFILL_HINT_PASSWORD).value);
    }

    @Test
    public void aPageShownAgainFromTheBackForwardCacheGetsAutofill() throws Exception {
        WebPage page = showPage(
            "/login",
            LOGIN_FORM + "<script>addEventListener('pageshow', e => window.restored = e.persisted);</script>");
        WebPageTestHelper.RecordingClient client = clients.get(page);
        loadUrl(page, client, servePage(server, "/next", "<!doctype html><title>Next</title>"));
        waitFor("The next page did not replace the login fields", () -> page.autofill.getFields().isEmpty());

        client.finishedUrls.clear();
        onMain(page::goBack);
        client.waitForFinishedLoad();
        // WebKit may empty the cache, as when memory runs low.
        assumeTrue("The page did not come from the back and forward cache",
                   "true".equals(waitForResult(page, "window.restored === true", "true")));
        waitFor("The page shown again did not tell of its login fields", () -> page.autofill.getFields().size() == 2);
        focus(page, "user");
        assertNotNull(nextFillRequest().withHint(View.AUTOFILL_HINT_USERNAME));
    }

    // Messages come through the web context, which hears every web process. One that does not carry the key that
    // only the web process of the page got, or that names another site, changes nothing.
    @Test
    public void messagesThatThePageDidNotSendChangeNothing() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        String origin = onMainGet(() -> page.getWebKitWebView().getDocumentOrigin());
        String forgedField = "{\"id\":1,\"role\":\"username\",\"name\":\"forged\",\"value\":\"\"}";
        onMain(() -> {
            page.autofill.onMessage("fields", "{\"doc\":\"forged\",\"key\":\"\",\"origin\":\"" + origin +
                                                  "\",\"fields\":[" + forgedField + "]}");
            page.autofill.onMessage("fields", "{\"doc\":\"forged\",\"key\":\"guess\",\"origin\":\"" + origin +
                                                  "\",\"fields\":[" + forgedField + "]}");
            page.autofill.onMessage("fields", "{\"doc\":\"forged\",\"key\":\"\",\"origin\":\"https://bank.example\","
                                                  + "\"fields\":[" + forgedField + "]}");
            page.autofill.onMessage("focus",
                                    "{\"doc\":\"forged\",\"key\":\"guess\",\"origin\":\"" + origin + "\",\"id\":1}");
        });
        Thread.sleep(2000);
        List<String> names = onMainGet(() -> {
            List<String> fieldNames = new ArrayList<>();
            for (PageAutofill.Field field : page.autofill.getFields())
                fieldNames.add(field.name);
            return fieldNames;
        });
        assertEquals(List.of("user", "pass"), names);
        assertNull("The password service was asked to fill",
                   TestAutofillService.fillRequests.poll(1, TimeUnit.SECONDS));
    }

    // An app turns Autofill off this way, for example for private pages. Android must not even hear of the fields, as
    // it would offer to save what it heard when the activity finishes.
    @Test
    public void aViewNotImportantForAutofillTellsAndroidNothing() throws Exception {
        onMain(() -> view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS));
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        type(page, "user", "typed-user");
        type(page, "pass", "typed-secret");
        evaluate(page, "document.forms[0].requestSubmit(); true");

        assertFalse("Android offered to save", tapSave(3000));
        onMain(() -> activity.finish());
        assertFalse("Android offered to save when the activity finished", tapSave(3000));
        assertNull("The password service was asked to fill",
                   TestAutofillService.fillRequests.poll(1, TimeUnit.SECONDS));
        assertNull(TestAutofillService.saveRequests.poll(1, TimeUnit.SECONDS));
    }

    // Android puts its list of accounts under the field, in the view, also when the page is scrolled.
    @Test
    public void theFieldsArePlacedWhereTheyAreAfterScrolling() throws Exception {
        WebPage page = showPage("/login", "<div style='height: 2000px'></div>" + LOGIN_FORM);
        int topBefore = fieldWithRole(page, PageAutofill.ROLE_USERNAME).rect.top;
        evaluate(page, "scrollTo(0, 1000); true");
        waitFor("The page did not tell where its fields moved",
                () -> fieldWithRoleOnMain(page, PageAutofill.ROLE_USERNAME).rect.top != topBefore);
        focus(page, "user");
        TestAutofillService.Request request = nextFillRequest();

        int expectedTop = Integer.parseInt(
            evaluate(page, "Math.round(document.forms[0].user.getBoundingClientRect().top * devicePixelRatio)"));
        int expectedLeft = Integer.parseInt(
            evaluate(page, "Math.round(document.forms[0].user.getBoundingClientRect().left * devicePixelRatio)"));
        TestAutofillService.Field user = request.withHint(View.AUTOFILL_HINT_USERNAME);
        assertNotNull(user);
        assertTrue("The field is at " + user.top + " instead of " + expectedTop, Math.abs(user.top - expectedTop) <= 2);
        assertTrue("The field is at " + user.left + " instead of " + expectedLeft,
                   Math.abs(user.left - expectedLeft) <= 2);
    }

    // Android keeps one session for the activity, which would otherwise mix the fields of both pages.
    @Test
    public void anotherPageInTheViewStartsAnotherSession() throws Exception {
        WebPage first = showPage("/login", LOGIN_FORM);
        focus(first, "user");
        assertEquals(1, nextFillRequest().contextCount);
        Set<Integer> firstIds = new HashSet<>();
        for (PageAutofill.Field field : onMainGet(first.autofill::getFields))
            firstIds.add(field.virtualId);

        WebPage second = showPage("/other", LOGIN_FORM);
        focus(second, "user");
        assertEquals("The session of the first page went on", 1, nextFillRequest().contextCount);
        for (PageAutofill.Field field : onMainGet(second.autofill::getFields))
            assertFalse("A field id was given again", firstIds.contains(field.virtualId));
    }
}
