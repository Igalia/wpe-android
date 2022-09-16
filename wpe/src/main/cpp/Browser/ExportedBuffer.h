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
#include <cstdint>
#include <memory>

struct ExportedBuffer final : public std::enable_shared_from_this<ExportedBuffer> {
    AHardwareBuffer* buffer;

    uint32_t poolID;
    uint32_t bufferID;

    struct {
        uint32_t width;
        uint32_t height;
    } size;

    ExportedBuffer(AHardwareBuffer* buffer, uint32_t poolID, uint32_t bufferID)
        : buffer(buffer)
        , poolID(poolID)
        , bufferID(bufferID)
        , size({0, 0})
    {
        if (buffer) {
            AHardwareBuffer_acquire(buffer);

            AHardwareBuffer_Desc desc;
            AHardwareBuffer_describe(buffer, &desc);
            size = {desc.width, desc.height};
        }
    }

    ExportedBuffer(ExportedBuffer&&) = delete;
    ExportedBuffer& operator=(ExportedBuffer&&) = delete;
    ExportedBuffer(const ExportedBuffer&) = delete;
    ExportedBuffer& operator=(const ExportedBuffer&) = delete;

    ~ExportedBuffer()
    {
        if (buffer)
            AHardwareBuffer_release(buffer);
    }
};
