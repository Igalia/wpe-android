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

#include "WPEViewAndroid.h"

#include "Logging.h"
#include "WPEToplevelAndroid.h"

#include <android/surface_control.h>

struct _WPEViewAndroid {
    WPEView parent;
    ASurfaceControl* surfaceControl;
};

G_DEFINE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE_TYPE_VIEW)

namespace {

struct BufferCallbackData {
    WPEView* view;
    WPEBuffer* buffer;
    void (*notify)(WPEView*, WPEBuffer*);

    BufferCallbackData(WPEView* v, WPEBuffer* b, void (*n)(WPEView*, WPEBuffer*))
        : view(static_cast<WPEView*>(g_object_ref(v)))
        , buffer(static_cast<WPEBuffer*>(g_object_ref(b)))
        , notify(n)
    {
    }
};

static void wpeViewAndroidOnTransactionCallback(void* context, ASurfaceTransactionStats*)
{
    g_main_context_invoke_full(
        nullptr, G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* d = static_cast<BufferCallbackData*>(userData);
            d->notify(d->view, d->buffer);
            return G_SOURCE_REMOVE;
        },
        context,
        +[](gpointer userData) {
            auto* d = static_cast<BufferCallbackData*>(userData);
            g_object_unref(d->view);
            g_object_unref(d->buffer);
            delete d;
        });
}

} // namespace

static gboolean wpeViewAndroidRenderBuffer(WPEView* view, WPEBuffer* buffer, const WPERectangle*, guint, GError** error)
{
    if (!WPE_IS_BUFFER_ANDROID(buffer)) {
        g_set_error_literal(
            error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "WPEViewAndroid: buffer is not a WPEBufferAndroid");
        return FALSE;
    }

    WPEToplevel* toplevel = wpe_view_get_toplevel(view);
    if (!toplevel || !WPE_IS_TOPLEVEL_ANDROID(toplevel)) {
        g_set_error_literal(error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "WPEViewAndroid: no toplevel");
        return FALSE;
    }

    ANativeWindow* window = wpe_toplevel_android_get_window(WPE_TOPLEVEL_ANDROID(toplevel));
    if (!window) {
        g_set_error_literal(error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "WPEViewAndroid: no native window");
        return FALSE;
    }

    auto* viewAndroid = WPE_VIEW_ANDROID(view);
    if (!viewAndroid->surfaceControl)
        viewAndroid->surfaceControl = ASurfaceControl_createFromWindow(window, "WPEViewAndroid");

    if (!viewAndroid->surfaceControl) {
        g_set_error_literal(
            error, WPE_VIEW_ERROR, WPE_VIEW_ERROR_RENDER_FAILED, "WPEViewAndroid: failed to create surface control");
        return FALSE;
    }

    auto* bufferAndroid = WPE_BUFFER_ANDROID(buffer);
    AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(bufferAndroid);
    int fenceFD = wpe_buffer_take_rendering_fence(buffer);

    Logging::logDebug("WPEViewAndroid::render_buffer(%p, %p, fence=%d)", view, buffer, fenceFD);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setBuffer(transaction, viewAndroid->surfaceControl, hardwareBuffer, fenceFD);
    ASurfaceTransaction_setVisibility(transaction, viewAndroid->surfaceControl, ASURFACE_TRANSACTION_VISIBILITY_SHOW);

    ASurfaceTransaction_setOnCommit(transaction, new BufferCallbackData {view, buffer, wpe_view_buffer_rendered},
        wpeViewAndroidOnTransactionCallback);

    ASurfaceTransaction_setOnComplete(transaction, new BufferCallbackData {view, buffer, wpe_view_buffer_released},
        wpeViewAndroidOnTransactionCallback);

    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);

    return TRUE;
}

static gboolean wpeViewAndroidCanBeMapped(WPEView* view)
{
    WPEToplevel* toplevel = wpe_view_get_toplevel(view);
    if (!toplevel || !WPE_IS_TOPLEVEL_ANDROID(toplevel))
        return FALSE;
    return wpe_toplevel_android_get_window(WPE_TOPLEVEL_ANDROID(toplevel)) != nullptr;
}

static void wpeViewAndroidSetOpaqueRectangles(WPEView*, WPERectangle*, guint)
{
    // TODO: Forward opaque region hints to the SurfaceControl layer for compositor optimization.
}

static void wpeViewAndroidFinalize(GObject* object)
{
    auto* viewAndroid = WPE_VIEW_ANDROID(object);
    if (viewAndroid->surfaceControl) {
        ASurfaceControl_release(viewAndroid->surfaceControl);
        viewAndroid->surfaceControl = nullptr;
    }

    G_OBJECT_CLASS(wpe_view_android_parent_class)->finalize(object);
}

static void wpe_view_android_class_init(WPEViewAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->finalize = wpeViewAndroidFinalize;

    WPEViewClass* viewClass = WPE_VIEW_CLASS(klass);
    viewClass->render_buffer = wpeViewAndroidRenderBuffer;
    viewClass->can_be_mapped = wpeViewAndroidCanBeMapped;
    viewClass->set_opaque_rectangles = wpeViewAndroidSetOpaqueRectangles;
}

static void wpe_view_android_init(WPEViewAndroid*)
{
}

WPEView* wpe_view_android_new(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);
    return WPE_VIEW(g_object_new(WPE_TYPE_VIEW_ANDROID, "display", display, nullptr));
}
