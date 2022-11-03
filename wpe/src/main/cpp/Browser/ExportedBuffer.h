/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
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

#include <android/hardware_buffer.h>

class ExportedBuffer final {
public:
    ExportedBuffer(AHardwareBuffer* buffer, uint32_t poolId, uint32_t bufferId)
        : m_buffer(buffer)
        , m_poolId(poolId)
        , m_bufferId(bufferId)
    {
        if (m_buffer != nullptr) {
            AHardwareBuffer_acquire(m_buffer);
            AHardwareBuffer_Desc desc = {};
            AHardwareBuffer_describe(m_buffer, &desc);
            m_size = {desc.width, desc.height};
        }
    }

    ExportedBuffer(ExportedBuffer&&) = delete;
    ExportedBuffer& operator=(ExportedBuffer&&) = delete;
    ExportedBuffer(const ExportedBuffer&) = delete;
    ExportedBuffer& operator=(const ExportedBuffer&) = delete;

    ~ExportedBuffer()
    {
        if (m_buffer != nullptr)
            AHardwareBuffer_release(m_buffer);
    }

    AHardwareBuffer* buffer() const noexcept { return m_buffer; }
    uint32_t poolId() const noexcept { return m_poolId; }
    uint32_t bufferId() const noexcept { return m_bufferId; }
    uint32_t width() const noexcept { return m_size.m_width; }
    uint32_t height() const noexcept { return m_size.m_height; }

private:
    AHardwareBuffer* m_buffer;
    uint32_t m_poolId;
    uint32_t m_bufferId;
    struct {
        uint32_t m_width;
        uint32_t m_height;
    } m_size = {};
};
