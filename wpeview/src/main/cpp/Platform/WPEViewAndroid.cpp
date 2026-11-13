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
#include "WPEGestureControllerAndroid.h"

#include <algorithm>
#include <android/native_window.h>
#include <cmath>
#include <cstring>
#include <unistd.h>

// Each view shows its buffers on a layer of its own, made with its first frame in a toplevel with a window, under the
// root of that toplevel. So each buffer is released by the layer that showed it. The view keeps its layer and its last
// buffer until it is destroyed: when the view leaves its toplevel, the layer stays on screen until the view that takes
// its place shows something (see WPEToplevelAndroid), and then goes out of the window with its buffer, which the view
// shows again at once when it joins a toplevel with a window of the same size.
//
// So every view that drew keeps one buffer of the size of its window, and a layer. WebKit keeps that buffer too, for
// snapshots. Before a view shows many pages in turn, a page out of the window needs a way to let it go, under memory
// pressure or beyond a number of pages, and to draw again when it comes back.
struct _WPEViewAndroid {
    WPEView parent;
    WPEToplevelAndroid* toplevel;
    ASurfaceControl* surfaceControl;
    WPEBuffer* frontBuffer;
    // The toplevel whose root the layer is under: the one the view is in, or the one it left while its layer still
    // shows there. Null while the layer is out of the window.
    WPEToplevelAndroid* layerToplevel;
    WPEScreen* screen;
    gulong screenRefreshRateChangedID;
    bool frameRateDirty;
};

G_DEFINE_FINAL_TYPE(WPEViewAndroid, wpe_view_android, WPE_TYPE_VIEW)

namespace {

// The callbacks of a transaction hold the view weakly: the view owns the layer that the transaction holds until it
// completes, and a view that WebKit let go has nobody to tell anymore.
struct BufferRenderedCallbackData {
    GWeakRef view;
    WPEBuffer* buffer;

    BufferRenderedCallbackData(WPEView* view, WPEBuffer* buffer)
        : buffer(static_cast<WPEBuffer*>(g_object_ref(buffer)))
    {
        g_weak_ref_init(&this->view, view);
    }

    ~BufferRenderedCallbackData()
    {
        g_weak_ref_clear(&view);
        g_object_unref(buffer);
    }

    BufferRenderedCallbackData(const BufferRenderedCallbackData&) = delete;
    BufferRenderedCallbackData& operator=(const BufferRenderedCallbackData&) = delete;
};

struct BufferReleasedCallbackData {
    GWeakRef view;
    WPEBuffer* buffer;
    ASurfaceControl* surfaceControl;
    // Previous-buffer release fence, owned until it is handed over to wpe_buffer_set_release_fence().
    int releaseFence {-1};

    BufferReleasedCallbackData(WPEView* view, WPEBuffer* buffer, ASurfaceControl* surfaceControl)
        : buffer(static_cast<WPEBuffer*>(g_object_ref(buffer)))
        , surfaceControl(surfaceControl)
    {
        g_weak_ref_init(&this->view, view);
        ASurfaceControl_acquire(surfaceControl);
    }

    ~BufferReleasedCallbackData()
    {
        if (releaseFence != -1)
            close(releaseFence);
        ASurfaceControl_release(surfaceControl);
        g_weak_ref_clear(&view);
        g_object_unref(buffer);
    }

    BufferReleasedCallbackData(const BufferReleasedCallbackData&) = delete;
    BufferReleasedCallbackData& operator=(const BufferReleasedCallbackData&) = delete;
};

static void wpeViewAndroidOnTransactionCommitted(void* context, ASurfaceTransactionStats* stats)
{
    UNUSED_PARAM(stats);
    g_main_context_invoke_full(
        nullptr, G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* data = static_cast<BufferRenderedCallbackData*>(userData);
            g_autoptr(GObject) view = static_cast<GObject*>(g_weak_ref_get(&data->view));
            if (view)
                wpe_view_buffer_rendered(WPE_VIEW(view), data->buffer);
            return G_SOURCE_REMOVE;
        },
        context, +[](gpointer userData) { delete static_cast<BufferRenderedCallbackData*>(userData); });
}

static void wpeViewAndroidOnTransactionCompleted(void* context, ASurfaceTransactionStats* stats)
{
    auto* data = static_cast<BufferReleasedCallbackData*>(context);

    // The transaction set the new buffer on the layer, so SurfaceFlinger lists the layer in the stats. Asking for a
    // layer that it does not list aborts the process, so a layer missing from them, which no known case gives, gets
    // the present fence instead: the old buffer is off the screen once the frame of the transaction is presented.
    ASurfaceControl** surfaceControls = nullptr;
    size_t surfaceControlsSize = 0;
    ASurfaceTransactionStats_getASurfaceControls(stats, &surfaceControls, &surfaceControlsSize);
    if (std::find(surfaceControls, surfaceControls + surfaceControlsSize, data->surfaceControl)
        != surfaceControls + surfaceControlsSize)
        data->releaseFence = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, data->surfaceControl);
    else
        data->releaseFence = ASurfaceTransactionStats_getPresentFenceFd(stats);
    ASurfaceTransactionStats_releaseASurfaceControls(surfaceControls);

    g_main_context_invoke_full(
        nullptr, G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* data = static_cast<BufferReleasedCallbackData*>(userData);
            if (data->releaseFence != -1) {
                wpe_buffer_set_release_fence(data->buffer, data->releaseFence);
                data->releaseFence = -1;
            }
            g_autoptr(GObject) view = static_cast<GObject*>(g_weak_ref_get(&data->view));
            if (view)
                wpe_view_buffer_released(WPE_VIEW(view), data->buffer);
            return G_SOURCE_REMOVE;
        },
        context, +[](gpointer userData) { delete static_cast<BufferReleasedCallbackData*>(userData); });
}

} // namespace

// The layers of the views go above the color of the root. Android 12 keeps that color in a child layer of the root,
// which it sorts with the layers at the default order, 0, by age, so a layer at 0 that is older than the root, as one
// that comes back to the window under the root of a new window, goes below the color and does not show.
static constexpr int32_t s_layerZOrder = 1;

// Puts the layer under the root of the toplevel, or out of the window when the toplevel is null or has no window.
static void wpeViewAndroidSetLayerParent(
    WPEViewAndroid* view, WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction)
{
    ASurfaceControl* root = toplevel ? wpe_toplevel_android_get_root_surface_control(toplevel) : nullptr;
    ASurfaceTransaction_reparent(transaction, view->surfaceControl, root);
    if (root)
        ASurfaceTransaction_setZOrder(transaction, view->surfaceControl, s_layerZOrder);
    view->layerToplevel = root ? toplevel : nullptr;
}

// WebKit draws a view at its logical size times the scale, rounded down, and the view has the logical size of its
// toplevel. A last frame of another size, from before the window turned or changed size, would show the page in a
// corner, so it waits out of the window until the view draws again.
static bool wpeViewAndroidLastFrameFits(WPEViewAndroid* view, WPEToplevelAndroid* toplevel)
{
    int width = 0;
    int height = 0;
    wpe_toplevel_get_size(WPE_TOPLEVEL(toplevel), &width, &height);
    double scale = wpe_toplevel_get_scale(WPE_TOPLEVEL(toplevel));
    return view->frontBuffer && scale > 0 && std::abs((wpe_buffer_get_width(view->frontBuffer) / scale) - width) < 1
        && std::abs((wpe_buffer_get_height(view->frontBuffer) / scale) - height) < 1;
}

// Shows the last frame of the view in the toplevel if it fits there, and keeps it out of the window otherwise.
static void wpeViewAndroidShowLastFrame(
    WPEViewAndroid* view, WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction)
{
    wpeViewAndroidSetLayerParent(
        view, toplevel && wpeViewAndroidLastFrameFits(view, toplevel) ? toplevel : nullptr, transaction);
}

static void wpeViewAndroidDispose(GObject* object)
{
    auto* view = WPE_VIEW_ANDROID(object);
    // WebKit stops listening to the view before it lets it go, so the layer goes at once, and its buffer with it. A
    // layer out of the window, or on its way out in a transaction that is not applied yet, needs no transaction of
    // its own: one applied now would take the layer of a destroyed page off the screen before the page that takes
    // its place shows.
    if (view->surfaceControl) {
        if (view->layerToplevel) {
            ASurfaceTransaction* transaction = ASurfaceTransaction_create();
            ASurfaceTransaction_reparent(transaction, view->surfaceControl, nullptr);
            ASurfaceTransaction_apply(transaction);
            ASurfaceTransaction_delete(transaction);
        }
        g_clear_pointer(&view->surfaceControl, ASurfaceControl_release);
    }
    g_clear_object(&view->frontBuffer);
    view->layerToplevel = nullptr;
    g_clear_object(&view->toplevel);
    if (view->screen) {
        g_clear_signal_handler(&view->screenRefreshRateChangedID, view->screen);
        g_clear_object(&view->screen);
    }
    G_OBJECT_CLASS(wpe_view_android_parent_class)->dispose(object);
}

static void wpeViewAndroidToplevelChanged(WPEViewAndroid* view)
{
    WPEToplevel* toplevel = wpe_view_get_toplevel(WPE_VIEW(view));
    auto* toplevelAndroid = WPE_IS_TOPLEVEL_ANDROID(toplevel) ? WPE_TOPLEVEL_ANDROID(toplevel) : nullptr;
    if (view->toplevel == toplevelAndroid)
        return;

    if (view->toplevel)
        wpe_toplevel_android_view_left(view->toplevel, WPE_VIEW(view), view->layerToplevel == view->toplevel);
    g_clear_object(&view->toplevel);

    if (!toplevelAndroid)
        return;
    view->toplevel = WPE_TOPLEVEL_ANDROID(g_object_ref(toplevelAndroid));
    if (!view->surfaceControl)
        return;

    // Back before its layer went: the layer is still on screen.
    if (view->layerToplevel == toplevelAndroid) {
        wpe_toplevel_android_forget_leaving_view(toplevelAndroid, WPE_VIEW(view));
        return;
    }

    // The view shows its last frame at once in the toplevel, where it takes the place of the layer that the view
    // before it left.
    if (view->layerToplevel)
        wpe_toplevel_android_forget_leaving_view(view->layerToplevel, WPE_VIEW(view));
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    wpeViewAndroidShowLastFrame(view, toplevelAndroid, transaction);
    if (view->layerToplevel)
        wpe_toplevel_android_remove_leaving_layer(toplevelAndroid, transaction);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

// The class closure of the notify signal runs before the handlers connected to it, so the view has its toplevel
// before WebKit renders a frame that it kept for it.
static void wpeViewAndroidNotify(GObject* object, GParamSpec* paramSpec)
{
    if (G_OBJECT_CLASS(wpe_view_android_parent_class)->notify)
        G_OBJECT_CLASS(wpe_view_android_parent_class)->notify(object, paramSpec);

    if (!std::strcmp(paramSpec->name, "toplevel"))
        wpeViewAndroidToplevelChanged(WPE_VIEW_ANDROID(object));
}

static void wpeViewAndroidRefreshRateChanged(WPEViewAndroid* view)
{
    view->frameRateDirty = true;
}

static void wpeViewAndroidEnsureScreenObserver(WPEViewAndroid* view, WPEScreen* screen)
{
    if (view->screen == screen)
        return;

    // A screen lasts as long as its display, which can go while the view still observes its screen.
    if (view->screen) {
        g_clear_signal_handler(&view->screenRefreshRateChangedID, view->screen);
        g_clear_object(&view->screen);
    }

    view->screen = screen ? WPE_SCREEN(g_object_ref(screen)) : nullptr;
    view->frameRateDirty = true;

    if (screen) {
        view->screenRefreshRateChangedID = g_signal_connect_swapped(
            screen, "notify::refresh-rate", G_CALLBACK(wpeViewAndroidRefreshRateChanged), view);
    }
}

static void wpeViewAndroidUpdateFrameRate(WPEViewAndroid* view, ASurfaceTransaction* transaction, WPEScreen* screen)
{
    if (!screen || !view->frameRateDirty)
        return;

    float refreshRateHz = wpe_screen_get_refresh_rate(screen) / 1000.0F;
    if (refreshRateHz <= 0.0F)
        return;

    ASurfaceTransaction_setFrameRate(
        transaction, view->surfaceControl, refreshRateHz, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT);
    view->frameRateDirty = false;
}

// A frame of a view that has no layer yet, in a toplevel with no window, goes nowhere. WebKit hears at once that it
// was shown and released, so that it draws the next one, and waits for the frame to be drawn before it draws into the
// buffer again.
static void wpeViewAndroidDropFrame(WPEView* view, WPEBuffer* buffer)
{
    struct DroppedFrame {
        WPEView* view;
        WPEBuffer* buffer;
        int renderingFence;
    };
    auto* frame = new DroppedFrame {static_cast<WPEView*>(g_object_ref(view)),
        static_cast<WPEBuffer*>(g_object_ref(buffer)), wpe_buffer_take_rendering_fence(buffer)};
    g_idle_add_full(
        G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* frame = static_cast<DroppedFrame*>(userData);
            wpe_view_buffer_rendered(frame->view, frame->buffer);
            if (frame->renderingFence != -1) {
                wpe_buffer_set_release_fence(frame->buffer, frame->renderingFence);
                frame->renderingFence = -1;
            }
            wpe_view_buffer_released(frame->view, frame->buffer);
            return G_SOURCE_REMOVE;
        },
        frame,
        +[](gpointer userData) {
            auto* frame = static_cast<DroppedFrame*>(userData);
            if (frame->renderingFence != -1)
                close(frame->renderingFence);
            g_object_unref(frame->view);
            g_object_unref(frame->buffer);
            delete frame;
        });
}

static gboolean wpeViewAndroidRenderBuffer(
    WPEView* view, WPEBuffer* buffer, const WPERectangle* damage, guint nDamageRects, GError**)
{
    UNUSED_PARAM(damage);
    UNUSED_PARAM(nDamageRects);

    auto* viewAndroid = WPE_VIEW_ANDROID(view);
    WPEToplevelAndroid* toplevel = viewAndroid->toplevel;
    ASurfaceControl* root = toplevel ? wpe_toplevel_android_get_root_surface_control(toplevel) : nullptr;
    bool newLayer = !viewAndroid->surfaceControl && root;
    if (newLayer) {
        viewAndroid->surfaceControl = ASurfaceControl_create(root, "WPEWebLayer");
        if (!viewAndroid->surfaceControl)
            Logging::logError("WPEViewAndroid: cannot create the layer of the view");
        else
            viewAndroid->layerToplevel = toplevel;
        viewAndroid->frameRateDirty = true;
    }
    if (!viewAndroid->surfaceControl) {
        wpeViewAndroidDropFrame(view, buffer);
        return TRUE;
    }

    AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(WPE_BUFFER_ANDROID(buffer));
    int fenceFD = wpe_buffer_take_rendering_fence(buffer);
    WPEBuffer* previousFrontBuffer
        = viewAndroid->frontBuffer ? WPE_BUFFER(g_object_ref(viewAndroid->frontBuffer)) : nullptr;
    g_set_object(&viewAndroid->frontBuffer, buffer);
    WPEScreen* screen = wpe_view_get_screen(view);
    wpeViewAndroidEnsureScreenObserver(viewAndroid, screen);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (newLayer)
        ASurfaceTransaction_setZOrder(transaction, viewAndroid->surfaceControl, s_layerZOrder);
    ASurfaceTransaction_setBuffer(transaction, viewAndroid->surfaceControl, hardwareBuffer, fenceFD);
    wpeViewAndroidUpdateFrameRate(viewAndroid, transaction, screen);

    ASurfaceTransaction_setOnCommit(
        transaction, new BufferRenderedCallbackData {view, buffer}, wpeViewAndroidOnTransactionCommitted);

    if (previousFrontBuffer) {
        ASurfaceTransaction_setOnComplete(transaction,
            new BufferReleasedCallbackData {view, previousFrontBuffer, viewAndroid->surfaceControl},
            wpeViewAndroidOnTransactionCompleted);
        g_object_unref(previousFrontBuffer);
    }

    // A layer that waited out of the window, with a last frame of another size, comes back with the first frame that
    // fits, which WebKit draws once it has the new size. The frame takes the place of the layer that the view before
    // this one left in the toplevel, in the same transaction, so that the toplevel shows neither the old page nor its
    // background in between.
    if (root && viewAndroid->layerToplevel != toplevel && wpeViewAndroidLastFrameFits(viewAndroid, toplevel))
        wpeViewAndroidSetLayerParent(viewAndroid, toplevel, transaction);
    if (root && viewAndroid->layerToplevel == toplevel)
        wpe_toplevel_android_remove_leaving_layer(toplevel, transaction);

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
    objectClass->notify = wpeViewAndroidNotify;

    WPEViewClass* viewClass = WPE_VIEW_CLASS(klass);
    viewClass->render_buffer = wpeViewAndroidRenderBuffer;
    viewClass->can_be_mapped = wpeViewAndroidCanBeMapped;
}

static void wpe_view_android_init(WPEViewAndroid* view)
{
    // Taps and drags as Android finds them, also on pages that cancel touchmove.
    g_autoptr(WPEGestureController) gestureController = wpe_gesture_controller_android_new();
    wpe_view_set_gesture_controller(WPE_VIEW(view), gestureController);
}

WPEView* wpe_view_android_new(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);
    return WPE_VIEW(g_object_new(WPE_TYPE_VIEW_ANDROID, "display", display, nullptr));
}

// Shows the layer of a view in the toplevel under its new root, or takes it out of the window when the toplevel loses
// its window. The layer keeps its buffer, so that the view shows its last frame again at once in the next window.
void wpe_view_android_move_layer(WPEView* view, WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));
    auto* viewAndroid = WPE_VIEW_ANDROID(view);
    if (viewAndroid->surfaceControl)
        wpeViewAndroidShowLastFrame(viewAndroid, toplevel, transaction);
}

// Takes the layer that a view left in the toplevel out of the window, unless the view has moved it since.
void wpe_view_android_hide_layer(WPEView* view, WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction)
{
    g_return_if_fail(WPE_IS_VIEW_ANDROID(view));
    auto* viewAndroid = WPE_VIEW_ANDROID(view);
    if (viewAndroid->surfaceControl && viewAndroid->layerToplevel == toplevel)
        wpeViewAndroidSetLayerParent(viewAndroid, nullptr, transaction);
}
