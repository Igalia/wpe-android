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

#pragma once

#include <glib-object.h>
#include <wpe/wpe-platform.h>

G_BEGIN_DECLS

#define WPE_TYPE_INPUT_METHOD_CONTEXT_ANDROID (wpe_input_method_context_android_get_type())
G_DECLARE_FINAL_TYPE(WPEInputMethodContextAndroid, wpe_input_method_context_android, WPE, INPUT_METHOD_CONTEXT_ANDROID,
    WPEInputMethodContext)

// Callback type for focus notifications
typedef void (*WPEInputMethodContextAndroidFocusCallback)(void* userData);

WPE_API WPEInputMethodContext* wpe_input_method_context_android_new(WPEView* view);

WPE_API void wpe_input_method_context_android_set_focus_callbacks(WPEInputMethodContext* context,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData);
WPE_API void wpe_input_method_context_android_commit_text(WPEInputMethodContext* context, const char* text);
WPE_API void wpe_input_method_context_android_delete_surrounding(
    WPEInputMethodContext* context, int offset, unsigned int count);

G_END_DECLS
