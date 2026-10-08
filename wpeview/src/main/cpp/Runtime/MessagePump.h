/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

#include <android/looper.h>
#include <glib.h>
#include <vector>

class MessagePump final {
public:
    MessagePump();

    MessagePump(MessagePump&&) = delete;
    MessagePump& operator=(MessagePump&&) = delete;
    MessagePump(const MessagePump&) = delete;
    MessagePump& operator=(const MessagePump&) = delete;

    ~MessagePump();

private:
    void flush() const noexcept;
    void prepare() noexcept;
    void scheduleDispatch() noexcept;
    void scheduleTimer(gint timeout) const noexcept;
    void dispatch() const noexcept;
    static int handleWakeUp(int fileDesc, int events, void* userData) noexcept;

    int m_dispatchFd = -1;
    int m_timerFd = -1;
    bool m_pendingDispatch = false;
    ALooper* m_looper = nullptr;

    GMainContext* m_context = nullptr;
    gint m_maxPriority = 0;
    GPollFD* m_pollFds = nullptr;
    gint m_pollFdsSize = 0;
    gint m_pollFdsCapacity = 0;

    std::vector<int> m_looperFds {}; // The file descriptors registered with the looper, sorted
    std::vector<int> m_polledFds {}; // Reused by prepare() to avoid allocating on every iteration
};
