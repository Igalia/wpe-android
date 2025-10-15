/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
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
#include <glib-object.h>
#include <wpe-platform/wpe/wpe-platform.h>

G_BEGIN_DECLS

#define WPE_TYPE_DISPLAY_ANDROID (wpe_display_android_get_type())
G_DECLARE_FINAL_TYPE(WPEDisplayAndroid, wpe_display_android, WPE, DISPLAY_ANDROID, WPEDisplay)

WPE_API WPEDisplay* wpe_display_android_new(void);

WPE_API WPEToplevel* wpe_display_android_get_toplevel(WPEDisplay* display);

typedef struct AHardwareBuffer AHardwareBuffer;

WPE_API AHardwareBuffer* wpe_display_android_allocate_buffer(
    WPEDisplay* display, int width, int height, guint32 format);

G_END_DECLS
