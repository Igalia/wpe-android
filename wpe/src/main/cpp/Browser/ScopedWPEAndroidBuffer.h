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
#include <memory>
#include <wpe-android/view-backend.h>

#include "ScopedFD.h"

class ScopedWPEAndroidBuffer final {
public:
    ScopedWPEAndroidBuffer(WPEAndroidBuffer* buffer)
        : m_buffer(buffer)
    {
        if (m_buffer != nullptr) {
            m_hardwareBuffer = WPEAndroidBuffer_getAHardwareBuffer(m_buffer);
            AHardwareBuffer_acquire(m_hardwareBuffer);
            AHardwareBuffer_Desc desc = {};
            AHardwareBuffer_describe(m_hardwareBuffer, &desc);
            m_size = {desc.width, desc.height};
        }
    }

    ScopedWPEAndroidBuffer(ScopedWPEAndroidBuffer&&) = delete;
    ScopedWPEAndroidBuffer& operator=(ScopedWPEAndroidBuffer&&) = delete;
    ScopedWPEAndroidBuffer(const ScopedWPEAndroidBuffer&) = delete;
    ScopedWPEAndroidBuffer& operator=(const ScopedWPEAndroidBuffer&) = delete;

    ~ScopedWPEAndroidBuffer()
    {
        if (m_buffer != nullptr)
            AHardwareBuffer_release(m_hardwareBuffer);
    }

    WPEAndroidBuffer* wpeBuffer() const { return m_buffer; }
    AHardwareBuffer* buffer() const noexcept { return WPEAndroidBuffer_getAHardwareBuffer(m_buffer); }
    uint32_t width() const noexcept { return m_size.m_width; }
    uint32_t height() const noexcept { return m_size.m_height; }

    int getReleaseFenceFD() const { return m_releaseFenceFD->get(); }
    void setReleaseFenceFD(std::shared_ptr<ScopedFD> releaseFenceFD) { m_releaseFenceFD = std::move(releaseFenceFD); }

private:
    WPEAndroidBuffer* m_buffer = nullptr;
    ;
    AHardwareBuffer* m_hardwareBuffer = nullptr;
    std::shared_ptr<ScopedFD> m_releaseFenceFD;
    struct {
        uint32_t m_width;
        uint32_t m_height;
    } m_size = {};
};
