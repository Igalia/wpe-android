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

#include "WPEViewAndroid.h"

#include "Logging.h"
#include "WPEDisplayAndroid.h"
#include "WPEInputMethodContextAndroid.h"
#include "WPEToplevelAndroid.h"

#include <android/hardware_buffer.h>

struct _WPEViewAndroid {
    WPEView parent;

    WPEInputMethodContext* inputMethodContext;
    struct {
        WPEInputMethodContextAndroidFocusCallback focusInCallback;
        WPEInputMethodContextAndroidFocusCallback focusOutCallback;
        void* userData;
    } pendingFocusCallbacks;
};

G_DEFINE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE_TYPE_VIEW)

static gboolean wpeViewAndroidRenderBuffer(
    WPEView* view, WPEBuffer* buffer, const WPERectangle*, guint nDamageRects, GError** error)
{
    Logging::logDebug("WPEViewAndroid::render_buffer(%p, %p, %u rects)", view, buffer, nDamageRects);

    if (!WPE_IS_BUFFER_ANDROID(buffer)) {
        g_set_error_literal(error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "Buffer is not a WPEBufferAndroid");
        return FALSE;
    }

    auto* bufferAndroid = WPE_BUFFER_ANDROID(buffer);
    AHardwareBuffer* ahb = wpe_buffer_android_get_hardware_buffer(bufferAndroid);
    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        const int renderingFence = wpe_buffer_take_rendering_fence(buffer);
        auto fenceFD = std::make_shared<ScopedFD>(renderingFence);
        wpe_toplevel_android_commit_buffer(WPE_TOPLEVEL_ANDROID(toplevel), ahb, bufferAndroid, fenceFD);
    } else {
        wpe_view_buffer_released(view, buffer);
        wpe_view_buffer_rendered(view, buffer);
    }

    Logging::logDebug("WPEViewAndroid: buffer committed successfully");

    return TRUE;
}

static void wpe_view_android_class_init(WPEViewAndroidClass* klass)
{
    WPEViewClass* viewClass = WPE_VIEW_CLASS(klass);

    viewClass->render_buffer = wpeViewAndroidRenderBuffer;
}

static void wpe_view_android_init(WPEViewAndroid* view)
{
    Logging::logDebug("WPEViewAndroid::init(%p)", view);
    view->inputMethodContext = nullptr;
    view->pendingFocusCallbacks = {};
}

WPEView* wpe_view_android_new(WPEDisplay* display)
{
    return WPE_VIEW(g_object_new(WPE_TYPE_VIEW_ANDROID, "display", display, nullptr));
}

void wpe_view_android_resize(WPEViewAndroid* view, int width, int height)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::resize(%p, %d, %d)", view, width, height);
    wpe_view_resized(WPE_VIEW(view), width, height);
}

void wpe_view_android_dispatch_event(WPEViewAndroid* view, WPEEvent* event)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));
    g_return_if_fail(event != nullptr);

    Logging::logDebug("WPEViewAndroid::dispatch_event(%p, %p)", view, event);
    wpe_view_event(WPE_VIEW(view), event);
}

void wpe_view_android_set_scale(WPEViewAndroid* view, double scale)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::set_scale(%p, %f)", view, scale);

    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        wpe_toplevel_scale_changed(toplevel, scale);
    }
}

void wpe_view_android_set_toplevel_state(WPEViewAndroid* view, WPEToplevelState state)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::set_toplevel_state(%p, %u)", view, static_cast<unsigned>(state));

    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        wpe_toplevel_state_changed(toplevel, state);
    }
}

void wpe_view_android_on_surface_created(WPEViewAndroid* view, ANativeWindow* window)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_created(%p, %p)", view, window);

    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        wpe_toplevel_android_on_surface_created(WPE_TOPLEVEL_ANDROID(toplevel), window);
    }
}

void wpe_view_android_on_surface_changed(WPEViewAndroid* view, int format, uint32_t width, uint32_t height)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_changed(%p, %d, %u, %u)", view, format, width, height);

    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        wpe_toplevel_android_on_surface_changed(WPE_TOPLEVEL_ANDROID(toplevel), format, width, height);
    }
}

void wpe_view_android_on_surface_redraw_needed(WPEViewAndroid* view)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_redraw_needed(%p)", view);

    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        wpe_toplevel_android_on_surface_redraw_needed(WPE_TOPLEVEL_ANDROID(toplevel));
    }
}

void wpe_view_android_on_surface_destroyed(WPEViewAndroid* view)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_destroyed(%p)", view);

    auto* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    if (toplevel != nullptr) {
        wpe_toplevel_android_on_surface_destroyed(WPE_TOPLEVEL_ANDROID(toplevel));
    }
}

WPEInputMethodContext* wpe_view_android_get_input_method_context(WPEViewAndroid* view)
{
    g_return_val_if_fail(WPE_IS_VIEW_ANDROID(view), nullptr);
    return view->inputMethodContext;
}

void wpe_view_android_set_input_method_context(WPEViewAndroid* view, WPEInputMethodContext* context)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));
    view->inputMethodContext = context;

    if (context && (view->pendingFocusCallbacks.focusInCallback || view->pendingFocusCallbacks.focusOutCallback)) {
        wpe_input_method_context_android_set_focus_callbacks(context, view->pendingFocusCallbacks.focusInCallback,
            view->pendingFocusCallbacks.focusOutCallback, view->pendingFocusCallbacks.userData);
        view->pendingFocusCallbacks = {};
        Logging::logDebug("WPEViewAndroid::set_input_method_context(%p) - applied pending focus callbacks", view);
    }
}

void wpe_view_android_set_input_method_focus_callbacks(WPEViewAndroid* view,
    WPEInputMethodContextAndroidFocusCallback focusInCallback,
    WPEInputMethodContextAndroidFocusCallback focusOutCallback, void* userData)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    if (auto* context = view->inputMethodContext) {
        wpe_input_method_context_android_set_focus_callbacks(context, focusInCallback, focusOutCallback, userData);
        return;
    }

    view->pendingFocusCallbacks.focusInCallback = focusInCallback;
    view->pendingFocusCallbacks.focusOutCallback = focusOutCallback;
    view->pendingFocusCallbacks.userData = userData;
    Logging::logDebug("WPEViewAndroid::set_input_method_focus_callbacks(%p) - stored as pending", view);
}
