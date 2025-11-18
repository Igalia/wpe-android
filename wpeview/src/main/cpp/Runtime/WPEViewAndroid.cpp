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
#include "RendererSurfaceControl.h"
#include "ScopedFD.h"
#include "WPEDisplayAndroid.h"

#include <android/hardware_buffer.h>
#include <wpe-platform/wpe/WPEBufferAndroid.h>

struct _WPEViewAndroid {
    WPEView parent;
};

typedef struct {
    WPEBuffer* pendingBuffer;
    WPEBuffer* committedBuffer;
    std::shared_ptr<RendererSurfaceControl> renderer;
} WPEViewAndroidPrivate;

G_DEFINE_TYPE_WITH_PRIVATE(WPEViewAndroid, wpe_view_android, WPE_TYPE_VIEW)

static void wpeViewAndroidConstructed(GObject* object)
{
    G_OBJECT_CLASS(wpe_view_android_parent_class)->constructed(object);

    Logging::logDebug("WPEViewAndroid::constructed(%p)", object);

    auto* view = WPE_VIEW(object);
    auto* display = wpe_view_get_display(view);
    if (display) {
        auto* toplevel = wpe_display_android_get_toplevel(display);
        if (toplevel) {
            Logging::logDebug("WPEViewAndroid::constructed - associating view %p with toplevel %p", view, toplevel);
            wpe_view_set_toplevel(view, toplevel);
        } else {
            Logging::logDebug("WPEViewAndroid::constructed - no toplevel found for display %p", display);
        }
    } else {
        Logging::logDebug("WPEViewAndroid::constructed - no display set for view %p", view);
    }
}

static void wpeViewAndroidDispose(GObject* object)
{
    Logging::logDebug("WPEViewAndroid::dispose(%p)", object);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(object)));

    g_clear_object(&priv->pendingBuffer);
    g_clear_object(&priv->committedBuffer);
    priv->renderer.reset();

    G_OBJECT_CLASS(wpe_view_android_parent_class)->dispose(object);
}

static gboolean wpeViewAndroidRenderBuffer(
    WPEView* view, WPEBuffer* buffer, const WPERectangle* /*damageRects*/, guint nDamageRects, GError** error)
{
    Logging::logDebug("WPEViewAndroid::render_buffer(%p, %p, %u rects)", view, buffer, nDamageRects);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    if (!WPE_IS_BUFFER_ANDROID(buffer)) {
        g_set_error_literal(error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "Buffer is not a WPEBufferAndroid");
        return FALSE;
    }

    auto* bufferAndroid = WPE_BUFFER_ANDROID(buffer);
    AHardwareBuffer* ahb = wpe_buffer_android_get_hardware_buffer(bufferAndroid);
    if (ahb == nullptr) {
        g_set_error_literal(
            error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "Failed to get AHardwareBuffer from WPEBufferAndroid");
        return FALSE;
    }

    g_clear_object(&priv->pendingBuffer);
    priv->pendingBuffer = WPE_BUFFER(g_object_ref(buffer));
    Logging::logDebug("WPEViewAndroid: pending buffer %p", priv->pendingBuffer);

    if (priv->renderer) {
        // Commit buffer to SurfaceControl for display.
        auto fenceFD = std::make_shared<ScopedFD>(-1);
        priv->renderer->commitBuffer(ahb, bufferAndroid, fenceFD);
    } else {
        // Headless rendering: signal that the buffer is available for reuse.
        wpe_view_buffer_released(view, buffer);
        wpe_view_buffer_rendered(view, buffer);
    }

    Logging::logDebug("WPEViewAndroid: buffer committed successfully");

    return TRUE;
}

static void wpe_view_android_class_init(WPEViewAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    WPEViewClass* viewClass = WPE_VIEW_CLASS(klass);

    objectClass->constructed = wpeViewAndroidConstructed;
    objectClass->dispose = wpeViewAndroidDispose;
    viewClass->render_buffer = wpeViewAndroidRenderBuffer;
}

static void wpe_view_android_init(WPEViewAndroid* view)
{
    Logging::logDebug("WPEViewAndroid::init(%p)", view);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    priv->pendingBuffer = nullptr;
    priv->committedBuffer = nullptr;
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

void wpe_view_android_set_renderer(WPEViewAndroid* view, std::shared_ptr<RendererSurfaceControl> renderer)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::set_renderer(%p, %p)", view, renderer.get());

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    priv->renderer = renderer;

    if (renderer) {
        renderer->setBufferReleaseCallback([view](WPEBufferAndroid* buffer) {
            if (buffer != nullptr) {
                Logging::logDebug("WPEViewAndroid: buffer released %p", buffer);
                wpe_view_buffer_released(WPE_VIEW(view), WPE_BUFFER(buffer));
            }
        });

        renderer->setFrameCompleteCallback([view]() {
            Logging::logDebug("WPEViewAndroid: frame complete");
            auto* priv
                = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

            g_clear_object(&priv->committedBuffer);
            priv->committedBuffer = priv->pendingBuffer;
            priv->pendingBuffer = nullptr;

            if (priv->committedBuffer) {
                Logging::logDebug("WPEViewAndroid: committed buffer %p", priv->committedBuffer);
                wpe_view_buffer_rendered(WPE_VIEW(view), priv->committedBuffer);
            }
        });
    }
}

void wpe_view_android_on_surface_created(WPEViewAndroid* view, ANativeWindow* window)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_created(%p, %p)", view, window);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    if (priv->renderer) {
        priv->renderer->onSurfaceCreated(window);
    }
}

void wpe_view_android_on_surface_changed(WPEViewAndroid* view, int format, uint32_t width, uint32_t height)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_changed(%p, %d, %u, %u)", view, format, width, height);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    if (priv->renderer) {
        priv->renderer->onSurfaceChanged(format, width, height);
    }
}

void wpe_view_android_on_surface_redraw_needed(WPEViewAndroid* view)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_redraw_needed(%p)", view);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    if (priv->renderer) {
        priv->renderer->onSurfaceRedrawNeeded();
    }
}

void wpe_view_android_on_surface_destroyed(WPEViewAndroid* view)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));

    Logging::logDebug("WPEViewAndroid::on_surface_destroyed(%p)", view);

    auto* priv = static_cast<WPEViewAndroidPrivate*>(wpe_view_android_get_instance_private(WPE_VIEW_ANDROID(view)));

    if (priv->renderer) {
        priv->renderer->onSurfaceDestroyed();
    }
}
