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

#include <cstddef>
#include <cstdint>
#include <jni.h>

namespace WebKit {

// The pixels of a WebKitImage go into Android bitmaps with these.

struct ImageSize {
    int width;
    int height;
};

// Whether WebKit's buffer holds height rows of width BGRA pixels at the given stride.
bool isValidImageLayout(int width, int height, size_t stride, size_t sourceSize);

// The size of a width by height image scaled down to fit in maxWidth by maxHeight, keeping its aspect ratio. An image
// that already fits keeps its size, and a limit of 0 means no limit on that side.
ImageSize scaledImageSize(int width, int height, int maxWidth, int maxHeight);

// Fills bitmap, an RGBA_8888 Android bitmap of the size scaled, from scaledImageSize(), with WebKit's premultiplied
// BGRA pixels, scaled down if it is smaller. Returns false, after logging why, if the bitmap is not such a bitmap or
// its pixels cannot be locked. Call only after validating the source layout with isValidImageLayout().
bool fillBitmap(
    JNIEnv* env, jobject bitmap, const uint8_t* source, int width, int height, size_t sourceStride, ImageSize scaled);

} // namespace WebKit
