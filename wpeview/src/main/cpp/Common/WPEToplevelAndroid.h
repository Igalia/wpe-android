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

#include <glib-object.h>
#include <wpe/wpe-platform.h>

#include "ScopedFD.h"
#include "WPEDisplayAndroid.h"
#include "WPEViewAndroid.h"

G_BEGIN_DECLS

#define WPE_TYPE_TOPLEVEL_ANDROID (wpe_toplevel_android_get_type())
G_DECLARE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE, TOPLEVEL_ANDROID, WPEToplevel)

class RendererSurfaceControl;
WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display);
void wpe_toplevel_android_resize(WPEToplevelAndroid* toplevel, int width, int height);
void wpe_toplevel_android_set_scale(WPEToplevel* toplevel, double scale);
void wpe_toplevel_android_set_toplevel_state(WPEToplevel* toplevel, WPEToplevelState state);
void wpe_toplevel_android_set_renderer(
    WPEToplevelAndroid* toplevel, const std::shared_ptr<RendererSurfaceControl>& renderer);
void wpe_toplevel_android_on_surface_created(WPEToplevelAndroid* toplevel, struct ANativeWindow* window);
void wpe_toplevel_android_on_surface_changed(WPEToplevelAndroid* toplevel, int format, uint32_t width, uint32_t height);
void wpe_toplevel_android_on_surface_redraw_needed(WPEToplevelAndroid* toplevel);
void wpe_toplevel_android_on_surface_destroyed(WPEToplevelAndroid* toplevel);
void wpe_toplevel_android_commit_buffer(WPEToplevelAndroid* toplevel, AHardwareBuffer* hardwareBuffer,
    WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD);
void wpe_toplevel_android_set_visible_view(WPEToplevelAndroid* toplevel, WPEViewAndroid* visibleView);

G_END_DECLS
