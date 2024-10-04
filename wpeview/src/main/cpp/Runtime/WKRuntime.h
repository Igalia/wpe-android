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

#include "MessagePump.h"

class WKRuntime final {
public:
    static void configureJNIMappings();

    static WKRuntime& instance() noexcept
    {
        static WKRuntime s_singleton;
        return s_singleton;
    }

    WKRuntime(WKRuntime&&) = delete;
    WKRuntime& operator=(WKRuntime&&) = delete;
    WKRuntime(const WKRuntime&) = delete;
    WKRuntime& operator=(const WKRuntime&) = delete;

    ~WKRuntime() { jniShut(); }

    void invokeOnUiThread(void (*onExec)(void*), void (*onDestroy)(void*), void* userData) const noexcept;

private:
    WKRuntime() = default;

    friend class JNIBrowserCache;
    void jniInit();
    void jniShut() noexcept;

    std::unique_ptr<MessagePump> m_messagePump {};
};
