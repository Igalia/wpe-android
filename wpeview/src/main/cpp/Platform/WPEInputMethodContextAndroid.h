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

#pragma once

#include <glib-object.h>
#include <wpe/wpe-platform.h>

G_BEGIN_DECLS

#define WPE_TYPE_INPUT_METHOD_CONTEXT_ANDROID (wpe_input_method_context_android_get_type())
G_DECLARE_FINAL_TYPE(WPEInputMethodContextAndroid, wpe_input_method_context_android, WPE, INPUT_METHOD_CONTEXT_ANDROID,
    WPEInputMethodContext)

using WPEInputMethodContextAndroidFocusCallback = void (*)(gpointer userData);
using WPEInputMethodContextAndroidResetCallback = void (*)(gpointer userData);

// The text is UTF-8 and not nul-terminated; the indices are byte offsets into it. The text is null for password
// and PIN fields, which don't give it out.
using WPEInputMethodContextAndroidSurroundingCallback
    = void (*)(const char* text, unsigned length, unsigned cursorIndex, unsigned selectionIndex, gpointer userData);

WPEInputMethodContext* wpe_input_method_context_android_new(WPEView* view);

WPEInputMethodContextAndroid* wpe_input_method_context_android_from_view(WPEView* view);

void wpe_input_method_context_android_set_focus_callbacks(WPEInputMethodContextAndroid* context,
    WPEInputMethodContextAndroidFocusCallback focusIn, WPEInputMethodContextAndroidFocusCallback focusOut,
    gpointer userData);

// Called when WebKit ends the composition itself, for example when the page is tapped.
void wpe_input_method_context_android_set_reset_callback(
    WPEInputMethodContextAndroid* context, WPEInputMethodContextAndroidResetCallback callback, gpointer userData);

void wpe_input_method_context_android_set_surrounding_callback(
    WPEInputMethodContextAndroid* context, WPEInputMethodContextAndroidSurroundingCallback callback, gpointer userData);

void wpe_input_method_context_android_commit_text(WPEInputMethodContextAndroid* context, const char* text);

void wpe_input_method_context_android_delete_surrounding(
    WPEInputMethodContextAndroid* context, int offset, unsigned int count);

// cursorOffset is in UTF-16 code units from the start of text, as WebKit counts it.
void wpe_input_method_context_android_set_preedit(
    WPEInputMethodContextAndroid* context, const char* text, unsigned int cursorOffset);

void wpe_input_method_context_android_finish_preedit(WPEInputMethodContextAndroid* context);

G_END_DECLS
