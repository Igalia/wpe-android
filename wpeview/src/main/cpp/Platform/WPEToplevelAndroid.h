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
#include <android/surface_control.h>
#include <glib-object.h>
#include <wpe/wpe-platform.h>

G_BEGIN_DECLS

#define WPE_TYPE_TOPLEVEL_ANDROID (wpe_toplevel_android_get_type())
G_DECLARE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE, TOPLEVEL_ANDROID, WPEToplevel)

using WPEToplevelAndroidFullscreenCallback = void (*)(gboolean fullscreen, gpointer userData);

WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display, ANativeWindow* window);

void wpe_toplevel_android_set_window(WPEToplevelAndroid* toplevel, ANativeWindow* window);
void wpe_toplevel_android_set_background_color(WPEToplevelAndroid* toplevel, float red, float green, float blue);
void wpe_toplevel_android_set_physical_size(WPEToplevelAndroid* toplevel, int width, int height);
void wpe_toplevel_android_set_window_focused(WPEToplevelAndroid* toplevel, gboolean focused);
void wpe_toplevel_android_set_screen(WPEToplevelAndroid* toplevel, WPEScreen* screen);
void wpe_toplevel_android_set_fullscreen(WPEToplevelAndroid* toplevel, gboolean fullscreen);
void wpe_toplevel_android_set_fullscreen_callback(
    WPEToplevelAndroid* toplevel, WPEToplevelAndroidFullscreenCallback callback, gpointer userData);
ASurfaceControl* wpe_toplevel_android_get_root_surface_control(WPEToplevelAndroid* toplevel);
ANativeWindow* wpe_toplevel_android_get_window(WPEToplevelAndroid* toplevel);

// For WPEViewAndroid, whose layer stays on screen for a moment after the view leaves the toplevel.
void wpe_toplevel_android_view_left(WPEToplevelAndroid* toplevel, WPEView* view, gboolean layerShown);
void wpe_toplevel_android_forget_leaving_view(WPEToplevelAndroid* toplevel, WPEView* view);
void wpe_toplevel_android_remove_leaving_layer(WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction);

G_END_DECLS
