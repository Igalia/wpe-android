/*
 * Copyright (C) 2015, 2016 Igalia S.L.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "ipc.h"

#include <cstdio>
#include <sys/types.h>
#include <sys/socket.h>

namespace IPC {

Host::Host() = default;

void Host::initialize(Handler& handler)
{
    m_handler = &handler;

    int sockets[2];
    int ret = socketpair(AF_UNIX, SOCK_STREAM, 0, sockets);
    if (ret == -1)
        return;

    m_socket = g_socket_new_from_fd(sockets[0], nullptr);
    if (!m_socket) {
        close(sockets[0]);
        close(sockets[1]);
        return;
    }

    m_source = g_socket_create_source(m_socket, G_IO_IN, nullptr);
    g_source_set_callback(m_source, reinterpret_cast<GSourceFunc>(socketCallback), this, nullptr);
    g_source_set_priority(m_source, G_PRIORITY_HIGH + 30);
    g_source_attach(m_source, g_main_context_get_thread_default());

    m_clientFd = sockets[1];
}

void Host::deinitialize()
{
    if (m_clientFd != -1)
        close(m_clientFd);

    if (m_source)
        g_source_destroy(m_source);
    if (m_socket)
        g_object_unref(m_socket);

    m_handler = nullptr;
}

int Host::releaseClientFD()
{
    return dup(m_clientFd);
}

void Host::sendMessage(char* data, size_t size)
{
    g_socket_send(m_socket, data, size, nullptr, nullptr);
}

gboolean Host::socketCallback(GSocket* socket, GIOCondition condition, gpointer data)
{
    if (!(condition & G_IO_IN))
        return TRUE;

    auto& host = *static_cast<Host*>(data);

    char* buffer = g_new0(char, Message::size);
    GInputVector vector = { buffer, Message::size };
    gssize len = g_socket_receive_message(socket, nullptr, &vector, 1,
        nullptr, nullptr, nullptr, nullptr, nullptr);

    // If nothing is read, give up.
    if (len == -1) {
        g_free(buffer);
        return FALSE;
    }

    if (len == Message::size)
        host.m_handler->handleMessage(buffer, Message::size);

    g_free(buffer);
    return TRUE;
}

Client::Client() = default;

void Client::initialize(Handler& handler, int fd)
{
    m_handler = &handler;

    m_socket = g_socket_new_from_fd(fd, nullptr);
    if (!m_socket)
        return;

    m_source = g_socket_create_source(m_socket, G_IO_IN, nullptr);
    g_source_set_callback(m_source, reinterpret_cast<GSourceFunc>(socketCallback), this, nullptr);
    g_source_set_priority(m_source, G_PRIORITY_HIGH + 30);
    g_source_attach(m_source, g_main_context_get_thread_default());
}

void Client::deinitialize()
{
    if (m_source)
        g_source_destroy(m_source);
    if (m_socket)
        g_object_unref(m_socket);

    m_handler = nullptr;
}

gboolean Client::socketCallback(GSocket* socket, GIOCondition condition, gpointer data)
{
    if (!(condition & G_IO_IN))
        return TRUE;

    auto& client = *reinterpret_cast<Client*>(data);

    char* buffer = g_new0(char, Message::size);
    gssize len = g_socket_receive(socket, buffer, Message::size, nullptr, nullptr);

    if (len == Message::size)
        client.m_handler->handleMessage(buffer, Message::size);

    g_free(buffer);
    return TRUE;
}

void Client::sendMessage(char* data, size_t size)
{
    g_socket_send(m_socket, data, size, nullptr, nullptr);
}

} // namespace IPC
