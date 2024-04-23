/**
 * Copyright (C) 2023 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

#include <unistd.h>

class ScopedFD final {
public:
    ScopedFD()
        : m_fd(-1)
    {
    }
    explicit ScopedFD(int fileDescriptor)
        : m_fd(fileDescriptor)
    {
    }
    ~ScopedFD()
    {
        if (m_fd != -1) {
            close();
        }
    }

    ScopedFD(ScopedFD&& other) = delete;
    ScopedFD& operator=(ScopedFD&& other) = delete;
    ScopedFD(const ScopedFD&) = delete;
    ScopedFD& operator=(const ScopedFD&) = delete;

    int get() const { return m_fd; }

    int release()
    {
        const int releasedFD = m_fd;
        m_fd = -1;
        return releasedFD;
    }

private:
    void close()
    {
        if (m_fd >= 0)
            ::close(m_fd);
        m_fd = -1;
    }

    int m_fd;
};
