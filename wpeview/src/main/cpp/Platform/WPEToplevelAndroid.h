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

#include <android/native_window.h>
#include <glib-object.h>
#include <wpe/wpe-platform.h>

G_BEGIN_DECLS

#define WPE_TYPE_TOPLEVEL_ANDROID (wpe_toplevel_android_get_type())
G_DECLARE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE, TOPLEVEL_ANDROID, WPEToplevel)

WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display, ANativeWindow* window);

void wpe_toplevel_android_on_window_created(WPEToplevelAndroid* toplevel, ANativeWindow* window);
void wpe_toplevel_android_on_window_destroyed(WPEToplevelAndroid* toplevel);

ANativeWindow* wpe_toplevel_android_get_window(WPEToplevelAndroid* toplevel);

G_END_DECLS
