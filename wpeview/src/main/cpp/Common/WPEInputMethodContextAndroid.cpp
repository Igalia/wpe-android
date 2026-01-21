/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
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
#include "WPEViewAndroid.h"

struct _WPEInputMethodContextAndroid {
    WPEInputMethodContext parent;
    WPEInputMethodContextAndroidFocusCallback focusInCallback;
    WPEInputMethodContextAndroidFocusCallback focusOutCallback;
    void* callbackUserData;
};

G_DEFINE_FINAL_TYPE(WPEInputMethodContextAndroid, wpe_input_method_context_android, WPE_TYPE_INPUT_METHOD_CONTEXT)

static void wpeInputMethodContextAndroidDispose(GObject* object)
{
    auto* context = WPE_INPUT_METHOD_CONTEXT(object);
    auto* view = wpe_input_method_context_get_view(context);
    if (view != nullptr) {
        Logging::logDebug("WPEInputMethodContextAndroid::dispose view=%p", view);
        auto* viewAndroid = WPE_VIEW_ANDROID(view);
        if (wpe_view_android_get_input_method_context(viewAndroid) == context)
            wpe_view_android_set_input_method_context(viewAndroid, nullptr);
    }
    G_OBJECT_CLASS(wpe_input_method_context_android_parent_class)->dispose(object);
}

static void wpeInputMethodContextAndroidGetPreeditString(
    WPEInputMethodContext*, char** text, GList** underlines, guint* cursorOffset)
{
    // No preedit support for now - all text is committed immediately
    if (text != nullptr)
        *text = g_strdup("");
    if (underlines != nullptr)
        *underlines = nullptr;
    if (cursorOffset != nullptr)
        *cursorOffset = 0;
}

static void wpeInputMethodContextAndroidFocusIn(WPEInputMethodContext* context)
{
    auto* view = wpe_input_method_context_get_view(context);
    auto* imContext = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    Logging::logDebug(
        "WPEInputMethodContextAndroid::focus_in(%p) view=%p callback=%p", context, view, imContext->focusInCallback);
    if (imContext->focusInCallback != nullptr)
        imContext->focusInCallback(imContext->callbackUserData);
}

static void wpeInputMethodContextAndroidFocusOut(WPEInputMethodContext* context)
{
    Logging::logDebug("WPEInputMethodContextAndroid::focus_out(%p)", context);
    auto* imContext = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    if (imContext->focusOutCallback != nullptr)
        imContext->focusOutCallback(imContext->callbackUserData);
}

static void wpeInputMethodContextAndroidReset(WPEInputMethodContext*)
{
    // No state to reset
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

static void wpe_input_method_context_android_init(WPEInputMethodContextAndroid* /*self*/)
{
    Logging::logDebug("WPEInputMethodContextAndroid::init");
}

WPEInputMethodContext* wpe_input_method_context_android_new(WPEView* view)
{
    Logging::logDebug("WPEInputMethodContextAndroid::new(%p)", view);
    auto* context
        = WPE_INPUT_METHOD_CONTEXT(g_object_new(WPE_TYPE_INPUT_METHOD_CONTEXT_ANDROID, "view", view, nullptr));

    auto* viewAndroid = WPE_VIEW_ANDROID(view);
    wpe_view_android_set_input_method_context(viewAndroid, context);
    Logging::logDebug("WPEInputMethodContextAndroid::new - stored in view=%p", view);

    wpe_view_android_apply_pending_focus_callbacks(viewAndroid, context);

    return context;
}

WPEInputMethodContext* wpe_input_method_context_android_get_for_view(WPEView* view)
{
    if (!WPE_IS_VIEW_ANDROID(view))
        return nullptr;
    return wpe_view_android_get_input_method_context(WPE_VIEW_ANDROID(view));
}

void wpe_input_method_context_android_set_focus_callbacks(WPEInputMethodContext* context,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    auto* imContext = WPE_INPUT_METHOD_CONTEXT_ANDROID(context);
    imContext->focusInCallback = focusInCallback;
    imContext->focusOutCallback = focusOutCallback;
    imContext->callbackUserData = userData;
    Logging::logDebug("WPEInputMethodContextAndroid::set_focus_callbacks(%p, %p, %p, %p)", context, focusInCallback,
        focusOutCallback, userData);
}

void wpe_input_method_context_android_set_focus_callbacks_for_view(WPEView* view,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));
    auto* viewAndroid = WPE_VIEW_ANDROID(view);

    if (auto* context = wpe_view_android_get_input_method_context(viewAndroid)) {
        wpe_input_method_context_android_set_focus_callbacks(context, focusInCallback, focusOutCallback, userData);
        return;
    }

    wpe_view_android_set_pending_focus_callbacks(viewAndroid, focusInCallback, focusOutCallback, userData);
    Logging::logDebug("WPEInputMethodContextAndroid::set_focus_callbacks_for_view(%p) - stored as pending", view);
}

void wpe_input_method_context_android_commit_text(WPEInputMethodContext* context, const char* text)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    auto* view = wpe_input_method_context_get_view(context);
    Logging::logDebug("WPEInputMethodContextAndroid::commit_text(%p, '%s') view=%p", context, text, view);

    auto* viewContext = wpe_input_method_context_android_get_for_view(view);
    if (viewContext != context) {
        Logging::logError("WPEInputMethodContextAndroid::commit_text - mismatch view=%p this=%p", viewContext, context);
    }

    g_signal_emit_by_name(context, "committed", text);
    Logging::logDebug("WPEInputMethodContextAndroid::commit_text signal emitted");
}

void wpe_input_method_context_android_delete_surrounding(WPEInputMethodContext* context, int offset, unsigned int count)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    Logging::logDebug("WPEInputMethodContextAndroid::delete_surrounding(%p, %d, %u)", context, offset, count);
    g_signal_emit_by_name(context, "delete-surrounding", offset, count);
}
