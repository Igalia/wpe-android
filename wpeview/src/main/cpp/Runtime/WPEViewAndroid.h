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

G_BEGIN_DECLS

#define WPE_TYPE_VIEW_ANDROID (wpe_view_android_get_type())
G_DECLARE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE, VIEW_ANDROID, WPEView)

typedef void (*WPEInputMethodContextAndroidFocusCallback)(void* userData);

G_END_DECLS

#include <memory>

struct ANativeWindow;
class RendererSurfaceControl;

WPEView* wpe_view_android_new(WPEDisplay* display);
void wpe_view_android_resize(WPEViewAndroid* view, int width, int height);
void wpe_view_android_dispatch_event(WPEViewAndroid* view, WPEEvent* event);
void wpe_view_android_set_scale(WPEViewAndroid* view, double scale);
void wpe_view_android_set_toplevel_state(WPEViewAndroid* view, WPEToplevelState state);
void wpe_view_android_set_renderer(WPEViewAndroid* view, const std::shared_ptr<RendererSurfaceControl>& renderer);
void wpe_view_android_on_surface_created(WPEViewAndroid* view, struct ANativeWindow* window);
void wpe_view_android_on_surface_changed(WPEViewAndroid* view, int format, uint32_t width, uint32_t height);
void wpe_view_android_on_surface_redraw_needed(WPEViewAndroid* view);
void wpe_view_android_on_surface_destroyed(WPEViewAndroid* view);
void wpe_view_android_set_input_method_context(WPEViewAndroid* view, WPEInputMethodContext* context);
void wpe_view_android_set_pending_focus_callbacks(WPEViewAndroid* view,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData);
void wpe_view_android_apply_pending_focus_callbacks(WPEViewAndroid* view, WPEInputMethodContext* context);
WPEInputMethodContext* wpe_view_android_get_input_method_context(WPEViewAndroid* view);
