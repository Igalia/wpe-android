/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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
