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

#include "MessagePump.h"

#include "Logging.h"

#include <algorithm>
#include <cerrno>
#include <sys/eventfd.h>
#include <sys/timerfd.h>
#include <unistd.h>

namespace {
uint glibEventsToLooperEvents(gushort events) noexcept
{
    uint looperEvents = 0;
    if ((events & G_IO_IN) != 0)
        looperEvents |= ALOOPER_EVENT_INPUT;
    if ((events & G_IO_OUT) != 0)
        looperEvents |= ALOOPER_EVENT_OUTPUT;
    if ((events & G_IO_ERR) != 0)
        looperEvents |= ALOOPER_EVENT_ERROR;
    if ((events & G_IO_HUP) != 0)
        looperEvents |= ALOOPER_EVENT_HANGUP;
    if ((events & G_IO_NVAL) != 0)
        looperEvents |= ALOOPER_EVENT_INVALID;
    return looperEvents;
}
} // namespace

/*
 * Message pump implements integration between the GLib main loop
 * and the Android native ALooper run loop and events handling..
 *
 * Message pump "pumps" events and messages file descriptors from GLib context
 * and pushes them back to an Android looper for polling. When an event occurs,
 * the MessagePump is called from the Android looper and initiates GLib main
 * loop cycle steps (prepare, check, dispatch) and makes the appropriate calls
 * into GLib.
 *
 * This allows to run WPE UI on Android within the Android main UI thread.
 */
MessagePump::MessagePump()
{
    // The Android native ALooper uses epoll to poll file descriptors.
    // We use eventfd to inform GLib that it can start dispatching.
    m_dispatchFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);

    m_looper = ALooper_prepare(0);
    ALooper_acquire(m_looper);

    ALooper_addFd(m_looper, m_dispatchFd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, handleWakeUp,
        reinterpret_cast<void*>(this));

    // The main looper's poll timeout belongs to Java's MessageQueue, GLib timers need their own fd.
    m_timerFd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (m_timerFd < 0)
        Logging::logError("MessagePump: cannot create the timer fd (errno %d), GLib timers will be late", errno);
    else {
        ALooper_addFd(m_looper, m_timerFd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, handleWakeUp,
            reinterpret_cast<void*>(this));
    }

    m_context = g_main_context_ref(g_main_context_default());
    g_main_context_acquire(m_context);
    prepare();
}

MessagePump::~MessagePump()
{
    flush();

    for (const int fileDesc : m_looperFds)
        ALooper_removeFd(m_looper, fileDesc);
    m_looperFds.clear();

    m_pollFdsSize = 0;
    m_pollFdsCapacity = 0;
    if (m_pollFds != nullptr) {
        g_free(m_pollFds);
        m_pollFds = nullptr;
    }
    m_maxPriority = 0;

    g_main_context_release(m_context);
    g_main_context_unref(m_context);

    if (m_timerFd >= 0)
        ALooper_removeFd(m_looper, m_timerFd);
    ALooper_removeFd(m_looper, m_dispatchFd);
    ALooper_release(m_looper);
    m_looper = nullptr;

    if (m_timerFd >= 0)
        close(m_timerFd);
    m_timerFd = -1;
    close(m_dispatchFd);
    m_dispatchFd = -1;
}

void MessagePump::flush() const noexcept
{
    // Clear the eventfd and reset its counter to 0
    int64_t value = 0;
    read(m_dispatchFd, &value, sizeof(value));
    dispatch();
}

void MessagePump::prepare() noexcept
{
    g_main_context_prepare(m_context, &m_maxPriority);

    if (m_pollFds == nullptr) {
        m_pollFdsCapacity = 1; // There will be at least one fd in GMainContext
        m_pollFds = g_new(GPollFD, m_pollFdsCapacity);
    }

    gint timeout = 0;
    while ((m_pollFdsSize = g_main_context_query(m_context, m_maxPriority, &timeout, m_pollFds, m_pollFdsCapacity))
        > m_pollFdsCapacity) {
        g_free(m_pollFds);
        m_pollFdsCapacity = m_pollFdsSize;
        m_pollFds = g_new(GPollFD, m_pollFdsCapacity);
    }

    // Register them all again: epoll forgets a closed file, and a new one can reuse its number.
    m_polledFds.clear();
    for (int i = 0; i < m_pollFdsSize; ++i) {
        ALooper_addFd(
            m_looper, m_pollFds[i].fd, ALOOPER_POLL_CALLBACK,
            static_cast<int>(glibEventsToLooperEvents(m_pollFds[i].events)),
            +[](int fileDesc, int events, void* userData) -> int {
                UNUSED_PARAM(fileDesc);
                UNUSED_PARAM(events);
                reinterpret_cast<MessagePump*>(userData)->scheduleDispatch();
                return 1; // Continue listening for events
            },
            reinterpret_cast<void*>(this));
        m_polledFds.push_back(m_pollFds[i].fd);
    }

    // g_main_context_query() returns the file descriptors sorted.
    for (const int fileDesc : m_looperFds) {
        if (!std::binary_search(m_polledFds.begin(), m_polledFds.end(), fileDesc))
            ALooper_removeFd(m_looper, fileDesc);
    }
    std::swap(m_looperFds, m_polledFds);

    // The timeout is 0 when a source is ready already.
    if (timeout == 0)
        scheduleDispatch();
    else
        scheduleTimer(timeout);
}

void MessagePump::scheduleTimer(gint timeout) const noexcept
{
    if (m_timerFd < 0)
        return;

    // Disarm it when GLib has no timer (-1).
    struct itimerspec timerSpec {};
    if (timeout > 0) {
        timerSpec.it_value.tv_sec = timeout / 1000;
        timerSpec.it_value.tv_nsec = (timeout % 1000) * 1000000L;
    }
    timerfd_settime(m_timerFd, 0, &timerSpec, nullptr);
}

int MessagePump::handleWakeUp(int fileDesc, int events, void* userData) noexcept
{
    UNUSED_PARAM(events);
    auto* pump = reinterpret_cast<MessagePump*>(userData);

    uint64_t count = 0;
    const bool readCount = read(fileDesc, &count, sizeof(count)) == sizeof(count);
    if (fileDesc == pump->m_dispatchFd)
        pump->m_pendingDispatch = false;
    else if (!readCount || pump->m_pendingDispatch) {
        // The timer was re-armed since the looper saw it expire, or a dispatch is coming anyway.
        return 1;
    }

    pump->dispatch();
    pump->prepare();
    return 1; // Continue listening for events
}

void MessagePump::scheduleDispatch() noexcept
{
    if (!m_pendingDispatch) {
        uint64_t value = 1;
        write(m_dispatchFd, &value, sizeof(value));
        m_pendingDispatch = true;
    }
}

void MessagePump::dispatch() const noexcept
{
    // Poll again: the looper's events can be stale, handled already by a dispatch from an earlier
    // callback of the same poll, and GLib would then block in a source that isn't ready.
    g_main_context_get_poll_func(m_context)(m_pollFds, static_cast<guint>(m_pollFdsSize), 0);

    if (g_main_context_check(m_context, m_maxPriority, m_pollFds, m_pollFdsSize) == TRUE)
        g_main_context_dispatch(m_context);
}
