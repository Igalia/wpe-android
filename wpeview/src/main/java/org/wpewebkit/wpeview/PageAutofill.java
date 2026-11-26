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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WebKitWebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The login fields of a page, as WebKit tells of them, for the view that shows the page to give to Android's
 * Autofill. WebKit finds the user name and password fields of a form when one of them gets the focus, and tells of
 * the values that the page submits. The fields stay after a blur, for the sign in that follows, until another form
 * replaces them or their document goes. Fields that WebKit finds from another field of the same form join them.
 *
 * <p>The site that Android gets is the origin of the frame of the form, as WebKit's UI process knows it, never one
 * that the page says, and only for http and https pages.
 */
final class PageAutofill {
    /** What the view that shows the page hears of its login fields. */
    interface Listener {
        /** Other fields replaced those of before, of another form or another document, or the fields are gone. */
        void onAutofillDocumentChanged();
        void onAutofillFieldFocused(@NonNull Field field);
        void onAutofillFieldBlurred(@NonNull Field field);
        void onAutofillValueChanged(@NonNull Field field);
        /** The fields moved in the view, after scrolling or a change of size. */
        void onAutofillFieldsMoved();
        /** A form with login fields is sent, with these fields and their values. */
        void onAutofillSubmit(@NonNull List<Field> submitted);
        /** The app changed what Android's Autofill may do with the page, in its settings. */
        void onAutofillModeChanged();
    }

    // What a field is for: a user name, a password, or a new password, of a form to sign up or to change the password.
    static final int ROLE_USERNAME = WebKitWebView.LOGIN_FORM_FIELD_ROLE_USERNAME;
    static final int ROLE_PASSWORD = WebKitWebView.LOGIN_FORM_FIELD_ROLE_PASSWORD;
    static final int ROLE_NEW_PASSWORD = WebKitWebView.LOGIN_FORM_FIELD_ROLE_NEW_PASSWORD;

    // The ids that the view gives Android for the fields of every page, never reused, as Android keeps one Autofill
    // session for the whole activity, which could otherwise fill a field of another page.
    private static final AtomicInteger nextVirtualId = new AtomicInteger(1);

    static final class Field {
        // WebKit's identifier of the element.
        final long id;
        final int virtualId;
        final int role;
        final @NonNull String type;
        final @NonNull String name;
        final @NonNull String idAttribute;
        final @NonNull String autocomplete;
        final @NonNull String placeholder;
        final @NonNull String label;
        @NonNull
        String value;
        // In pixels of the view.
        final @NonNull Rect rect;

        Field(int virtualId, @NonNull WebKitWebView.LoginForm.Field field) {
            this.virtualId = virtualId;
            id = field.id;
            role = field.role;
            type = field.type;
            name = field.name;
            idAttribute = field.idAttribute;
            autocomplete = field.autocomplete;
            placeholder = field.placeholder;
            label = field.label;
            value = field.value;
            rect = new Rect(field.bounds);
        }
    }

    private final @NonNull WebPage page;
    private @Nullable Listener listener;
    private @NonNull String origin = "";
    private @NonNull List<Field> fields = Collections.emptyList();
    private @Nullable Field focused;

    PageAutofill(@NonNull WebPage page) { this.page = page; }

    void setListener(@Nullable Listener listener) { this.listener = listener; }

    void onModeChanged() {
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillModeChanged();
    }

    /** The origin of the frame of the fields, as the web domain that Android's Autofill services check. */
    @NonNull
    String getOrigin() {
        return origin;
    }

    @NonNull
    List<Field> getFields() {
        return fields;
    }

    @Nullable
    Field getFocusedField() {
        return focused;
    }

    @Nullable
    Field findByVirtualId(int virtualId) {
        for (Field field : fields) {
            if (field.virtualId == virtualId)
                return field;
        }
        return null;
    }

    /**
     * Fills the fields, as a user who typed the values would, while the field that the service answered for has the
     * focus. WebKit fills the fields of the form of the focused field; a field without a value stays as it is.
     */
    void fill(@NonNull Map<Field, String> values) {
        Field current = focused;
        if (current == null)
            return;
        long[] ids = new long[values.size()];
        String[] texts = new String[values.size()];
        int i = 0;
        for (Map.Entry<Field, String> entry : values.entrySet()) {
            ids[i] = entry.getKey().id;
            texts[i++] = entry.getValue();
        }
        page.getWebKitWebView().autofillLoginForm(current.id, ids, texts);
    }

    /** The fields are gone, with their document or the web process of the page, or as the app turned Autofill off. */
    void reset() {
        if (fields.isEmpty())
            return;
        origin = "";
        fields = Collections.emptyList();
        focused = null;
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillDocumentChanged();
    }

    /** WebKit's login form whose field has the focus, or null when no field of a login form has the focus. */
    void onFocusedLoginForm(@Nullable WebKitWebView.LoginForm form) {
        Listener currentListener = listener;
        Field oldFocused = focused;
        if (form == null) {
            focused = null;
            if (oldFocused != null && currentListener != null)
                currentListener.onAutofillFieldBlurred(oldFocused);
            return;
        }

        if (!sharesFields(form)) {
            List<Field> adopted = new ArrayList<>(form.fields.size());
            for (WebKitWebView.LoginForm.Field field : form.fields)
                adopted.add(new Field(nextVirtualId.getAndIncrement(), field));
            origin = form.origin;
            fields = Collections.unmodifiableList(adopted);
            focused = form.focusedField >= 0 ? fields.get(form.focusedField) : null;
            if (currentListener == null)
                return;
            currentListener.onAutofillDocumentChanged();
            if (focused != null)
                currentListener.onAutofillFieldFocused(focused);
            return;
        }

        // The same form, maybe seen from another of its fields, as the confirmation field of a form to sign up, which
        // WebKit finds alone: the fields that are new join those of before, so that the session goes on.
        List<Field> merged = new ArrayList<>(fields);
        for (WebKitWebView.LoginForm.Field field : form.fields) {
            if (findById(field.id) == null)
                merged.add(new Field(nextVirtualId.getAndIncrement(), field));
        }
        fields = Collections.unmodifiableList(merged);
        focused = form.focusedField >= 0 ? findById(form.fields.get(form.focusedField).id) : null;
        boolean focusMoved = focused != null && focused != oldFocused;
        // The fields stay current while the page is out of any view, too.
        boolean moved = updateFields(form, focusMoved ? null : currentListener);
        if (currentListener == null)
            return;
        if (focusMoved)
            currentListener.onAutofillFieldFocused(focused);
        else if (moved)
            currentListener.onAutofillFieldsMoved();
    }

    /** WebKit tells that the page is about to submit a login form, with its values. */
    void onSubmitted(@NonNull WebKitWebView.LoginForm form) {
        if (!form.origin.equals(origin))
            return;
        List<Field> submitted = new ArrayList<>();
        for (WebKitWebView.LoginForm.Field sent : form.fields) {
            Field field = findById(sent.id);
            if (field != null) {
                field.value = sent.value;
                submitted.add(field);
            }
        }
        Listener currentListener = listener;
        if (!submitted.isEmpty() && currentListener != null)
            currentListener.onAutofillSubmit(submitted);
    }

    @Nullable
    private Field findById(long id) {
        for (Field field : fields) {
            if (field.id == id)
                return field;
        }
        return null;
    }

    // Whether the form is in the frame of the fields and has one of their elements.
    private boolean sharesFields(@NonNull WebKitWebView.LoginForm form) {
        if (!form.origin.equals(origin))
            return false;
        for (WebKitWebView.LoginForm.Field field : form.fields) {
            if (findById(field.id) != null)
                return true;
        }
        return false;
    }

    // Takes the values and bounds of the fields that the form has, telling the listener of each value that changed, if
    // there is one. Returns whether a field moved.
    private boolean updateFields(@NonNull WebKitWebView.LoginForm form, @Nullable Listener valueListener) {
        boolean moved = false;
        for (WebKitWebView.LoginForm.Field update : form.fields) {
            Field field = findById(update.id);
            if (field == null)
                continue;
            moved |= !field.rect.equals(update.bounds);
            field.rect.set(update.bounds);
            if (!field.value.equals(update.value)) {
                field.value = update.value;
                if (valueListener != null)
                    valueListener.onAutofillValueChanged(field);
            }
        }
        return moved;
    }
}
