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

struct _WPEInputMethodContextAndroid {
    WPEInputMethodContext parent;
    WPEInputMethodContextAndroidFocusCallback focusInCallback;
    WPEInputMethodContextAndroidFocusCallback focusOutCallback;
    gpointer focusCallbackUserData;
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

static void wpeInputMethodContextAndroidGetPreeditString(
    WPEInputMethodContext* context, gchar** text, GList** underlines, guint* cursorOffset)
{
    UNUSED_PARAM(context);
    // Preedit is not supported: BaseInputConnection's composing text is forwarded as committed text.
    if (text)
        *text = nullptr;
    if (underlines)
        *underlines = nullptr;
    if (cursorOffset)
        *cursorOffset = 0;
}

static void wpeInputMethodContextAndroidFocusIn(WPEInputMethodContext* context)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (self->focusInCallback)
        self->focusInCallback(self->focusCallbackUserData);
}

static void wpeInputMethodContextAndroidFocusOut(WPEInputMethodContext* context)
{
    auto* self = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (self->focusOutCallback)
        self->focusOutCallback(self->focusCallbackUserData);
}

static void wpeInputMethodContextAndroidReset(WPEInputMethodContext* context)
{
    UNUSED_PARAM(context);
}

static void wpe_input_method_context_android_class_init(WPEInputMethodContextAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeInputMethodContextAndroidDispose;

    WPEInputMethodContextClass* imContextClass = WPE_INPUT_METHOD_CONTEXT_CLASS(klass);
    imContextClass->get_preedit_string = wpeInputMethodContextAndroidGetPreeditString;
    imContextClass->focus_in = wpeInputMethodContextAndroidFocusIn;
    imContextClass->focus_out = wpeInputMethodContextAndroidFocusOut;
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

void wpe_input_method_context_android_commit_text(WPEInputMethodContextAndroid* context, const char* text)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    g_return_if_fail(text != nullptr);
    g_signal_emit_by_name(context, "committed", text);
}

void wpe_input_method_context_android_delete_surrounding(
    WPEInputMethodContextAndroid* context, int offset, unsigned int count)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    g_signal_emit_by_name(context, "delete-surrounding", offset, count);
}
