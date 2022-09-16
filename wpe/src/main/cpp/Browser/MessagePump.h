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

#include <glib.h>
#include <memory>
#include <vector>

struct ALooper;

class MessagePump {
public:
    MessagePump();

    MessagePump(MessagePump&&) = delete;
    MessagePump& operator=(MessagePump&&) = delete;
    MessagePump(const MessagePump&) = delete;
    MessagePump& operator=(const MessagePump&) = delete;

    ~MessagePump();

    void quit();

    void invoke(void (*callback)(void*), void* callbackData, void (*destroy)(void*));

    void onCollectEventsLooperCallback(int fd, int events);
    void onDispatchLooperCallback();

private:
    void prepare();
    void dispatch();

    ALooper* m_looper = nullptr;
    int m_dispatchFd = 0;

    GMainContext* m_context = nullptr;
    gint m_maxPriority = 0;
    GPollFD* m_pollFds = nullptr;
    gint m_pollFdsSize = 1;

    struct LooperGLibPollFd {
        int fd;
        GPollFD* pfd;
        int ref;
    };

    std::vector<LooperGLibPollFd> m_looperGlibPollFds;
};
