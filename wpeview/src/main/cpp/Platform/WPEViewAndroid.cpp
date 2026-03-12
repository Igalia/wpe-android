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
};

G_DEFINE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE_TYPE_VIEW)

namespace {

struct BufferCallbackData {
    WPEView* view;
    WPEBuffer* buffer;
    void (*notify)(WPEView*, WPEBuffer*);

    BufferCallbackData(WPEView* view, WPEBuffer* buffer, void (*notify)(WPEView*, WPEBuffer*))
        : view(static_cast<WPEView*>(g_object_ref(view)))
        , buffer(static_cast<WPEBuffer*>(g_object_ref(buffer)))
        , notify(notify)
    {
    }

    ~BufferCallbackData()
    {
        g_object_unref(view);
        g_object_unref(buffer);
    }
};

static void wpeViewAndroidOnTransactionCallback(void* context, ASurfaceTransactionStats* stats)
{
    UNUSED_PARAM(stats);
    g_main_context_invoke_full(
        nullptr, G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* data = static_cast<BufferCallbackData*>(userData);
            data->notify(data->view, data->buffer);
            return G_SOURCE_REMOVE;
        },
        context, +[](gpointer userData) { delete static_cast<BufferCallbackData*>(userData); });
}

} // namespace

static gboolean wpeViewAndroidRenderBuffer(
    WPEView* view, WPEBuffer* buffer, const WPERectangle* damage, guint nDamageRects, GError**)
{
    UNUSED_PARAM(damage);
    UNUSED_PARAM(nDamageRects);

    auto* toplevel = WPE_TOPLEVEL_ANDROID(wpe_view_get_toplevel(view));
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), FALSE);

    ASurfaceControl* surfaceControl = wpe_toplevel_android_get_surface_control(toplevel);
    g_return_val_if_fail(surfaceControl, FALSE);

    auto* bufferAndroid = WPE_BUFFER_ANDROID(buffer);
    AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(bufferAndroid);
    int fenceFD = wpe_buffer_take_rendering_fence(buffer);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setBuffer(transaction, surfaceControl, hardwareBuffer, fenceFD);
    ASurfaceTransaction_setVisibility(transaction, surfaceControl, ASURFACE_TRANSACTION_VISIBILITY_SHOW);

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
    auto* toplevel = WPE_TOPLEVEL_ANDROID(wpe_view_get_toplevel(view));
    return toplevel && wpe_toplevel_android_get_window(toplevel) != nullptr;
}

static void wpe_view_android_class_init(WPEViewAndroidClass* klass)
{
    WPEViewClass* viewClass = WPE_VIEW_CLASS(klass);
    viewClass->render_buffer = wpeViewAndroidRenderBuffer;
    viewClass->can_be_mapped = wpeViewAndroidCanBeMapped;
}

static void wpe_view_android_init(WPEViewAndroid*)
{
}

WPEView* wpe_view_android_new(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);
    return WPE_VIEW(g_object_new(WPE_TYPE_VIEW_ANDROID, "display", display, nullptr));
}
