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

#include "InputMethodContext.h"

#include "Logging.h"

enum InputMethodContextProperty {
    PROP_OBSERVER = 1,
    N_PROPERTIES
};

static GParamSpec* obj_properties[N_PROPERTIES] = {
    nullptr,
};

struct InputMethodContextPrivate {
    PageEventObserver* m_observer;
};

G_DEFINE_TYPE_WITH_PRIVATE(InputMethodContext, input_method_context, WEBKIT_TYPE_INPUT_METHOD_CONTEXT)

#define PRIV(obj)                                                                                                      \
    ((InputMethodContextPrivate*)input_method_context_get_instance_private(WPE_ANDROID_INPUT_METHOD_CONTEXT(obj)))

static void input_method_context_notify_focus_in(WebKitInputMethodContext* context)
{
    InputMethodContextPrivate* priv = PRIV(context);
    ALOGV("input_method_context_notify_focus_in %p", priv->m_observer);

    if (priv->m_observer != nullptr) {
        priv->m_observer->onInputMethodContextIn();
    }
}

static void input_method_context_notify_focus_out(WebKitInputMethodContext* context)
{
    InputMethodContextPrivate* priv = PRIV(context);
    ALOGV("input_method_context_notify_focus_out %p", priv->m_observer);

    if (priv->m_observer != nullptr) {
        priv->m_observer->onInputMethodContextOut();
    }
}

static void input_method_context_set_property(
    GObject* object, guint property_id, const GValue* value, GParamSpec* pspec)
{
    InputMethodContextPrivate* self = PRIV(object);

    switch ((InputMethodContextProperty)property_id) {
    case PROP_OBSERVER:
        self->m_observer = static_cast<PageEventObserver*>(g_value_get_pointer(value));
        break;

    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, property_id, pspec);
        break;
    }
}

static void input_method_context_get_property(GObject* object, guint property_id, GValue* value, GParamSpec* pspec)
{
    InputMethodContextPrivate* self = PRIV(object);

    switch ((InputMethodContextProperty)property_id) {
    case PROP_OBSERVER:
        g_value_set_pointer(value, self->m_observer);
        break;

    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, property_id, pspec);
        break;
    }
}

static void input_method_context_class_init(InputMethodContextClass* klass)
{
    GObjectClass* object_class = G_OBJECT_CLASS(klass);

    object_class->set_property = input_method_context_set_property;
    object_class->get_property = input_method_context_get_property;

    obj_properties[PROP_OBSERVER] = g_param_spec_pointer("observer", "Observer", "Page event observer",
        static_cast<GParamFlags>(G_PARAM_CONSTRUCT_ONLY | G_PARAM_READWRITE));

    g_object_class_install_properties(object_class, N_PROPERTIES, obj_properties);

    WebKitInputMethodContextClass* input_method_context_class = WEBKIT_INPUT_METHOD_CONTEXT_CLASS(klass);
    input_method_context_class->notify_focus_in = input_method_context_notify_focus_in;
    input_method_context_class->notify_focus_out = input_method_context_notify_focus_out;

    // input_method_context_class->get_preedit = input_method_context_get_preedit;
    // input_method_context_class->notify_cursor_area = input_method_context_notify_cursor_area;
    // input_method_context_class->notify_surrounding = input_method_context_notify_surrounding;
    // input_method_context_class->reset = input_method_context_reset;
}

static void input_method_context_init(InputMethodContext* self) {}

WebKitInputMethodContext* input_method_context_new(std::shared_ptr<PageEventObserver> observer)
{
    return WEBKIT_INPUT_METHOD_CONTEXT(g_object_new(TYPE_INPUT_METHOD_CONTEXT, "observer", observer.get()));
}

void input_method_context_set_content(WebKitInputMethodContext* context, const char c)
{
    g_signal_emit_by_name(context, "committed", &c);
}

void input_method_context_delete_content(WebKitInputMethodContext* context, int offset)
{
    g_signal_emit_by_name(context, "delete-surrounding", offset, 1);
}
