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

#include <unordered_map>

// Map from WPEView to WPEInputMethodContext for lookup from JNI
static std::unordered_map<WPEView*, WPEInputMethodContext*> s_contextMap;

// Map from WPEView to focus callbacks (registered before context is created)
struct FocusCallbacks {
    WPEInputMethodContextAndroidFocusCallback focusInCallback = nullptr;
    WPEInputMethodContextAndroidFocusCallback focusOutCallback = nullptr;
    void* userData = nullptr;
};
static std::unordered_map<WPEView*, FocusCallbacks> s_pendingCallbacks;

struct _WPEInputMethodContextAndroid {
    WPEInputMethodContext parent;
};

typedef struct {
    WPEInputMethodContextAndroidFocusCallback focusInCallback;
    WPEInputMethodContextAndroidFocusCallback focusOutCallback;
    void* callbackUserData;
} WPEInputMethodContextAndroidPrivate;

G_DEFINE_TYPE_WITH_PRIVATE(
    WPEInputMethodContextAndroid, wpe_input_method_context_android, WPE_TYPE_INPUT_METHOD_CONTEXT)

static inline WPEInputMethodContextAndroidPrivate* getPrivate(WPEInputMethodContextAndroid* context)
{
    return static_cast<WPEInputMethodContextAndroidPrivate*>(
        wpe_input_method_context_android_get_instance_private(context));
}

static void wpeInputMethodContextAndroidDispose(GObject* object)
{
    auto* context = WPE_INPUT_METHOD_CONTEXT(object);
    auto* view = wpe_input_method_context_get_view(context);
    if (view != nullptr) {
        Logging::logDebug("WPEInputMethodContextAndroid::dispose - removing from map for view %p", view);
        s_contextMap.erase(view);
    }
    G_OBJECT_CLASS(wpe_input_method_context_android_parent_class)->dispose(object);
}

static void wpeInputMethodContextAndroidGetPreeditString(
    WPEInputMethodContext* /*context*/, char** text, GList** underlines, guint* cursorOffset)
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
    Logging::logDebug("WPEInputMethodContextAndroid::focus_in(%p) view=%p callback=%p", context, view,
        getPrivate(WPE_INPUT_METHOD_CONTEXT_ANDROID(context))->focusInCallback);
    auto* priv = getPrivate(WPE_INPUT_METHOD_CONTEXT_ANDROID(context));
    if (priv->focusInCallback != nullptr)
        priv->focusInCallback(priv->callbackUserData);
}

static void wpeInputMethodContextAndroidFocusOut(WPEInputMethodContext* context)
{
    Logging::logDebug("WPEInputMethodContextAndroid::focus_out(%p)", context);
    auto* priv = getPrivate(WPE_INPUT_METHOD_CONTEXT_ANDROID(context));
    if (priv->focusOutCallback != nullptr)
        priv->focusOutCallback(priv->callbackUserData);
}

static void wpeInputMethodContextAndroidReset(WPEInputMethodContext* /*context*/)
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

    // Store in map for later lookup from JNI
    s_contextMap[view] = context;
    Logging::logDebug("WPEInputMethodContextAndroid::new - added to map, now has %zu entries", s_contextMap.size());

    // Check if there are pending callbacks registered before this context was created
    auto it = s_pendingCallbacks.find(view);
    if (it != s_pendingCallbacks.end()) {
        auto* priv = getPrivate(WPE_INPUT_METHOD_CONTEXT_ANDROID(context));
        priv->focusInCallback = it->second.focusInCallback;
        priv->focusOutCallback = it->second.focusOutCallback;
        priv->callbackUserData = it->second.userData;
        s_pendingCallbacks.erase(it);
        Logging::logDebug("WPEInputMethodContextAndroid::new - applied pending callbacks");
    }

    return context;
}

WPEInputMethodContext* wpe_input_method_context_android_get_for_view(WPEView* view)
{
    auto it = s_contextMap.find(view);
    if (it != s_contextMap.end())
        return it->second;
    return nullptr;
}

void wpe_input_method_context_android_set_focus_callbacks(WPEInputMethodContext* context,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    auto* priv = getPrivate(WPE_INPUT_METHOD_CONTEXT_ANDROID(context));
    priv->focusInCallback = focusInCallback;
    priv->focusOutCallback = focusOutCallback;
    priv->callbackUserData = userData;
    Logging::logDebug("WPEInputMethodContextAndroid::set_focus_callbacks(%p, %p, %p, %p)", context, focusInCallback,
        focusOutCallback, userData);
}

void wpe_input_method_context_android_set_focus_callbacks_for_view(WPEView* view,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData)
{
    g_return_if_fail(view != nullptr);

    // Check if context already exists
    auto* context = wpe_input_method_context_android_get_for_view(view);
    if (context != nullptr) {
        wpe_input_method_context_android_set_focus_callbacks(context, focusInCallback, focusOutCallback, userData);
        return;
    }

    // Context doesn't exist yet - store callbacks for later
    s_pendingCallbacks[view] = {focusInCallback, focusOutCallback, userData};
    Logging::logDebug("WPEInputMethodContextAndroid::set_focus_callbacks_for_view(%p) - stored as pending", view);
}

void wpe_input_method_context_android_commit_text(WPEInputMethodContext* context, const char* text)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    auto* view = wpe_input_method_context_get_view(context);
    Logging::logDebug("WPEInputMethodContextAndroid::commit_text(%p, '%s') view=%p", context, text, view);

    // Check if this context is in our map (sanity check)
    auto* mapContext = wpe_input_method_context_android_get_for_view(view);
    if (mapContext != context) {
        Logging::logError(
            "WPEInputMethodContextAndroid::commit_text - context mismatch! map=%p, this=%p", mapContext, context);
    }

    g_signal_emit_by_name(context, "committed", text);
    Logging::logDebug("WPEInputMethodContextAndroid::commit_text - signal emitted");
}

void wpe_input_method_context_android_delete_surrounding(WPEInputMethodContext* context, int offset, unsigned int count)
{
    g_return_if_fail(WPE_IS_INPUT_METHOD_CONTEXT_ANDROID(context));
    Logging::logDebug("WPEInputMethodContextAndroid::delete_surrounding(%p, %d, %u)", context, offset, count);
    g_signal_emit_by_name(context, "delete-surrounding", offset, count);
}
