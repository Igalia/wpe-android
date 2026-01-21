/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <memory>

#include "ScopedFD.h"

typedef struct _WPEBufferAndroid WPEBufferAndroid;

class Renderer {
public:
    Renderer() = default;
    virtual ~Renderer() = default;

    Renderer(Renderer&&) = default;
    Renderer& operator=(Renderer&&) = default;
    Renderer(const Renderer&) = default;
    Renderer& operator=(const Renderer&) = default;

    virtual uint32_t width() const noexcept = 0;
    virtual uint32_t height() const noexcept = 0;

    virtual void onSurfaceCreated(ANativeWindow* window) noexcept = 0;
    virtual void onSurfaceChanged(int format, uint32_t width, uint32_t height) noexcept = 0;
    virtual void onSurfaceRedrawNeeded() noexcept = 0;
    virtual void onSurfaceDestroyed() noexcept = 0;

    virtual void commitBuffer(
        AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD)
        = 0;
};
