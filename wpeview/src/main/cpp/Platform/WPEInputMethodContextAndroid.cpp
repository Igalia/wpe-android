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
};

G_DEFINE_FINAL_TYPE(WPEInputMethodContextAndroid, wpe_input_method_context_android, WPE_TYPE_INPUT_METHOD_CONTEXT)

static void wpeInputMethodContextAndroidGetPreeditString(
    WPEInputMethodContext* context, gchar** text, GList** underlines, guint* cursorOffset)
{
    UNUSED_PARAM(context);
    if (text)
        *text = nullptr;
    if (underlines)
        *underlines = nullptr;
    if (cursorOffset)
        *cursorOffset = 0;
}

static void wpe_input_method_context_android_class_init(WPEInputMethodContextAndroidClass* klass)
{
    WPEInputMethodContextClass* imContextClass = WPE_INPUT_METHOD_CONTEXT_CLASS(klass);
    imContextClass->get_preedit_string = wpeInputMethodContextAndroidGetPreeditString;
}

static void wpe_input_method_context_android_init(WPEInputMethodContextAndroid* context)
{
    UNUSED_PARAM(context);
}

WPEInputMethodContext* wpe_input_method_context_android_new(WPEView* view)
{
    g_return_val_if_fail(WPE_IS_VIEW(view), nullptr);
    return WPE_INPUT_METHOD_CONTEXT(g_object_new(WPE_TYPE_INPUT_METHOD_CONTEXT_ANDROID, "view", view, nullptr));
}
