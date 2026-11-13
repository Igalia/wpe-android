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

#include "WPEToplevelAndroid.h"

#include <android/surface_control.h>
#include <glib-object.h>
#include <wpe/wpe-platform.h>

G_BEGIN_DECLS

#define WPE_TYPE_VIEW_ANDROID (wpe_view_android_get_type())
G_DECLARE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE, VIEW_ANDROID, WPEView)

WPEView* wpe_view_android_new(WPEDisplay* display);

// For WPEToplevelAndroid, which shows the layers of its views under its root.
void wpe_view_android_move_layer(WPEView* view, WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction);
void wpe_view_android_hide_layer(WPEView* view, WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction);

G_END_DECLS
