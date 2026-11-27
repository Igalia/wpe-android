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
import android.app.Dialog;
import android.app.UiAutomation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

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
import static org.wpewebkit.wpeview.WebPageTestHelper.loadHtml;
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
        return showPage(path, html, WebSettings.AUTOFILL_FILL_AND_SAVE);
    }

    private WebPage showPage(String path, String html, @WebSettings.AutofillMode int mode) throws InterruptedException {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        clients.put(page, client);
        onMain(() -> {
            page.getSettings().setAutofillMode(mode);
            view.setPage(page);
        });
        loadUrl(page, client, servePage(server, path, html));
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

    // Focuses the field and types in it, and waits until the view has its value.
    private void typeInto(WebPage page, String name, int role, String value) throws InterruptedException {
        focus(page, name);
        type(page, name, value);
        waitFor("The field did not tell of its value", () -> {
            PageAutofill.Field field = fieldWithRoleOnMain(page, role);
            return field != null && field.value.equals(value);
        });
    }

    // What the view gives Android's Autofill, which Android keeps, though it strips the values of sensitive fields for
    // the password service. On the main thread.
    private RecordingViewStructure structureOnMain() {
        RecordingViewStructure structure = new RecordingViewStructure();
        structure.setAutofillId(view.getAutofillId());
        view.onProvideAutofillVirtualStructure(structure, 0);
        return structure;
    }

    private String valueGiven(WebPage page, int role) {
        return onMainGet(() -> {
            RecordingViewStructure child =
                structureOnMain().childWithVirtualId(fieldWithRoleOnMain(page, role).virtualId);
            assertNotNull(child);
            return child.autofillValue.getTextValue().toString();
        });
    }

    private static TestAutofillService.Request nextFillRequest() throws InterruptedException {
        TestAutofillService.Request request = TestAutofillService.fillRequests.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("The password service was not asked to fill", request);
        return request;
    }

    // On the main thread.
    private static PageAutofill.Field fieldWithRoleOnMain(WebPage page, int role) {
        for (PageAutofill.Field field : page.autofill.getFields()) {
            if (field.role == role)
                return field;
        }
        return null;
    }

    private static PageAutofill.Field fieldWithRole(WebPage page, int role) {
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

    // Taps the account that the password service offers in Android's list, as the user picks it, if the list shows
    // within the time. Returns whether it showed.
    private static boolean tapAccount(long timeoutMs) throws InterruptedException {
        UiAutomation automation = windowsAutomation();
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null)
                    continue;
                for (AccessibilityNodeInfo node :
                     root.findAccessibilityNodeInfosByText(TestAutofillService.ACCOUNT_LABEL)) {
                    for (AccessibilityNodeInfo target = node; target != null; target = target.getParent()) {
                        if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            return true;
                    }
                }
            }
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    // Waits until Android's list shows the account that the password service offers, or until it is gone.
    private static void waitForAccountList(boolean shown) throws InterruptedException {
        UiAutomation automation = windowsAutomation();
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_SECONDS * 1000;
        while (true) {
            boolean found = false;
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && !root.findAccessibilityNodeInfosByText(TestAutofillService.ACCOUNT_LABEL).isEmpty())
                    found = true;
            }
            if (found == shown)
                return;
            assertTrue(shown ? "Android did not show the account" : "Android's list stayed",
                       SystemClock.uptimeMillis() < deadline);
            Thread.sleep(100);
        }
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

    private void assertPickingTheAccountFills(boolean authenticate, @WebSettings.AutofillMode int mode)
        throws Exception {
        TestAutofillService.accountUser = "saved-user";
        TestAutofillService.accountPassword = "saved-secret";
        TestAutofillService.authenticate = authenticate;
        WebPage page = showPage("/login", LOGIN_FORM, mode);
        focus(page, "user");
        nextFillRequest();

        assertTrue("Android did not show the account", tapAccount(TIMEOUT_SECONDS * 1000));
        assertEquals("\"saved-user\"", waitForResult(page, "document.forms[0].user.value", "\"saved-user\""));
        assertEquals("\"saved-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"saved-secret\""));
        assertEquals("true", waitForResult(page, "document.hasFocus()", "true"));
    }

    // The user picks the account in Android's list, a window that takes the window focus.
    @Test
    public void pickingAnAccountFills() throws Exception {
        assertPickingTheAccountFills(false, WebSettings.AUTOFILL_FILL_AND_SAVE);
    }

    // The password service unlocks the account in an activity of its own, as one that asks for the screen lock does,
    // and the fill can come before the page has the focus back.
    @Test
    public void pickingAnAccountBehindAuthenticationFills() throws Exception {
        assertPickingTheAccountFills(true, WebSettings.AUTOFILL_FILL_AND_SAVE);
    }

    @Test
    public void pickingAnAccountFillsInFillOnlyMode() throws Exception {
        assertPickingTheAccountFills(false, WebSettings.AUTOFILL_FILL_ONLY);
    }

    // Shows a dialog, which takes the window focus and the input method, and so blurs the page, as the screen lock
    // prompt of a password service does, and waits until the view heard of the blur.
    private Dialog blurWithADialog(WebPage page) throws InterruptedException {
        Dialog dialog = onMainGet(() -> {
            Dialog shown = new Dialog(activity);
            shown.setContentView(new View(activity));
            shown.show();
            return shown;
        });
        assertEquals("true", waitForResult(page, "!document.hasFocus()", "true"));
        // WebKit tells of the login form before it answers a later script.
        evaluate(page, "true");
        return dialog;
    }

    // The field keeps the focus of its document while the page is blurred, so it keeps its login form, and a fill
    // lands.
    @Test
    public void aBlurredPageKeepsTheFormOfTheFocusedField() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        Dialog dialog = blurWithADialog(page);
        try {
            assertNotNull("The field lost its login form", onMainGet(() -> page.autofill.getFocusedField()));
            SparseArray<AutofillValue> values = new SparseArray<>();
            values.put(fieldWithRole(page, PageAutofill.ROLE_USERNAME).virtualId, AutofillValue.forText("test-user"));
            values.put(fieldWithRole(page, PageAutofill.ROLE_PASSWORD).virtualId, AutofillValue.forText("test-secret"));
            onMain(() -> view.autofill(values));
            assertEquals("\"test-user\"", waitForResult(page, "document.forms[0].user.value", "\"test-user\""));
            assertEquals("\"test-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"test-secret\""));
        } finally {
            onMain(dialog::dismiss);
        }
    }

    // A field that leaves its page while the page is blurred reports no blur, and loses its login form when the page
    // has the focus back.
    @Test
    public void aFieldRemovedWhileThePageIsBlurredLosesItsForm() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        Dialog dialog = blurWithADialog(page);
        try {
            evaluate(page, "document.forms[0].remove(); true");
        } finally {
            onMain(dialog::dismiss);
        }
        assertEquals("true", waitForResult(page, "document.hasFocus()", "true"));
        waitFor("The removed field kept its login form", () -> page.autofill.getFocusedField() == null);
    }

    // Android's list of accounts takes the window focus but leaves the input method to the view, as for its own text
    // fields, so the page keeps the focus while it shows, and the keyboard can stay up.
    @Test
    public void thePageKeepsTheFocusWhileAndroidShowsItsList() throws Exception {
        TestAutofillService.accountUser = "saved-user";
        TestAutofillService.accountPassword = "saved-secret";
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        waitForAccountList(true);
        waitFor("The list did not take the window focus", () -> !activity.hasWindowFocus());

        Thread.sleep(1000);
        assertEquals("true", evaluate(page, "document.hasFocus()"));
        assertTrue(onMainGet(() -> view.isInputFieldFocused()));
    }

    // A window that is not the list, as a dialog that the app opens over it, takes the focus of the page.
    @Test
    public void aDialogOverAndroidsListBlursThePage() throws Exception {
        TestAutofillService.accountUser = "saved-user";
        TestAutofillService.accountPassword = "saved-secret";
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        waitForAccountList(true);
        waitFor("The list did not take the window focus", () -> !activity.hasWindowFocus());
        assertEquals("true", evaluate(page, "document.hasFocus()"));

        Dialog dialog = onMainGet(() -> {
            Dialog shown = new Dialog(activity);
            shown.setContentView(new View(activity));
            shown.show();
            return shown;
        });
        try {
            assertEquals("true", waitForResult(page, "!document.hasFocus()", "true"));
        } finally {
            onMain(dialog::dismiss);
        }
    }

    // An activity that comes up over the list, as the unlock of a password service does, takes the focus of the page.
    @Test
    public void anActivityOverAndroidsListBlursThePage() throws Exception {
        TestAutofillService.accountUser = "saved-user";
        TestAutofillService.accountPassword = "saved-secret";
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        waitForAccountList(true);
        waitFor("The list did not take the window focus", () -> !activity.hasWindowFocus());
        assertEquals("true", evaluate(page, "document.hasFocus()"));

        onMain(() -> activity.startActivityForResult(new Intent(activity, WebViewFragmentTestActivity.class), 1));
        try {
            assertEquals("true", waitForResult(page, "!document.hasFocus()", "true"));
        } finally {
            onMain(() -> activity.finishActivity(1));
        }
    }

    // Another view of the app takes the focus, as the address field of a browser does, and gives it back: the field
    // kept the focus of its page, so it is entered again, and the password service offers its account again.
    @Test
    public void theFieldIsEnteredAgainWhenTheViewGetsTheFocusBack() throws Exception {
        TestAutofillService.accountUser = "saved-user";
        TestAutofillService.accountPassword = "saved-secret";
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        waitForAccountList(true);

        onMain(() -> {
            EditText other = new EditText(activity);
            activity.addContentView(other, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                      ViewGroup.LayoutParams.WRAP_CONTENT));
            other.requestFocus();
        });
        waitForAccountList(false);
        onMain(() -> view.requestFocus());

        assertTrue("Android did not show the account again", tapAccount(TIMEOUT_SECONDS * 1000));
        assertEquals("\"saved-user\"", waitForResult(page, "document.forms[0].user.value", "\"saved-user\""));
        assertEquals("\"saved-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"saved-secret\""));
    }

    // A script that blurs the field while the page is blurred takes its login form away at once.
    @Test
    public void aFieldBlurredWhileThePageIsBlurredLosesItsForm() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        Dialog dialog = blurWithADialog(page);
        try {
            evaluate(page, "document.forms[0].user.blur(); true");
            waitFor("The blurred field kept its login form", () -> page.autofill.getFocusedField() == null);
        } finally {
            onMain(dialog::dismiss);
        }
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
        // One sign in, one offer, though it goes through both the submit event and the sending of the form.
        assertFalse("Android offered to save twice", tapSave(2000));
        assertNull(TestAutofillService.saveRequests.poll(1, TimeUnit.SECONDS));
    }

    @Test
    public void signingInOffersToSaveOnce() throws Exception {
        assertSignInOffersToSave(LOGIN_FORM, "document.forms[0].requestSubmit()");
    }

    // Many sign in pages have a button that shows the password, which turns its field into a text field.
    @Test
    public void signingInWithThePasswordShownOffersToSave() throws Exception {
        assertSignInOffersToSave(LOGIN_FORM, "document.forms[0].pass.type = 'text'; document.forms[0].requestSubmit()");
    }

    @Test
    public void aShownPasswordKeepsItsLoginForm() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "pass");
        nextFillRequest();
        evaluate(page, "document.forms[0].pass.type = 'text'; true");
        // WebKit tells of the login form before it answers a later script.
        evaluate(page, "true");

        PageAutofill.Field focused = onMainGet(() -> page.autofill.getFocusedField());
        assertNotNull("The shown password lost its login form", focused);
        assertEquals(PageAutofill.ROLE_PASSWORD, focused.role);
        SparseArray<AutofillValue> values = new SparseArray<>();
        values.put(fieldWithRole(page, PageAutofill.ROLE_USERNAME).virtualId, AutofillValue.forText("test-user"));
        values.put(focused.virtualId, AutofillValue.forText("test-secret"));
        onMain(() -> view.autofill(values));
        assertEquals("\"test-user\"", waitForResult(page, "document.forms[0].user.value", "\"test-user\""));
        assertEquals("\"test-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"test-secret\""));
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

        // The user types the last letter through the keyboard, which WebKit tells of as typing.
        type(page, "pass", "second-secre");
        waitFor("The field did not become a text editor", view::onCheckIsTextEditor);
        onMain(() -> view.onCreateInputConnection(new EditorInfo()).commitText("t", 1));
        assertEquals("\"second-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"second-secret\""));
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
        focus(page, "user");
        nextFillRequest();
        WebPageTestHelper.RecordingClient client = clients.get(page);
        loadUrl(page, client, servePage(server, "/next", "<!doctype html><title>Next</title>"));
        waitFor("The next page did not replace the login fields", () -> page.autofill.getFields().isEmpty());

        client.finishedUrls.clear();
        onMain(page::goBack);
        client.waitForFinishedLoad();
        // WebKit may empty the cache, as when memory runs low.
        assumeTrue("The page did not come from the back and forward cache",
                   "true".equals(waitForResult(page, "window.restored === true", "true")));
        focus(page, "user");
        assertNotNull(nextFillRequest().withHint(View.AUTOFILL_HINT_USERNAME));
    }

    // The site that Android gets is the one of the frame of the form, as WebKit's UI process knows it.
    @Test
    public void aLoginFormInAFrameTellsTheSiteOfTheFrame() throws Exception {
        String frameUrl = servePage(server, "/frame",
                                    LOGIN_FORM + "<script>addEventListener('message', () => "
                                        + "document.forms[0].user.focus({preventScroll: true}));</script>")
                              .replace("//localhost:", "//127.0.0.1:");
        WebPage page =
            showPage("/page", "<!doctype html><iframe src='" + frameUrl + "' onload='window.loaded = true'></iframe>");
        assertEquals("true", waitForResult(page, "window.loaded === true", "true"));
        onMain(() -> view.requestFocus());
        // A page cannot reach into a frame of another site, so the frame focuses its field itself.
        evaluate(page, "frames[0].postMessage('focus', '*'); true");
        TestAutofillService.Request request = nextFillRequest();
        assertEquals("127.0.0.1", request.webDomain);
        assertNotNull(request.withHint(View.AUTOFILL_HINT_USERNAME));
    }

    // Only pages on the web get Autofill: a page that the app loads from a string has no site to check.
    @Test
    public void aLoginFormOfAPageNotOnTheWebTellsAndroidNothing() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = newPage(context, client);
        pages.add(page);
        onMain(() -> view.setPage(page));
        loadHtml(page, client, LOGIN_FORM);
        focus(page, "user");
        assertNull("The password service was asked to fill",
                   TestAutofillService.fillRequests.poll(3, TimeUnit.SECONDS));
        assertTrue(onMainGet(page.autofill::getFields).isEmpty());
    }

    // An app turns Autofill off for a whole view this way. Android must not even hear of the fields, as it would offer
    // to save what it heard when the activity finishes.
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
        // What the user typed stayed in the web process: WebKit told of no login form.
        assertTrue(onMainGet(page.autofill::getFields).isEmpty());
    }

    // An app turns Autofill off for a page this way. What the user types stays in the web process.
    @Test
    public void aPageWithAutofillOffTellsAndroidNothing() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM, WebSettings.AUTOFILL_OFF);
        assertFalse(onMainGet(page::isLoginFormAutofillEnabled));
        focus(page, "user");
        type(page, "user", "typed-user");
        type(page, "pass", "typed-secret");
        evaluate(page, "document.forms[0].requestSubmit(); true");

        assertFalse("Android offered to save", tapSave(3000));
        assertNull("The password service was asked to fill",
                   TestAutofillService.fillRequests.poll(1, TimeUnit.SECONDS));
        assertNull(TestAutofillService.saveRequests.poll(1, TimeUnit.SECONDS));
        assertTrue(onMainGet(page.autofill::getFields).isEmpty());
    }

    // Android commits the session of the activity when it finishes, and offers to save what it heard. The tests of
    // pages that are never offered to save count on it.
    @Test
    public void leavingTheActivityAfterTypingOffersToSave() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        // Android commits the session only once the password service answered.
        focus(page, "user");
        nextFillRequest();
        typeInto(page, "user", PageAutofill.ROLE_USERNAME, "typed-user");
        typeInto(page, "pass", PageAutofill.ROLE_PASSWORD, "typed-secret");
        onMain(() -> activity.finish());

        assertTrue("Android did not offer to save when the activity finished", tapSave(TIMEOUT_SECONDS * 1000));
        TestAutofillService.Request save = nextSaveRequest();
        assertEquals("typed-user", save.withHint(View.AUTOFILL_HINT_USERNAME).value);
        assertEquals("typed-secret", save.withHint(View.AUTOFILL_HINT_PASSWORD).value);
    }

    // As for a private page: the password service fills, but Android never gets the values of the fields, so it has
    // nothing to offer to save, neither when the page signs in nor when the activity finishes.
    @Test
    public void aPageThatOnlyFillsIsNeverOfferedToSave() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM + SENT_BY_THE_PAGE, WebSettings.AUTOFILL_FILL_ONLY);
        focus(page, "user");
        TestAutofillService.Request request = nextFillRequest();
        assertEquals("http", request.webScheme);
        assertNotNull(request.withHint(View.AUTOFILL_HINT_USERNAME));
        assertNotNull(request.withHint(View.AUTOFILL_HINT_PASSWORD));
        SparseArray<AutofillValue> values = new SparseArray<>();
        values.put(fieldWithRole(page, PageAutofill.ROLE_USERNAME).virtualId, AutofillValue.forText("test-user"));
        onMain(() -> view.autofill(values));
        assertEquals("\"test-user\"", waitForResult(page, "document.forms[0].user.value", "\"test-user\""));
        typeInto(page, "pass", PageAutofill.ROLE_PASSWORD, "typed-secret");
        evaluate(page, "document.forms[0].requestSubmit(); true");
        assertFalse("Android offered to save the sign in", tapSave(3000));

        // As when the page refused the password: typing again starts a new session, in which the service offers its
        // accounts again, and which the activity finishing commits.
        type(page, "pass", "second-secre");
        waitFor("The field did not become a text editor", view::onCheckIsTextEditor);
        onMain(() -> view.onCreateInputConnection(new EditorInfo()).commitText("t", 1));
        assertEquals("\"second-secret\"", waitForResult(page, "document.forms[0].pass.value", "\"second-secret\""));
        assertEquals(1, nextFillRequest().contextCount);
        typeInto(page, "user", PageAutofill.ROLE_USERNAME, "typed-user");
        onMain(() -> activity.finish());
        assertFalse("Android offered to save when the activity finished", tapSave(3000));
        assertNull(TestAutofillService.saveRequests.poll(1, TimeUnit.SECONDS));
    }

    // An app may make a page private while it shows it: Android forgets what it heard of the page, and hears again of
    // the field that has the focus, without its value.
    @Test
    public void makingAShownPageFillOnlyAppliesAtOnce() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        typeInto(page, "user", PageAutofill.ROLE_USERNAME, "typed-user");
        typeInto(page, "pass", PageAutofill.ROLE_PASSWORD, "typed-secret");
        onMain(() -> page.getSettings().setAutofillMode(WebSettings.AUTOFILL_FILL_ONLY));

        // A new session, for the field that has the focus.
        TestAutofillService.Request request = nextFillRequest();
        assertEquals(1, request.contextCount);
        assertTrue(request.withHint(View.AUTOFILL_HINT_PASSWORD).focused);
        typeInto(page, "user", PageAutofill.ROLE_USERNAME, "other-user");
        onMain(() -> activity.finish());
        assertFalse("Android offered to save when the activity finished", tapSave(3000));
        assertNull(TestAutofillService.saveRequests.poll(1, TimeUnit.SECONDS));
    }

    // Android offers to save what it heard of the fields, also from a later structure after a fill, so the view gives
    // their values only to a page that may save.
    @Test
    public void onlyAPageThatMaySaveGivesAndroidTheValuesOfItsFields() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        typeInto(page, "user", PageAutofill.ROLE_USERNAME, "typed-user");
        typeInto(page, "pass", PageAutofill.ROLE_PASSWORD, "typed-secret");
        assertEquals("typed-user", valueGiven(page, PageAutofill.ROLE_USERNAME));
        assertEquals("typed-secret", valueGiven(page, PageAutofill.ROLE_PASSWORD));

        onMain(() -> page.getSettings().setAutofillMode(WebSettings.AUTOFILL_FILL_ONLY));
        assertEquals("", valueGiven(page, PageAutofill.ROLE_USERNAME));
        assertEquals("", valueGiven(page, PageAutofill.ROLE_PASSWORD));

        onMain(() -> page.getSettings().setAutofillMode(WebSettings.AUTOFILL_OFF));
        assertEquals(0, (int)onMainGet(() -> structureOnMain().getChildCount()));
    }

    // Turned off and on again for a page that it shows, the view forgets the fields, and WebKit tells again of the
    // login form of the field that has the focus.
    @Test
    public void turningAutofillOffAndOnAgainForAShownPage() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        onMain(() -> page.getSettings().setAutofillMode(WebSettings.AUTOFILL_OFF));
        assertFalse(onMainGet(page::isLoginFormAutofillEnabled));
        assertTrue(onMainGet(page.autofill::getFields).isEmpty());

        onMain(() -> page.getSettings().setAutofillMode(WebSettings.AUTOFILL_FILL_AND_SAVE));
        assertTrue(onMainGet(page::isLoginFormAutofillEnabled));
        assertTrue(nextFillRequest().withHint(View.AUTOFILL_HINT_USERNAME).focused);
    }

    // Android puts its list of accounts under the field, in the view, also when the page is scrolled.
    @Test
    public void theFieldsArePlacedWhereTheyAreAfterScrolling() throws Exception {
        WebPage page = showPage("/login", "<div style='height: 4000px'></div>" + LOGIN_FORM);
        evaluate(page, "scrollTo(0, 1500); true");
        focus(page, "user");
        TestAutofillService.Request request = nextFillRequest();
        TestAutofillService.Field user = request.withHint(View.AUTOFILL_HINT_USERNAME);
        assertNotNull(user);
        assertNear(page, "top", user.top);
        assertNear(page, "left", user.left);

        int topBefore = fieldWithRole(page, PageAutofill.ROLE_USERNAME).rect.top;
        evaluate(page, "scrollBy(0, 100); true");
        waitFor("The page did not tell where its fields moved",
                () -> fieldWithRoleOnMain(page, PageAutofill.ROLE_USERNAME).rect.top != topBefore);
        assertNear(page, "top", fieldWithRole(page, PageAutofill.ROLE_USERNAME).rect.top);
    }

    private static void assertNear(WebPage page, String side, int actual) throws InterruptedException {
        int expected = Integer.parseInt(evaluate(page, "Math.round(document.forms[0].user.getBoundingClientRect()." +
                                                           side + " * devicePixelRatio)"));
        assertTrue("The field's " + side + " is at " + actual + " instead of " + expected,
                   Math.abs(actual - expected) <= 2);
    }

    // The focus order goes into frames, but a field of another document never joins a login form: filling it would give
    // the logins of the page to the site of the frame.
    @Test
    public void aFieldOfAnotherDocumentIsNotPartOfTheForm() throws Exception {
        String frameUrl = servePage(server, "/frame", "<!doctype html><input name=pass type=password>")
                              .replace("//localhost:", "//127.0.0.1:");
        WebPage page = showPage("/page", "<!doctype html><input name=search><iframe src='" + frameUrl +
                                             "' onload='window.loaded = true'></iframe>");
        assertEquals("true", waitForResult(page, "window.loaded === true", "true"));
        focus(page, "search");
        assertNull("The password service was asked to fill",
                   TestAutofillService.fillRequests.poll(3, TimeUnit.SECONDS));
        assertTrue(onMainGet(page.autofill::getFields).isEmpty());
    }

    // A page tells WebKit nothing of its login forms once it leaves the view, nor while a view that does not take part
    // in Autofill shows it.
    @Test
    public void onlyThePageOfAViewThatTakesPartTellsOfItsLoginForms() throws Exception {
        WebPage first = showPage("/login", LOGIN_FORM);
        assertTrue(onMainGet(first::isLoginFormAutofillEnabled));
        onMain(() -> view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS));
        assertFalse(onMainGet(first::isLoginFormAutofillEnabled));

        // As an app that sets the view important again right before it shows another page.
        WebPage second = newPage(context, new WebPageTestHelper.RecordingClient());
        pages.add(second);
        onMain(() -> {
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            assertFalse("The private page told of its login forms", first.isLoginFormAutofillEnabled());
            view.setPage(second);
        });
        waitFor("The page shown did not tell of its login forms", second::isLoginFormAutofillEnabled);
        assertFalse(onMainGet(first::isLoginFormAutofillEnabled));
    }

    @Test
    public void aPasswordWithCharactersOutsideTheBasicPlaneIsFilled() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "pass");
        nextFillRequest();
        String password = "s3cret \uD83D\uDD11";
        SparseArray<AutofillValue> values = new SparseArray<>();
        values.put(fieldWithRole(page, PageAutofill.ROLE_PASSWORD).virtualId, AutofillValue.forText(password));
        onMain(() -> view.autofill(values));
        assertEquals("true", waitForResult(page, "document.forms[0].pass.value === 's3cret \\u{1F511}'", "true"));
        assertEquals("\"\"", evaluate(page, "document.forms[0].user.value"));
    }

    // WebKit finds the confirmation field of a form to sign up alone, as the field before it is a password field. The
    // fields found from the user name field stay, so the sign in is still offered to save.
    @Test
    public void signingUpAfterTheConfirmationFieldOffersToSave() throws Exception {
        WebPage page = showPage("/signup", "<!doctype html><form action='/welcome' method='get'>"
                                               + "<input name=user autocomplete=username>"
                                               + "<input name=pass type=password autocomplete=new-password>"
                                               + "<input name=confirm type=password autocomplete=new-password>"
                                               + "</form>");
        focus(page, "user");
        nextFillRequest();
        type(page, "user", "new-user");
        type(page, "pass", "new-secret");
        focus(page, "confirm");
        waitFor("The confirmation field did not get the focus", () -> {
            PageAutofill.Field focused = page.autofill.getFocusedField();
            return focused != null && focused.name.equals("confirm");
        });
        type(page, "confirm", "new-secret");
        // As after typing it, Android has the value of the confirmation field by the time the user signs up.
        waitFor("The confirmation field did not tell of its value", () -> {
            PageAutofill.Field focused = page.autofill.getFocusedField();
            return focused != null && focused.value.equals("new-secret");
        });
        evaluate(page, "document.forms[0].requestSubmit(); true");
        assertTrue("Android did not offer to save", tapSave(TIMEOUT_SECONDS * 1000));
        TestAutofillService.Request save = nextSaveRequest();
        assertEquals("new-user", save.withHint(View.AUTOFILL_HINT_USERNAME).value);
        assertEquals("new-secret", save.withHint("newPassword").value);
    }

    // A fill that comes after the field lost the focus fills nothing.
    @Test
    public void aFillAfterTheFieldLostTheFocusFillsNothing() throws Exception {
        WebPage page = showPage("/login", LOGIN_FORM);
        focus(page, "user");
        nextFillRequest();
        SparseArray<AutofillValue> values = new SparseArray<>();
        values.put(fieldWithRole(page, PageAutofill.ROLE_USERNAME).virtualId, AutofillValue.forText("late-user"));
        evaluate(page, "document.activeElement.blur(); true");
        waitFor("The field did not lose the focus", () -> page.autofill.getFocusedField() == null);
        onMain(() -> view.autofill(values));
        Thread.sleep(1000);
        assertEquals("\"\"", evaluate(page, "document.forms[0].user.value"));
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
