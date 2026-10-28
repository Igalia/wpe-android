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

#include "WPEInputMethodContextAndroid.h"

#include "Logging.h"

#include <cstring>

struct _WPEInputMethodContextAndroid {
    WPEInputMethodContext parent;
    WPEInputMethodContextAndroidFocusCallback focusInCallback;
    WPEInputMethodContextAndroidFocusCallback focusOutCallback;
    gpointer focusCallbackUserData;
    WPEInputMethodContextAndroidResetCallback resetCallback;
    gpointer resetCallbackUserData;
    // Non-null while a preedit is shown, even when it is empty.
    gchar* preeditText;
    guint preeditCursorOffset;
    WPEInputMethodContextAndroidSurroundingCallback surroundingCallback;
    gpointer surroundingCallbackUserData;
};

G_DEFINE_FINAL_TYPE(WPEInputMethodContextAndroid, wpe_input_method_context_android, WPE_TYPE_INPUT_METHOD_CONTEXT)

static GQuark wpeInputMethodContextAndroidViewQuark()
{
    static GQuark quark = g_quark_from_static_string("wpe-input-method-context-android");
    return quark;
}

static void wpeInputMethodContextAndroidDispose(GObject* object)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(object);
    auto* view = wpe_input_method_context_get_view(WPE_INPUT_METHOD_CONTEXT(self));
    // The view holds a non-owning back-reference; clear it if it still points at us.
    if (view && g_object_get_qdata(G_OBJECT(view), wpeInputMethodContextAndroidViewQuark()) == self)
        g_object_set_qdata(G_OBJECT(view), wpeInputMethodContextAndroidViewQuark(), nullptr);

    G_OBJECT_CLASS(wpe_input_method_context_android_parent_class)->dispose(object);
}

static void wpeInputMethodContextAndroidFinalize(GObject* object)
{
    g_free(WPE_INPUT_METHOD_CONTEXT_ANDROID(object)->preeditText);

    G_OBJECT_CLASS(wpe_input_method_context_android_parent_class)->finalize(object);
}

static void wpeInputMethodContextAndroidGetPreeditString(
    WPEInputMethodContext* context, gchar** text, GList** underlines, guint* cursorOffset)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (text)
        *text = g_strdup(self->preeditText ? self->preeditText : "");
    // No underlines: WebKit then underlines the whole preedit.
    if (underlines)
        *underlines = nullptr;
    if (cursorOffset)
        *cursorOffset = self->preeditText ? self->preeditCursorOffset : 0;
}

// Forgets the preedit without telling WebKit. Returns whether there was one.
static bool clearPreedit(WPEInputMethodContextAndroid* self)
{
    if (!self->preeditText)
        return false;
    g_clear_pointer(&self->preeditText, g_free);
    self->preeditCursorOffset = 0;
    return true;
}

static void wpeInputMethodContextAndroidFocusIn(WPEInputMethodContext* context)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    // A preedit set while no field was focused never reached WebKit, which ignores it then. Forget it, or finishing
    // it would commit its text into the field focused now.
    clearPreedit(self);
    if (self->focusInCallback)
        self->focusInCallback(self->focusCallbackUserData);
}

static void wpeInputMethodContextAndroidFocusOut(WPEInputMethodContext* context)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (self->focusOutCallback)
        self->focusOutCallback(self->focusCallbackUserData);
}

static void wpeInputMethodContextAndroidSetSurrounding(
    WPEInputMethodContext* context, const gchar* text, guint length, guint cursorIndex, guint selectionIndex)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (!self->surroundingCallback)
        return;

    // The keyboard is a separate app: don't hand it the contents of password or PIN fields. A null text tells
    // them apart from an empty field.
    auto purpose = wpe_input_method_context_get_input_purpose(context);
    if (purpose == WPE_INPUT_PURPOSE_PASSWORD || purpose == WPE_INPUT_PURPOSE_PIN) {
        self->surroundingCallback(nullptr, 0, 0, 0, self->surroundingCallbackUserData);
        return;
    }

    if (!text) {
        text = "";
        length = 0;
    } else if (length == static_cast<guint>(-1)) // wpe_input_method_context_set_surrounding() allows it.
        length = strlen(text);
    self->surroundingCallback(text, length, cursorIndex, selectionIndex, self->surroundingCallbackUserData);
}

static void wpeInputMethodContextAndroidReset(WPEInputMethodContext* context)
{
    // WebKit has already confirmed or dropped the composition on its side (InputMethodFilter::cancelComposition),
    // so only the input method still thinks it is composing. Forget the preedit before telling the WebView: it
    // restarts the input method right away, and the old connection finishing its composition must not commit the
    // text again.
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (clearPreedit(self) && self->resetCallback)
        self->resetCallback(self->resetCallbackUserData);
}

static void wpe_input_method_context_android_class_init(WPEInputMethodContextAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeInputMethodContextAndroidDispose;
    objectClass->finalize = wpeInputMethodContextAndroidFinalize;

    WPEInputMethodContextClass* imContextClass = WPE_INPUT_METHOD_CONTEXT_CLASS(klass);
    imContextClass->get_preedit_string = wpeInputMethodContextAndroidGetPreeditString;
    imContextClass->focus_in = wpeInputMethodContextAndroidFocusIn;
    imContextClass->focus_out = wpeInputMethodContextAndroidFocusOut;
    imContextClass->set_surrounding = wpeInputMethodContextAndroidSetSurrounding;
    imContextClass->reset = wpeInputMethodContextAndroidReset;
}

static void wpe_input_method_context_android_init(WPEInputMethodContextAndroid* context)
{
    UNUSED_PARAM(context);
}

WPEInputMethodContext* wpe_input_method_context_android_new(WPEView* view)
{
    g_return_val_if_fail(WPE_IS_VIEW(view), nullptr);
    auto* context
        = WPE_INPUT_METHOD_CONTEXT(g_object_new(WPE_TYPE_INPUT_METHOD_CONTEXT_ANDROID, "view", view, nullptr));
    // Non-owning back-reference so JNI bridges can resolve the current IM context from a WPEView*.
    // WebKit owns the strong reference via wpe_view_set_input_method_context().
    g_object_set_qdata(G_OBJECT(view), wpeInputMethodContextAndroidViewQuark(), context);
    return context;
}

WPEInputMethodContextAndroid* wpe_input_method_context_android_from_view(WPEView* view)
{
    g_return_val_if_fail(WPE_IS_VIEW(view), nullptr);
    auto* context = g_object_get_qdata(G_OBJECT(view), wpeInputMethodContextAndroidViewQuark());
    return context ? WPE_INPUT_METHOD_CONTEXT_ANDROID(context) : nullptr;
}

void wpe_input_method_context_android_set_focus_callbacks(WPEInputMethodContextAndroid* context,
    WPEInputMethodContextAndroidFocusCallback focusIn, WPEInputMethodContextAndroidFocusCallback focusOut,
    gpointer userData)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    context->focusInCallback = focusIn;
    context->focusOutCallback = focusOut;
    context->focusCallbackUserData = userData;
}

void wpe_input_method_context_android_set_reset_callback(
    WPEInputMethodContextAndroid* context, WPEInputMethodContextAndroidResetCallback callback, gpointer userData)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    context->resetCallback = callback;
    context->resetCallbackUserData = userData;
}

void wpe_input_method_context_android_set_surrounding_callback(
    WPEInputMethodContextAndroid* context, WPEInputMethodContextAndroidSurroundingCallback callback, gpointer userData)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    context->surroundingCallback = callback;
    context->surroundingCallbackUserData = userData;
}

void wpe_input_method_context_android_commit_text(WPEInputMethodContextAndroid* context, const char* text)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    g_return_if_fail(text != nullptr);
    if (!*text) {
        // Committing nothing replaces the preedit with nothing: empty it, then finish it.
        if (context->preeditText) {
            wpe_input_method_context_android_set_preedit(context, "", 0);
            wpe_input_method_context_android_finish_preedit(context);
        }
        return;
    }
    // A commit replaces the preedit: WebKit confirms the composition with the committed text.
    bool hadPreedit = clearPreedit(context);
    g_signal_emit_by_name(context, "committed", text);
    if (hadPreedit)
        g_signal_emit_by_name(context, "preedit-finished");
}

void wpe_input_method_context_android_set_preedit(
    WPEInputMethodContextAndroid* context, const char* text, unsigned int cursorOffset)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    g_return_if_fail(text != nullptr);
    bool started = !context->preeditText;
    if (started && !*text)
        return;
    g_free(context->preeditText);
    context->preeditText = g_strdup(text);
    context->preeditCursorOffset = cursorOffset;
    if (started)
        g_signal_emit_by_name(context, "preedit-started");
    g_signal_emit_by_name(context, "preedit-changed");
}

void wpe_input_method_context_android_finish_preedit(WPEInputMethodContextAndroid* context)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    if (!context->preeditText)
        return;
    g_autofree gchar* text = context->preeditText;
    context->preeditText = nullptr;
    context->preeditCursorOffset = 0;
    // Committing the preedit text keeps it in the page; finishing an empty preedit drops the composition.
    if (*text)
        g_signal_emit_by_name(context, "committed", text);
    g_signal_emit_by_name(context, "preedit-finished");
}

void wpe_input_method_context_android_delete_surrounding(
    WPEInputMethodContextAndroid* context, int offset, unsigned int count)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    g_signal_emit_by_name(context, "delete-surrounding", offset, count);
}
