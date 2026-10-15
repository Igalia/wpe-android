/**
 * Copyright (C) 2026 Igalia S.L.
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

#include <android/surface_control.h>
#include <cstdint>

// Presentation timing of every frame for system traces: when wpeview applied the buffer, when its
// rendering finished on the GPU, when SurfaceFlinger latched it and when it reached the display.
// Each frame ends up as one "WPEFrame" trace section carrying these CLOCK_MONOTONIC times, so a
// trace tells when each frame was on screen without a screen recording. Does nothing unless the
// app is being traced.
namespace WPEFrameTiming {

struct Frame {
    uint64_t number {0}; // 0 when the app is not being traced.
    int64_t applyTime {0};
};

// Call right before applying the transaction that carries a new buffer.
Frame willApply();

// Call from the transaction's OnComplete callback.
void didComplete(const Frame&, ASurfaceTransactionStats*, ASurfaceControl*);

} // namespace WPEFrameTiming
