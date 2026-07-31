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

#include <cstdint>

// Bridges to the Java WKRuntime for launching/terminating WebKit auxiliary processes as Android
// services. Used by WPEProcessManagerAndroid. processType is a Common/Environment.h ProcessType value.
bool wkRuntimeLaunchProcess(int64_t processId, int processType, int ipcSocketFd) noexcept;
void wkRuntimeTerminateProcess(int64_t processId) noexcept;

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

private:
    WKRuntime();

    friend class JNIWPERuntimeCache;
    void jniInit();
    void jniShut() noexcept;

    std::unique_ptr<MessagePump> m_messagePump {};
};
