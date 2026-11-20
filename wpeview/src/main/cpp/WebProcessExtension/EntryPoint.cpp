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

// WPEView's extension of the web process. WebKit's injected bundle loads it from the folder that WPEView gives with
// webkit_web_context_set_web_process_extensions_directory(), in every web process.

#include "AutofillScript.h"

#include <android/log.h>
#include <jsc/jsc.h>
#include <wpe/webkit-web-process-extension.h>

namespace {

// Both must match WebKitWebContext.cpp and WebKitWebView.cpp in WPEView.
constexpr const char* AUTOFILL_WORLD_NAME = "wpeview-autofill";
constexpr const char* AUTOFILL_MESSAGE_NAME = "wpeview.autofill";

WebKitWebProcessExtension* s_extension = nullptr;
WebKitScriptWorld* s_autofillWorld = nullptr;

void logError(const char* message)
{
    __android_log_write(ANDROID_LOG_ERROR, "WPEViewExtension", message);
}

// Called by the script as wpeviewPost(kind, json). The page id goes with the message, as the web context hears all of
// its pages.
void postAutofillMessage(const char* kind, const char* json, gpointer userData)
{
    if (!kind || !json)
        return;
    const guint64 pageId = *static_cast<guint64*>(userData);
    webkit_web_process_extension_send_message_to_context(s_extension,
        webkit_user_message_new(AUTOFILL_MESSAGE_NAME, g_variant_new("(tss)", pageId, kind, json)), nullptr, nullptr,
        nullptr);
}

// Called by the script as wpeviewAutoFill(element, value). WebKit sets the value as typed by the user, with its events,
// and marks the field as filled.
void autoFill(JSCValue* element, const char* value, gpointer /*userData*/)
{
    if (value && jsc_value_is_object(element))
        webkit_web_form_manager_input_element_auto_fill(element, value);
}

void reportException(JSCContext* context)
{
    if (JSCException* exception = jsc_context_get_exception(context)) {
        g_autofree char* report = jsc_exception_report(exception);
        logError(report);
        jsc_context_clear_exception(context);
    }
}

void onWindowObjectCleared(WebKitScriptWorld* world, WebKitWebPage* page, WebKitFrame* frame, gpointer /*userData*/)
{
    if (!webkit_frame_is_main_frame(frame))
        return;

    g_autoptr(JSCContext) context = webkit_frame_get_js_context_for_script_world(frame, world);
    auto* pageId = g_new(guint64, 1);
    *pageId = webkit_web_page_get_id(page);
    g_autoptr(JSCValue) post = jsc_value_new_function(context, "wpeviewPost", G_CALLBACK(postAutofillMessage), pageId,
        g_free, G_TYPE_NONE, 2, G_TYPE_STRING, G_TYPE_STRING);
    jsc_context_set_value(context, "wpeviewPost", post);
    g_autoptr(JSCValue) fill = jsc_value_new_function(context, "wpeviewAutoFill", G_CALLBACK(autoFill), nullptr,
        nullptr, G_TYPE_NONE, 2, JSC_TYPE_VALUE, G_TYPE_STRING);
    jsc_context_set_value(context, "wpeviewAutoFill", fill);

    g_autoptr(JSCValue) result = jsc_context_evaluate(context, Autofill::SCRIPT, -1);
    reportException(context);
}

// The form manager hears the forms of every frame; the script runs in the main frame only.
void onFormSubmit(
    WebKitWebFormManager* /*formManager*/, JSCValue* form, WebKitFrame* sourceFrame, WebKitFrame* /*targetFrame*/)
{
    if (!webkit_frame_is_main_frame(sourceFrame))
        return;

    JSCContext* context = jsc_value_get_context(form);
    g_autoptr(JSCValue) submit = jsc_context_get_value(context, "wpeviewSubmit");
    if (!jsc_value_is_function(submit))
        return;
    g_autoptr(JSCValue) result = jsc_value_function_call(submit, JSC_TYPE_VALUE, form, G_TYPE_NONE);
    reportException(context);
}

void onPageCreated(WebKitWebProcessExtension* /*extension*/, WebKitWebPage* page, gpointer /*userData*/)
{
    // Only the form managers that exist when a form is sent hear it, so it is taken now. will-send-submit-event comes
    // also when the page cancels the submit to send the form itself, and will-submit-form comes for form.submit().
    WebKitWebFormManager* formManager = webkit_web_page_get_form_manager(page, s_autofillWorld);
    g_signal_connect(formManager, "will-send-submit-event", G_CALLBACK(onFormSubmit), nullptr);
    g_signal_connect(formManager, "will-submit-form", G_CALLBACK(onFormSubmit), nullptr);
}

} // namespace

extern "C" G_MODULE_EXPORT void webkit_web_process_extension_initialize(WebKitWebProcessExtension* extension)
{
    s_extension = extension;
    s_autofillWorld = webkit_script_world_new_with_name(AUTOFILL_WORLD_NAME);
    g_signal_connect(s_autofillWorld, "window-object-cleared", G_CALLBACK(onWindowObjectCleared), nullptr);
    g_signal_connect(extension, "page-created", G_CALLBACK(onPageCreated), nullptr);
}
