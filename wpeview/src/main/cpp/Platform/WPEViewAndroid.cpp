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
#include "ScopedFD.h"
#include "WPEToplevelAndroid.h"

#include <android/native_window.h>
#include <android/surface_control.h>
#include <memory>

struct _WPEViewAndroid {
    WPEView parent;
    WPEBuffer* frontBuffer;
    ASurfaceControl* frameRateSurfaceControl;
    WPEScreen* screen;
    gulong screenRefreshRateChangedID;
    bool frameRateDirty;
};

G_DEFINE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE_TYPE_VIEW)

namespace {

struct BufferRenderedCallbackData {
    WPEView* view;
    WPEBuffer* buffer;

    BufferRenderedCallbackData(WPEView* view, WPEBuffer* buffer)
        : view(static_cast<WPEView*>(g_object_ref(view)))
        , buffer(static_cast<WPEBuffer*>(g_object_ref(buffer)))
    {
    }

    ~BufferRenderedCallbackData()
    {
        g_object_unref(view);
        g_object_unref(buffer);
    }
};

struct BufferReleasedCallbackData {
    WPEView* view;
    WPEBuffer* buffer;
    ASurfaceControl* surfaceControl;
    std::unique_ptr<ScopedFD> releaseFence;

    BufferReleasedCallbackData(WPEView* view, WPEBuffer* buffer, ASurfaceControl* surfaceControl)
        : view(static_cast<WPEView*>(g_object_ref(view)))
        , buffer(static_cast<WPEBuffer*>(g_object_ref(buffer)))
        , surfaceControl(surfaceControl)
    {
    }

    ~BufferReleasedCallbackData()
    {
        g_object_unref(view);
        g_object_unref(buffer);
    }
};

static void wpeViewAndroidOnTransactionCommitted(void* context, ASurfaceTransactionStats* stats)
{
    UNUSED_PARAM(stats);
    g_main_context_invoke_full(
        nullptr, G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* data = static_cast<BufferRenderedCallbackData*>(userData);
            wpe_view_buffer_rendered(data->view, data->buffer);
            return G_SOURCE_REMOVE;
        },
        context, +[](gpointer userData) { delete static_cast<BufferRenderedCallbackData*>(userData); });
}

static void wpeViewAndroidOnTransactionCompleted(void* context, ASurfaceTransactionStats* stats)
{
    auto* data = static_cast<BufferReleasedCallbackData*>(context);
    int releaseFenceFD = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, data->surfaceControl);
    if (releaseFenceFD != -1)
        data->releaseFence = std::make_unique<ScopedFD>(releaseFenceFD);

    g_main_context_invoke_full(
        nullptr, G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* data = static_cast<BufferReleasedCallbackData*>(userData);
            if (data->releaseFence)
                wpe_buffer_set_release_fence(data->buffer, data->releaseFence->release());
            wpe_view_buffer_released(data->view, data->buffer);
            return G_SOURCE_REMOVE;
        },
        context, +[](gpointer userData) { delete static_cast<BufferReleasedCallbackData*>(userData); });
}

} // namespace

static void wpeViewAndroidDispose(GObject* object)
{
    auto* view = WPE_VIEW_ANDROID(object);
    if (view->screenRefreshRateChangedID) {
        g_signal_handler_disconnect(view->screen, view->screenRefreshRateChangedID);
        view->screenRefreshRateChangedID = 0;
        view->screen = nullptr;
    }
    g_clear_object(&view->frontBuffer);
    G_OBJECT_CLASS(wpe_view_android_parent_class)->dispose(object);
}

static void wpeViewAndroidRefreshRateChanged(WPEViewAndroid* view)
{
    view->frameRateDirty = true;
}

static void wpeViewAndroidEnsureScreenObserver(WPEViewAndroid* view, WPEScreen* screen)
{
    if (view->screen == screen)
        return;

    if (view->screenRefreshRateChangedID) {
        g_signal_handler_disconnect(view->screen, view->screenRefreshRateChangedID);
        view->screenRefreshRateChangedID = 0;
    }

    view->screen = screen;
    view->frameRateDirty = true;

    if (screen) {
        view->screenRefreshRateChangedID = g_signal_connect_swapped(
            screen, "notify::refresh-rate", G_CALLBACK(wpeViewAndroidRefreshRateChanged), view);
    }
}

static void wpeViewAndroidUpdateFrameRate(
    WPEViewAndroid* view, ASurfaceTransaction* transaction, ASurfaceControl* surfaceControl, WPEScreen* screen)
{
    if (!screen)
        return;

    if (view->frameRateSurfaceControl == surfaceControl && !view->frameRateDirty)
        return;

    float refreshRateHz = wpe_screen_get_refresh_rate(screen) / 1000.0F;
    if (refreshRateHz <= 0.0F)
        return;

    ASurfaceTransaction_setFrameRate(
        transaction, surfaceControl, refreshRateHz, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT);
    view->frameRateSurfaceControl = surfaceControl;
    view->frameRateDirty = false;
}

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
    auto* viewAndroid = WPE_VIEW_ANDROID(view);
    WPEBuffer* previousFrontBuffer
        = viewAndroid->frontBuffer ? WPE_BUFFER(g_object_ref(viewAndroid->frontBuffer)) : nullptr;
    g_set_object(&viewAndroid->frontBuffer, buffer);
    WPEScreen* screen = wpe_view_get_screen(view);
    wpeViewAndroidEnsureScreenObserver(viewAndroid, screen);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setBuffer(transaction, surfaceControl, hardwareBuffer, fenceFD);
    ASurfaceTransaction_setVisibility(transaction, surfaceControl, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    wpeViewAndroidUpdateFrameRate(viewAndroid, transaction, surfaceControl, screen);

    ASurfaceTransaction_setOnCommit(
        transaction, new BufferRenderedCallbackData {view, buffer}, wpeViewAndroidOnTransactionCommitted);

    if (previousFrontBuffer) {
        ASurfaceTransaction_setOnComplete(transaction,
            new BufferReleasedCallbackData {view, previousFrontBuffer, surfaceControl},
            wpeViewAndroidOnTransactionCompleted);
        g_object_unref(previousFrontBuffer);
    }

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
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeViewAndroidDispose;

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
