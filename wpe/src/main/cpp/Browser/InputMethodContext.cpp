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

namespace {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"
G_DECLARE_DERIVABLE_TYPE(InternalInputMethodContext, internal_input_method_context, WPE_ANDROID,
    INTERNAL_INPUT_METHOD_CONTEXT, WebKitInputMethodContext);
#pragma clang diagnostic pop

struct _InternalInputMethodContextClass {
    WebKitInputMethodContextClass m_parentClass;
};

enum class InternalInputMethodContextProperty : guint {
    ObserverPropertyId = 1,
    NumberOfProperties
};

struct InternalInputMethodContextPrivate {
    InputMethodContextObserver* m_observer;
};

G_DEFINE_TYPE_WITH_PRIVATE(InternalInputMethodContext, internal_input_method_context, WEBKIT_TYPE_INPUT_METHOD_CONTEXT);

inline InternalInputMethodContextPrivate* getInternalInputMethodContextPrivate(GObject* object) noexcept
{
    return reinterpret_cast<InternalInputMethodContextPrivate*>(
        internal_input_method_context_get_instance_private(WPE_ANDROID_INTERNAL_INPUT_METHOD_CONTEXT(object)));
}

void setProperty(GObject* object, guint propertyId, const GValue* value, GParamSpec* paramSpec) noexcept
{
    InternalInputMethodContextPrivate* ctx = getInternalInputMethodContextPrivate(object);
    switch (static_cast<InternalInputMethodContextProperty>(propertyId)) {
    case InternalInputMethodContextProperty::ObserverPropertyId:
        ctx->m_observer = reinterpret_cast<InputMethodContextObserver*>(g_value_get_pointer(value));
        break;

    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, propertyId, paramSpec);
        break;
    }
}

void getProperty(GObject* object, guint propertyId, GValue* value, GParamSpec* paramSpec) noexcept
{
    InternalInputMethodContextPrivate* ctx = getInternalInputMethodContextPrivate(object);
    switch (static_cast<InternalInputMethodContextProperty>(propertyId)) {
    case InternalInputMethodContextProperty::ObserverPropertyId:
        g_value_set_pointer(value, ctx->m_observer);
        break;

    default:
        G_OBJECT_WARN_INVALID_PROPERTY_ID(object, propertyId, paramSpec);
        break;
    }
}

void notifyFocusIn(WebKitInputMethodContext* context) noexcept
{
    InternalInputMethodContextPrivate* ctx = getInternalInputMethodContextPrivate(G_OBJECT(context));
    Logging::logDebug("internal_input_method_context_notify_focus_in %p", ctx->m_observer);
    if (ctx->m_observer != nullptr)
        ctx->m_observer->onInputMethodContextIn();
}

void notifyFocusOut(WebKitInputMethodContext* context) noexcept
{
    InternalInputMethodContextPrivate* ctx = getInternalInputMethodContextPrivate(G_OBJECT(context));
    Logging::logDebug("internal_input_method_context_notify_focus_out %p", ctx->m_observer);
    if (ctx->m_observer != nullptr)
        ctx->m_observer->onInputMethodContextOut();
}

void internal_input_method_context_class_init(InternalInputMethodContextClass* klass)
{
    GObjectClass* objectKlass = G_OBJECT_CLASS(klass);
    objectKlass->set_property = setProperty;
    objectKlass->get_property = getProperty;

    static GParamSpec* s_properties[static_cast<size_t>(InternalInputMethodContextProperty::NumberOfProperties)]
        = {nullptr,
            g_param_spec_pointer("observer", "Observer", "Page event observer",
                static_cast<GParamFlags>(G_PARAM_CONSTRUCT_ONLY | G_PARAM_READWRITE))}; // NOLINT(hicpp-signed-bitwise)
    g_object_class_install_properties(
        objectKlass, static_cast<guint>(InternalInputMethodContextProperty::NumberOfProperties), s_properties);

    WebKitInputMethodContextClass* webkitInputMethodContextKlass = WEBKIT_INPUT_METHOD_CONTEXT_CLASS(klass);
    webkitInputMethodContextKlass->notify_focus_in = notifyFocusIn;
    webkitInputMethodContextKlass->notify_focus_out = notifyFocusOut;
}

void internal_input_method_context_init(InternalInputMethodContext* /*self*/) {}
} // namespace

InputMethodContext::InputMethodContext(InputMethodContextObserver* observer)
    : m_observer(observer)
    , m_webKitInputMethodContext({WEBKIT_INPUT_METHOD_CONTEXT(g_object_new(
                                      internal_input_method_context_get_type(), "observer", m_observer, nullptr)),
          [](auto* ptr) { g_object_unref(ptr); }})
{
}

void InputMethodContext::setContent(const char* utf8Content) const noexcept
{
    g_signal_emit_by_name(m_webKitInputMethodContext.get(), "committed", utf8Content);
}

void InputMethodContext::deleteContent(int offset) const noexcept
{
    g_signal_emit_by_name(m_webKitInputMethodContext.get(), "delete-surrounding", offset, 1);
}
