/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include "PageEventObserver.h"

#include <memory>
#include <wpe/webkit.h>

G_BEGIN_DECLS

#define TYPE_INPUT_METHOD_CONTEXT (input_method_context_get_type())

G_DECLARE_DERIVABLE_TYPE(
    InputMethodContext, input_method_context, WPE_ANDROID, INPUT_METHOD_CONTEXT, WebKitInputMethodContext)

struct _InputMethodContextClass {
    WebKitInputMethodContextClass parent_class;
};

WebKitInputMethodContext* input_method_context_new(std::shared_ptr<PageEventObserver> observer);
void input_method_context_set_content(WebKitInputMethodContext* context, const char c);
void input_method_context_delete_content(WebKitInputMethodContext* context, int offset);

G_END_DECLS
