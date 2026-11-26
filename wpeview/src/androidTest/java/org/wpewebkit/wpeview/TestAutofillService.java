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

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.content.IntentSender;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A password service for the Autofill tests, which the tests set as the device's own. It keeps what Android asks of it,
 * offers to save the login fields it finds, and keeps what it is asked to save. It can also offer one account.
 */
public class TestAutofillService extends AutofillService {
    /** A field of a page, as the service got it. */
    static final class Field {
        final AutofillId id;
        final String hint;
        final @Nullable String value;
        final Map<String, String> attributes = new HashMap<>();
        final int left;
        final int top;
        final int width;
        final int height;
        final boolean focused;

        Field(@NonNull AssistStructure.ViewNode node, @NonNull String hint) {
            id = node.getAutofillId();
            this.hint = hint;
            AutofillValue autofillValue = node.getAutofillValue();
            value = autofillValue != null && autofillValue.isText() ? autofillValue.getTextValue().toString() : null;
            ViewStructure.HtmlInfo html = node.getHtmlInfo();
            if (html != null && html.getAttributes() != null) {
                for (Pair<String, String> attribute : html.getAttributes())
                    attributes.put(attribute.first, attribute.second);
            }
            left = node.getLeft();
            top = node.getTop();
            width = node.getWidth();
            height = node.getHeight();
            focused = node.isFocused();
        }
    }

    /** A request to fill or to save, with the fields of the latest structure and the site they belong to. */
    static final class Request {
        final List<Field> fields = new ArrayList<>();
        @Nullable
        String webScheme;
        @Nullable
        String webDomain;
        int contextCount;

        @Nullable
        Field withHint(@NonNull String hint) {
            for (Field field : fields) {
                if (field.hint.equals(hint))
                    return field;
            }
            return null;
        }
    }

    static final BlockingQueue<Request> fillRequests = new LinkedBlockingQueue<>();
    static final BlockingQueue<Request> saveRequests = new LinkedBlockingQueue<>();

    // When set, the service offers an account with these values in Android's list, as for a saved login, behind its
    // TestAutofillAuthActivity when authenticate is set, as a service that asks for the screen lock first.
    static final String ACCOUNT_LABEL = "Test account";
    @Nullable
    static volatile String accountUser;
    @Nullable
    static volatile String accountPassword;
    static volatile boolean authenticate;
    @Nullable
    static volatile Dataset unlockedAccount;

    static void reset() {
        fillRequests.clear();
        saveRequests.clear();
        accountUser = null;
        accountPassword = null;
        authenticate = false;
        unlockedAccount = null;
    }

    private static void collect(@NonNull AssistStructure.ViewNode node, @NonNull Request request) {
        if (node.getWebDomain() != null && request.webDomain == null) {
            request.webScheme = node.getWebScheme();
            request.webDomain = node.getWebDomain();
        }
        String[] hints = node.getAutofillHints();
        if (hints != null && hints.length > 0)
            request.fields.add(new Field(node, hints[0]));
        for (int i = 0; i < node.getChildCount(); i++)
            collect(node.getChildAt(i), request);
    }

    private static @NonNull Request read(@NonNull List<FillContext> contexts) {
        Request request = new Request();
        request.contextCount = contexts.size();
        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
        for (int i = 0; i < structure.getWindowNodeCount(); i++)
            collect(structure.getWindowNodeAt(i).getRootViewNode(), request);
        return request;
    }

    // Through the presentation of Android 12, as Presentations came with Android 13.
    @SuppressWarnings("deprecation")
    private @NonNull Dataset account(@NonNull AutofillId user, @NonNull AutofillId password) {
        RemoteViews presentation = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
        presentation.setTextViewText(android.R.id.text1, ACCOUNT_LABEL);
        Dataset values = new Dataset.Builder(presentation)
                             .setValue(user, AutofillValue.forText(accountUser))
                             .setValue(password, AutofillValue.forText(accountPassword))
                             .build();
        if (!authenticate)
            return values;
        unlockedAccount = values;
        // Android adds the structure of the page to the intent, so it is mutable.
        IntentSender unlock = PendingIntent
                                  .getActivity(this, 0, new Intent(this, TestAutofillAuthActivity.class),
                                               PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT)
                                  .getIntentSender();
        return new Dataset.Builder(presentation)
            .setValue(user, null)
            .setValue(password, null)
            .setAuthentication(unlock)
            .build();
    }

    @Override
    public void onFillRequest(@NonNull FillRequest fillRequest, @NonNull CancellationSignal cancellationSignal,
                              @NonNull FillCallback callback) {
        Request request = read(fillRequest.getFillContexts());
        fillRequests.add(request);
        List<AutofillId> ids = new ArrayList<>();
        for (Field field : request.fields)
            ids.add(field.id);
        if (ids.isEmpty()) {
            callback.onSuccess(null);
            return;
        }
        FillResponse.Builder response = new FillResponse.Builder();
        Field user = request.withHint(View.AUTOFILL_HINT_USERNAME);
        Field password = request.withHint(View.AUTOFILL_HINT_PASSWORD);
        if (accountUser != null && user != null && password != null)
            response.addDataset(account(user.id, password.id));
        callback.onSuccess(
            response
                .setSaveInfo(new SaveInfo
                                 .Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME | SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                                          ids.toArray(new AutofillId[0]))
                                 .build())
                .build());
    }

    @Override
    public void onSaveRequest(@NonNull SaveRequest saveRequest, @NonNull SaveCallback callback) {
        saveRequests.add(read(saveRequest.getFillContexts()));
        callback.onSuccess();
    }
}
