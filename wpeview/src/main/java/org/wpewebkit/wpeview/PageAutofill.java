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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The login fields of the main frame of a page, as WPEView's extension of the web process tells of them, for the
 * view that shows the page to give to Android's Autofill. It holds them while the page is out of any view, and across
 * web processes, as the fields come again with each document.
 *
 * <p>The messages come through the web context, which hears every web process, and say which page they are for. So a
 * new document first proves that it is in the web process of this page: it gets a key that is sent only there, and
 * every later message of the document carries it. A web process that went wrong can then neither change the fields of
 * another page nor make Android offer to save for it. The site that Android gets is the origin of the document that
 * the page committed, never one that a message says.
 */
final class PageAutofill {
    private static final String LOGTAG = "PageAutofill";

    /** What the view that shows the page hears of its login fields. */
    interface Listener {
        /** Another document replaced the one of the fields, also when the new one has none. */
        void onAutofillDocumentChanged();
        void onAutofillFieldFocused(@NonNull Field field);
        void onAutofillFieldBlurred(@NonNull Field field);
        void onAutofillValueChanged(@NonNull Field field);
        /** The fields moved in the view, after scrolling or a change of size. */
        void onAutofillFieldsMoved();
        /** A form with login fields is sent, with these fields and their values. */
        void onAutofillSubmit(@NonNull List<Field> submitted);
    }

    // The role of a field, from what the page says of it: a user name, a password, or a new password, of a form to
    // sign up or to change the password.
    static final String ROLE_USERNAME = "username";
    static final String ROLE_PASSWORD = "password";
    static final String ROLE_NEW_PASSWORD = "new-password";

    // The ids that the view gives Android for the fields of every page, never reused, as Android keeps one Autofill
    // session for the whole activity, which could otherwise fill a field of another page.
    private static final AtomicInteger nextVirtualId = new AtomicInteger(1);
    // The new documents that were sent a key and have not answered yet. A few, so that one cannot push out another.
    private static final int MAX_PENDING_DOCUMENTS = 4;
    private static final SecureRandom random = new SecureRandom();

    static final class Field {
        final int id;
        final int virtualId;
        final @NonNull String role;
        final @NonNull String type;
        final @NonNull String name;
        final @NonNull String idAttribute;
        final @NonNull String autocomplete;
        @NonNull
        String value;
        // In pixels of the view.
        final @NonNull Rect rect = new Rect();

        Field(int id, int virtualId, @NonNull JSONObject description) {
            this.id = id;
            this.virtualId = virtualId;
            role = description.optString("role", ROLE_USERNAME);
            type = description.optString("type");
            name = description.optString("name");
            idAttribute = description.optString("idAttribute");
            autocomplete = description.optString("autocomplete");
            value = description.optString("value");
        }
    }

    private final @NonNull WebPage page;
    private @Nullable Listener listener;
    private @Nullable String documentToken;
    private @Nullable String documentKey;
    private @NonNull String origin = "";
    private final Map<String, String> pendingKeys = new LinkedHashMap<>();
    private final List<Field> fields = new ArrayList<>();
    private final Map<Integer, Field> fieldsById = new HashMap<>();
    private @Nullable Field focused;

    PageAutofill(@NonNull WebPage page) { this.page = page; }

    void setListener(@Nullable Listener listener) { this.listener = listener; }

    /** The origin of the document of the fields, as the web domain that Android's Autofill services check. */
    @NonNull
    String getOrigin() {
        return origin;
    }

    @NonNull
    List<Field> getFields() {
        return Collections.unmodifiableList(fields);
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

    /** Fills the fields with the values, which go only to the web process of the page. */
    void fill(@NonNull List<Field> filledFields, @NonNull List<String> values) {
        if (documentToken == null || documentKey == null || filledFields.isEmpty())
            return;
        int[] ids = new int[filledFields.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = filledFields.get(i).id;
        page.getWebKitWebView().autofill(documentToken, documentKey, origin, ids, values.toArray(new String[0]));
    }

    /** The web process of the page ended, and its fields with it. */
    void reset() {
        pendingKeys.clear();
        if (documentToken == null)
            return;
        documentToken = null;
        documentKey = null;
        origin = "";
        fields.clear();
        fieldsById.clear();
        focused = null;
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillDocumentChanged();
    }

    /**
     * The page committed a navigation. Fields of another origin than that of the new document are gone, also when the
     * new one has no login fields to tell of, or no script at all, as a page that is not on the web.
     */
    void onCommitted() {
        if (documentToken != null && !origin.equals(page.getWebKitWebView().getDocumentOrigin()))
            reset();
    }

    // A message of WPEView's extension of the web process. The messages of a document that another one replaced, which
    // can still come after it, are dropped once the new one has spoken.
    void onMessage(@NonNull String kind, @NonNull String content) {
        try {
            JSONObject message = new JSONObject(content);
            String token = message.getString("doc");
            String key = message.optString("key");
            String committedOrigin = page.getWebKitWebView().getDocumentOrigin();
            if (committedOrigin == null || !committedOrigin.equals(message.optString("origin")))
                return;
            if (kind.equals("fields")) {
                onFields(token, key, committedOrigin, message);
                return;
            }
            if (!token.equals(documentToken) || !key.equals(documentKey))
                return;
            switch (kind) {
            case "focus":
                onFocus(message);
                break;
            case "blur":
                onBlur(message);
                break;
            case "input":
                onInput(message);
                break;
            case "rects":
                onRects(message);
                break;
            case "submit":
                onSubmit(message);
                break;
            default:
                break;
            }
        } catch (JSONException e) {
            Log.w(LOGTAG, "Cannot read an autofill message of kind " + kind);
        }
    }

    private void onFields(@NonNull String token, @NonNull String key, @NonNull String committedOrigin,
                          @NonNull JSONObject message) throws JSONException {
        boolean newDocument;
        if (token.equals(documentToken) && key.equals(documentKey)) {
            newDocument = false;
        } else if (!key.isEmpty() && key.equals(pendingKeys.get(token))) {
            pendingKeys.remove(token);
            newDocument = true;
        } else {
            if (key.isEmpty() && !token.equals(documentToken))
                sendKey(token);
            return;
        }

        Map<Integer, Field> previous = newDocument ? Collections.emptyMap() : new HashMap<>(fieldsById);
        documentToken = token;
        documentKey = key;
        origin = committedOrigin;
        fields.clear();
        fieldsById.clear();
        JSONArray descriptions = message.getJSONArray("fields");
        for (int i = 0; i < descriptions.length(); i++) {
            JSONObject description = descriptions.getJSONObject(i);
            int id = description.getInt("id");
            Field old = previous.get(id);
            Field field = new Field(id, old != null ? old.virtualId : nextVirtualId.getAndIncrement(), description);
            readRect(description, field.rect);
            fields.add(field);
            fieldsById.put(id, field);
        }
        Listener currentListener = listener;
        if (newDocument) {
            focused = null;
            if (currentListener != null)
                currentListener.onAutofillDocumentChanged();
        } else if (focused != null) {
            // The focused field is no login field any more, as when the page took it away.
            Field left = focused;
            focused = fieldsById.get(left.id);
            if (focused == null && currentListener != null)
                currentListener.onAutofillFieldBlurred(left);
        }
    }

    // Sends a new document its key, through a call that reaches only the web process of this page. The document then
    // tells of its fields again, with the key.
    private void sendKey(@NonNull String token) {
        if (pendingKeys.containsKey(token))
            return;
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder key = new StringBuilder();
        for (byte b : bytes) {
            key.append(Character.forDigit((b >> 4) & 0xf, 16));
            key.append(Character.forDigit(b & 0xf, 16));
        }
        pendingKeys.put(token, key.toString());
        if (pendingKeys.size() > MAX_PENDING_DOCUMENTS)
            pendingKeys.remove(pendingKeys.keySet().iterator().next());
        page.getWebKitWebView().sendAutofillKey(token, key.toString());
    }

    private void onFocus(@NonNull JSONObject message) throws JSONException {
        Field field = fieldsById.get(message.getInt("id"));
        if (field == null)
            return;
        readRect(message, field.rect);
        focused = field;
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillFieldFocused(field);
    }

    private void onBlur(@NonNull JSONObject message) throws JSONException {
        Field field = fieldsById.get(message.getInt("id"));
        if (field == null || field != focused)
            return;
        focused = null;
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillFieldBlurred(field);
    }

    private void onInput(@NonNull JSONObject message) throws JSONException {
        Field field = fieldsById.get(message.getInt("id"));
        if (field == null)
            return;
        field.value = message.optString("value");
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillValueChanged(field);
    }

    private void onRects(@NonNull JSONObject message) throws JSONException {
        JSONArray moved = message.getJSONArray("fields");
        for (int i = 0; i < moved.length(); i++) {
            JSONObject item = moved.getJSONObject(i);
            Field field = fieldsById.get(item.getInt("id"));
            if (field != null)
                readRect(item, field.rect);
        }
        Listener currentListener = listener;
        if (currentListener != null)
            currentListener.onAutofillFieldsMoved();
    }

    private void onSubmit(@NonNull JSONObject message) throws JSONException {
        JSONArray sent = message.getJSONArray("fields");
        List<Field> submitted = new ArrayList<>();
        for (int i = 0; i < sent.length(); i++) {
            JSONObject item = sent.getJSONObject(i);
            Field field = fieldsById.get(item.getInt("id"));
            if (field == null)
                continue;
            field.value = item.optString("value");
            submitted.add(field);
        }
        Listener currentListener = listener;
        if (!submitted.isEmpty() && currentListener != null)
            currentListener.onAutofillSubmit(submitted);
    }

    private static void readRect(@NonNull JSONObject holder, @NonNull Rect rect) throws JSONException {
        JSONObject r = holder.optJSONObject("rect");
        if (r == null)
            return;
        int x = r.getInt("x");
        int y = r.getInt("y");
        rect.set(x, y, x + r.getInt("width"), y + r.getInt("height"));
    }
}
