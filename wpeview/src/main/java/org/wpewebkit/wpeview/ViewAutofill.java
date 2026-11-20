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

import android.graphics.Rect;
import android.os.SystemClock;
import android.text.InputType;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives Android's Autofill the login fields of the page that a WebView shows, as virtual children of the view, so that
 * the password service the user chose offers its accounts and offers to save after a sign in.
 *
 * <p>Android keeps one Autofill session for the whole activity, which commit() and cancel() end. So the view only ends
 * a session that it took part in, by entering a field of the page it shows, and ends it when the page leaves the view
 * or another document replaces the fields. Two views of one activity that both show login fields still share that
 * session, and one view ending it ends it for the other too. The view takes no part while it is not important for
 * Autofill, which is how an app turns Autofill off, for example for private pages: Android would otherwise offer to
 * save the sign ins of the session when the activity finishes.
 */
final class ViewAutofill implements PageAutofill.Listener {
    // Android's hint for a new password, as in androidx.autofill's HintConstants.
    private static final String AUTOFILL_HINT_NEW_PASSWORD = "newPassword";
    // Entering a field again makes Android ask the password service once more if it has not answered yet, which drops
    // the first answer. Fields move right after the focus, as the keyboard opens, so the view waits this long after
    // entering a field before it tells Android that the field moved. A service that takes longer to answer, as when it
    // starts, can still be asked twice.
    private static final long MOVE_DELAY_MS = 1000;

    private final @NonNull View view;
    private @Nullable PageAutofill autofill;
    // Whether the view entered a field of the page since the last commit or cancel.
    private boolean inSession;
    // The virtual id of the field that the view entered, or 0, and where and when.
    private int enteredId;
    private final Rect enteredBounds = new Rect();
    private long enteredTime;
    private final Runnable moveEntered = this::onAutofillFieldsMoved;
    // The values of the last sign in of the document, by virtual id. Android offers to save only values that changed
    // since the session started, so a session that starts after it, as when the page refused the password, starts
    // from them.
    private final Map<Integer, String> signedInValues = new HashMap<>();

    ViewAutofill(@NonNull View view) { this.view = view; }

    /** The view shows the page. A field that the page kept focused while it was out of the view is entered again. */
    void attach(@NonNull WebPage page) {
        autofill = page.autofill;
        page.autofill.setListener(this);
        PageAutofill.Field focused = page.autofill.getFocusedField();
        if (focused != null && view.hasFocus())
            onAutofillFieldFocused(focused);
    }

    /** The page leaves the view. */
    void detach() {
        PageAutofill current = autofill;
        if (current == null)
            return;
        endSession();
        current.setListener(null);
        autofill = null;
    }

    private @Nullable AutofillManager getManager() { return view.getContext().getSystemService(AutofillManager.class); }

    private @Nullable AutofillManager getEnabledManager() {
        AutofillManager manager = getManager();
        return manager != null && manager.isEnabled() && view.isImportantForAutofill() ? manager : null;
    }

    private void exitEntered(@NonNull AutofillManager manager) {
        view.removeCallbacks(moveEntered);
        if (enteredId != 0)
            manager.notifyViewExited(view, enteredId);
        enteredId = 0;
    }

    private void endSession() {
        signedInValues.clear();
        view.removeCallbacks(moveEntered);
        AutofillManager manager = getManager();
        if (manager == null)
            return;
        exitEntered(manager);
        if (inSession) {
            inSession = false;
            manager.cancel();
        }
    }

    void provideVirtualStructure(@NonNull ViewStructure structure) {
        PageAutofill current = autofill;
        if (current == null || getEnabledManager() == null)
            return;
        List<PageAutofill.Field> fields = current.getFields();
        if (fields.isEmpty())
            return;

        structure.setWebDomain(current.getOrigin());
        PageAutofill.Field focused = current.getFocusedField();
        int index = structure.addChildCount(fields.size());
        for (PageAutofill.Field field : fields) {
            ViewStructure child = structure.newChild(index++);
            child.setAutofillId(structure.getAutofillId(), field.virtualId);
            child.setAutofillType(View.AUTOFILL_TYPE_TEXT);
            String signedIn = signedInValues.get(field.virtualId);
            child.setAutofillValue(AutofillValue.forText(signedIn != null ? signedIn : field.value));
            child.setAutofillHints(new String[] {hintOf(field)});
            child.setInputType(inputTypeOf(field));
            child.setClassName("android.widget.EditText");
            child.setDimens(field.rect.left, field.rect.top, 0, 0, field.rect.width(), field.rect.height());
            child.setVisibility(View.VISIBLE);
            child.setEnabled(true);
            child.setFocusable(true);
            child.setFocused(focused != null && field.virtualId == focused.virtualId);
            child.setDataIsSensitive(true);
            ViewStructure.HtmlInfo.Builder html = child.newHtmlInfoBuilder("input");
            addAttribute(html, "type", field.type);
            addAttribute(html, "name", field.name);
            addAttribute(html, "id", field.idAttribute);
            addAttribute(html, "autocomplete", field.autocomplete);
            child.setHtmlInfo(html.build());
        }
    }

    void autofill(@NonNull SparseArray<AutofillValue> values) {
        PageAutofill current = autofill;
        if (current == null)
            return;
        List<PageAutofill.Field> fields = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            AutofillValue value = values.valueAt(i);
            PageAutofill.Field field = current.findByVirtualId(values.keyAt(i));
            if (field == null || value == null || !value.isText())
                continue;
            fields.add(field);
            texts.add(value.getTextValue().toString());
        }
        current.fill(fields, texts);
    }

    @Override
    public void onAutofillDocumentChanged() {
        endSession();
    }

    @Override
    public void onAutofillFieldFocused(@NonNull PageAutofill.Field field) {
        AutofillManager manager = getEnabledManager();
        if (manager == null)
            return;
        view.removeCallbacks(moveEntered);
        Rect bounds = boundsOnScreen(field);
        enteredId = field.virtualId;
        enteredBounds.set(bounds);
        enteredTime = SystemClock.uptimeMillis();
        inSession = true;
        manager.notifyViewEntered(view, field.virtualId, bounds);
    }

    @Override
    public void onAutofillFieldBlurred(@NonNull PageAutofill.Field field) {
        AutofillManager manager = getManager();
        if (manager != null && field.virtualId == enteredId)
            exitEntered(manager);
    }

    // After a sign in, which ends the session, the user may go on typing in the same field, as when the page refused
    // the password: that starts a new session.
    @Override
    public void onAutofillValueChanged(@NonNull PageAutofill.Field field) {
        // A field that the page or the user emptied no longer holds what was signed in with.
        if (field.value.isEmpty())
            signedInValues.remove(field.virtualId);
        AutofillManager manager = getEnabledManager();
        PageAutofill current = autofill;
        if (manager == null || current == null)
            return;
        if (!inSession) {
            PageAutofill.Field focused = current.getFocusedField();
            if (focused == null || focused.virtualId != field.virtualId)
                return;
            onAutofillFieldFocused(focused);
        }
        manager.notifyValueChanged(view, field.virtualId, AutofillValue.forText(field.value));
    }

    // Android moves its list of accounts with the field.
    @Override
    public void onAutofillFieldsMoved() {
        AutofillManager manager = getEnabledManager();
        PageAutofill current = autofill;
        PageAutofill.Field field = current != null && enteredId != 0 ? current.findByVirtualId(enteredId) : null;
        if (manager == null || field == null)
            return;
        long wait = enteredTime + MOVE_DELAY_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            view.removeCallbacks(moveEntered);
            view.postDelayed(moveEntered, wait);
            return;
        }
        Rect bounds = boundsOnScreen(field);
        if (bounds.equals(enteredBounds))
            return;
        enteredBounds.set(bounds);
        manager.notifyViewEntered(view, field.virtualId, bounds);
    }

    // The password service decides whether to offer to save, as it sees the sign in. A sign in that fails is offered
    // too, as the view cannot tell.
    @Override
    public void onAutofillSubmit(@NonNull List<PageAutofill.Field> submitted) {
        AutofillManager manager = getEnabledManager();
        if (manager == null || !inSession)
            return;
        signedInValues.clear();
        for (PageAutofill.Field field : submitted) {
            manager.notifyValueChanged(view, field.virtualId, AutofillValue.forText(field.value));
            signedInValues.put(field.virtualId, field.value);
        }
        view.removeCallbacks(moveEntered);
        enteredId = 0;
        inSession = false;
        manager.commit();
    }

    private @NonNull Rect boundsOnScreen(@NonNull PageAutofill.Field field) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Rect bounds = new Rect(field.rect);
        bounds.offset(location[0], location[1]);
        return bounds;
    }

    private static @NonNull String hintOf(@NonNull PageAutofill.Field field) {
        switch (field.role) {
        case PageAutofill.ROLE_PASSWORD:
            return View.AUTOFILL_HINT_PASSWORD;
        case PageAutofill.ROLE_NEW_PASSWORD:
            return AUTOFILL_HINT_NEW_PASSWORD;
        default:
            return View.AUTOFILL_HINT_USERNAME;
        }
    }

    private static int inputTypeOf(@NonNull PageAutofill.Field field) {
        if (!field.role.equals(PageAutofill.ROLE_USERNAME))
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        switch (field.type) {
        case "email":
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
        case "tel":
            return InputType.TYPE_CLASS_PHONE;
        default:
            return InputType.TYPE_CLASS_TEXT;
        }
    }

    private static void addAttribute(@NonNull ViewStructure.HtmlInfo.Builder html, @NonNull String name,
                                     @NonNull String value) {
        if (!value.isEmpty())
            html.addAttribute(name, value);
    }
}
