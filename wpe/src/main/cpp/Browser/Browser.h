#pragma once

#include "ExportedBuffer.h"
#include "MessagePump.h"
#include "Page.h"
#include "PageEventObserver.h"
#include "PageSettings.h"

#include <functional>
#include <string>
#include <unordered_map>

#include <wpe/webkit.h>
#include <wpe/wpe.h>

struct ANativeWindow;

class Browser final {
public:
    static Browser& getInstance()
    {
        static std::unique_ptr<Browser> singleton(new Browser());
        return *singleton;
    }

    Browser(Browser&&) = delete;
    Browser& operator=(Browser&&) = delete;
    Browser(const Browser&) = delete;
    Browser& operator=(const Browser&) = delete;

    ~Browser() { shut(); }

    void init();
    void shut();

    void invokeOnUiThread(void (*callback)(void*), void* callbackData, void (*destroy)(void*));

    void handleExportedBuffer(Page& page, std::shared_ptr<ExportedBuffer>&& exportedBuffer);

private:
    Browser() = default;

    std::unique_ptr<MessagePump> m_messagePump;
};
