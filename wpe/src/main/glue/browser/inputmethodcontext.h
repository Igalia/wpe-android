# pragma once

#include <wpe/webkit.h>
#include <memory>

#include "pageeventobserver.h"

G_BEGIN_DECLS

#define TYPE_INPUT_METHOD_CONTEXT (input_method_context_get_type())

G_DECLARE_DERIVABLE_TYPE(InputMethodContext, input_method_context, WPE_ANDROID, INPUT_METHOD_CONTEXT, WebKitInputMethodContext)

struct _InputMethodContextClass {
    WebKitInputMethodContextClass parent_class;
};

WebKitInputMethodContext *input_method_context_new(std::shared_ptr<PageEventObserver>);
void input_method_context_set_content(WebKitInputMethodContext*, const char c);
void input_method_context_delete_content(WebKitInputMethodContext*, int offset);

G_END_DECLS