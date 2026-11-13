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

#include "WPEToplevelAndroid.h"

#include "Logging.h"
#include "WPEViewAndroid.h"

#include <cmath>
#include <utility>

// How long the layer of a view that left stays on screen, waiting for the view that takes its place to show something.
static constexpr guint s_leavingLayerTimeoutMs = 100;

struct _WPEToplevelAndroid {
    WPEToplevel parent;
    ANativeWindow* window;
    // The views put their layers under the root, which shows the background color where they show nothing.
    ASurfaceControl* rootSurfaceControl;
    // The view that left last, while its layer still shows under the root. The toplevel holds the view, so a view
    // can outlive its WebView by up to the timeout.
    WPEView* leavingView;
    guint leavingIdleID;
    guint leavingTimeoutID;
    WPEScreen* screen;
    gboolean windowFocused;
    gboolean fullscreen;
    WPEToplevelAndroidFullscreenCallback fullscreenCallback;
    gpointer fullscreenCallbackUserData;
    int physicalWidth;
    int physicalHeight;
    float backgroundRed;
    float backgroundGreen;
    float backgroundBlue;
};

G_DEFINE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE_TYPE_TOPLEVEL)

static gboolean wpeToplevelAndroidResize(WPEToplevel* toplevel, int width, int height)
{
    wpe_toplevel_resized(toplevel, width, height);
    return TRUE;
}

static WPEScreen* wpeToplevelAndroidGetScreen(WPEToplevel* toplevel)
{
    return WPE_TOPLEVEL_ANDROID(toplevel)->screen;
}

static float wpeToplevelAndroidGetScreenScale(WPEToplevelAndroid* toplevel)
{
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), 1.0F);
    WPEScreen* screen = wpeToplevelAndroidGetScreen(WPE_TOPLEVEL(toplevel));
    if (!screen)
        return 1.0F;

    float scale = static_cast<float>(wpe_screen_get_scale(screen));
    return scale > 0.0F ? scale : 1.0F;
}

static int wpeToplevelAndroidLogicalDimension(int physicalDimension, float scale)
{
    return scale > 0.0F ? static_cast<int>(std::lround(physicalDimension / scale)) : physicalDimension;
}

static void wpeToplevelAndroidSyncScaleAndSize(WPEToplevelAndroid* toplevel, int physicalWidth, int physicalHeight)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    toplevel->physicalWidth = physicalWidth;
    toplevel->physicalHeight = physicalHeight;

    auto* baseToplevel = WPE_TOPLEVEL(toplevel);
    float scale = wpeToplevelAndroidGetScreenScale(toplevel);
    if (wpe_toplevel_get_scale(baseToplevel) != scale)
        wpe_toplevel_scale_changed(baseToplevel, scale);

    int logicalWidth = wpeToplevelAndroidLogicalDimension(physicalWidth, scale);
    int logicalHeight = wpeToplevelAndroidLogicalDimension(physicalHeight, scale);
    wpe_toplevel_resized(baseToplevel, logicalWidth, logicalHeight);
}

// Like a desktop window, the toplevel is active while its Android window has focus.
static void wpeToplevelAndroidUpdateState(WPEToplevelAndroid* toplevel)
{
    unsigned state = WPE_TOPLEVEL_STATE_NONE;
    if (toplevel->window)
        state |= WPE_TOPLEVEL_STATE_MAXIMIZED;
    if (toplevel->windowFocused)
        state |= WPE_TOPLEVEL_STATE_ACTIVE;
    if (toplevel->fullscreen)
        state |= WPE_TOPLEVEL_STATE_FULLSCREEN;
    wpe_toplevel_state_changed(WPE_TOPLEVEL(toplevel), static_cast<WPEToplevelState>(state));
}

// WebKit asks for fullscreen when an element of the page goes fullscreen, and to leave it when the page leaves
// fullscreen. The view always fills its toplevel, so the toplevel takes the state at once, and the callback tells the
// app, which gives the toplevel the whole screen or takes it back. The state comes first, so that the callback can
// leave fullscreen again.
static gboolean wpeToplevelAndroidSetFullscreen(WPEToplevel* baseToplevel, gboolean fullscreen)
{
    auto* toplevel = WPE_TOPLEVEL_ANDROID(baseToplevel);
    if (toplevel->fullscreen == !!fullscreen)
        return TRUE;

    wpe_toplevel_android_set_fullscreen(toplevel, fullscreen);
    if (toplevel->fullscreenCallback)
        toplevel->fullscreenCallback(fullscreen, toplevel->fullscreenCallbackUserData);
    return TRUE;
}

void wpe_toplevel_android_set_physical_size(WPEToplevelAndroid* toplevel, int width, int height)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    wpeToplevelAndroidSyncScaleAndSize(toplevel, width, height);
}

// Follows the scale of the screen, which changes with the display size setting: the size in pixels stays the
// same, and the views get the new logical size from here.
static void wpeToplevelAndroidScreenScaleChanged(WPEToplevelAndroid* toplevel, GParamSpec*, WPEScreen*)
{
    wpeToplevelAndroidSyncScaleAndSize(toplevel, toplevel->physicalWidth, toplevel->physicalHeight);

    int size[2];
    wpe_toplevel_get_size(WPE_TOPLEVEL(toplevel), &size[0], &size[1]);
    if (size[0] <= 0 || size[1] <= 0)
        return;

    wpe_toplevel_foreach_view(
        WPE_TOPLEVEL(toplevel),
        [](WPEToplevel*, WPEView* view, gpointer userData) {
            auto* size = static_cast<int*>(userData);
            wpe_view_resized(view, size[0], size[1]);
            return FALSE;
        },
        size);
}

// The screen is the one of the Android display the window shows on. A new screen can bring a new scale, and pages
// get its size and the rest of its properties.
void wpe_toplevel_android_set_screen(WPEToplevelAndroid* toplevel, WPEScreen* screen)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    g_return_if_fail(!screen || WPE_IS_SCREEN(screen));

    if (toplevel->screen == screen)
        return;

    if (toplevel->screen) {
        g_signal_handlers_disconnect_by_data(toplevel->screen, toplevel);
        g_object_unref(toplevel->screen);
    }
    toplevel->screen = screen ? WPE_SCREEN(g_object_ref(screen)) : nullptr;
    if (!screen)
        return;

    g_signal_connect_swapped(screen, "notify::scale", G_CALLBACK(wpeToplevelAndroidScreenScaleChanged), toplevel);
    wpeToplevelAndroidScreenScaleChanged(toplevel, nullptr, screen);
    wpe_toplevel_screen_changed(WPE_TOPLEVEL(toplevel));
}

static void wpeToplevelAndroidForgetLeavingView(WPEToplevelAndroid* toplevel)
{
    g_clear_handle_id(&toplevel->leavingIdleID, g_source_remove);
    g_clear_handle_id(&toplevel->leavingTimeoutID, g_source_remove);
    g_clear_object(&toplevel->leavingView);
}

static void wpeToplevelAndroidHideLeavingLayer(WPEToplevelAndroid* toplevel)
{
    if (!toplevel->leavingView)
        return;

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    wpe_toplevel_android_remove_leaving_layer(toplevel, transaction);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

// A released layer stays on screen for as long as its parent does, so the root leaves the window before it is
// released, and the layers under it go with it.
static void wpeToplevelAndroidRemoveRoot(WPEToplevelAndroid* toplevel)
{
    if (!toplevel->rootSurfaceControl && !toplevel->leavingView)
        return;

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    wpe_toplevel_android_remove_leaving_layer(toplevel, transaction);
    if (toplevel->rootSurfaceControl)
        ASurfaceTransaction_reparent(transaction, toplevel->rootSurfaceControl, nullptr);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
    g_clear_pointer(&toplevel->rootSurfaceControl, ASurfaceControl_release);
}

static void wpeToplevelAndroidDispose(GObject* object)
{
    auto* toplevel = WPE_TOPLEVEL_ANDROID(object);
    wpeToplevelAndroidRemoveRoot(toplevel);
    wpeToplevelAndroidForgetLeavingView(toplevel);
    if (toplevel->screen) {
        g_signal_handlers_disconnect_by_data(toplevel->screen, toplevel);
        g_clear_object(&toplevel->screen);
    }

    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->dispose(object);
}

static void wpeToplevelAndroidFinalize(GObject* object)
{
    auto* toplevel = WPE_TOPLEVEL_ANDROID(object);
    g_clear_pointer(&toplevel->window, ANativeWindow_release);

    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->finalize(object);
}

static void wpe_toplevel_android_class_init(WPEToplevelAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeToplevelAndroidDispose;
    objectClass->finalize = wpeToplevelAndroidFinalize;

    WPEToplevelClass* toplevelClass = WPE_TOPLEVEL_CLASS(klass);
    toplevelClass->resize = wpeToplevelAndroidResize;
    toplevelClass->get_screen = wpeToplevelAndroidGetScreen;
    toplevelClass->set_fullscreen = wpeToplevelAndroidSetFullscreen;
}

static void wpe_toplevel_android_init(WPEToplevelAndroid* toplevel)
{
    toplevel->backgroundRed = 1.0F;
    toplevel->backgroundGreen = 1.0F;
    toplevel->backgroundBlue = 1.0F;
}

WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display, ANativeWindow* window)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);
    auto* toplevel = WPE_TOPLEVEL_ANDROID(g_object_new(WPE_TYPE_TOPLEVEL_ANDROID, "display", display, nullptr));
    // The toplevel starts on the primary screen, and WebView moves it to the screen of the display it shows on.
    wpe_toplevel_android_set_screen(toplevel, wpe_display_get_screen(display, 0));
    wpeToplevelAndroidSyncScaleAndSize(toplevel, 0, 0);
    if (window)
        wpe_toplevel_android_set_window(toplevel, window);
    return WPE_TOPLEVEL(toplevel);
}

void wpe_toplevel_android_set_background_color(WPEToplevelAndroid* toplevel, float red, float green, float blue)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    g_return_if_fail(red >= 0.0F && red <= 1.0F && green >= 0.0F && green <= 1.0F && blue >= 0.0F && blue <= 1.0F);

    toplevel->backgroundRed = red;
    toplevel->backgroundGreen = green;
    toplevel->backgroundBlue = blue;
    if (!toplevel->rootSurfaceControl)
        return;

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setColor(transaction, toplevel->rootSurfaceControl, red, green, blue, 1.0F, ADATASPACE_SRGB);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

// NOLINTNEXTLINE(readability-function-cognitive-complexity)
void wpe_toplevel_android_set_window(WPEToplevelAndroid* toplevel, ANativeWindow* window)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    if (toplevel->window == window && window) {
        int physicalWidth = ANativeWindow_getWidth(window);
        int physicalHeight = ANativeWindow_getHeight(window);
        wpeToplevelAndroidSyncScaleAndSize(toplevel, physicalWidth, physicalHeight);
        return;
    }

    if (window)
        ANativeWindow_acquire(window);

    g_clear_pointer(&toplevel->window, ANativeWindow_release);
    toplevel->window = window;

    int physicalWidth = window ? ANativeWindow_getWidth(window) : 0;
    int physicalHeight = window ? ANativeWindow_getHeight(window) : 0;
    wpeToplevelAndroidSyncScaleAndSize(toplevel, physicalWidth, physicalHeight);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();

    // The layer that a view left shows its page in the old window only.
    wpe_toplevel_android_remove_leaving_layer(toplevel, transaction);

    ASurfaceControl* oldRoot = std::exchange(toplevel->rootSurfaceControl, nullptr);
    if (oldRoot)
        ASurfaceTransaction_reparent(transaction, oldRoot, nullptr);

    toplevel->rootSurfaceControl = window ? ASurfaceControl_createFromWindow(window, "WPEToplevelRoot") : nullptr;
    if (toplevel->rootSurfaceControl) {
        // Cover Android's black SurfaceView background where the views show nothing, in the same transaction that
        // shows their layers.
        ASurfaceTransaction_setColor(transaction, toplevel->rootSurfaceControl, toplevel->backgroundRed,
            toplevel->backgroundGreen, toplevel->backgroundBlue, 1.0F, ADATASPACE_SRGB);
    }

    // The views that stay in the toplevel show their last frames at once in the new window.
    wpe_toplevel_foreach_view(
        WPE_TOPLEVEL(toplevel),
        [](WPEToplevel* toplevel, WPEView* view, gpointer transaction) {
            if (WPE_IS_VIEW_ANDROID(view)) {
                wpe_view_android_move_layer(
                    view, WPE_TOPLEVEL_ANDROID(toplevel), static_cast<ASurfaceTransaction*>(transaction));
            }
            return FALSE;
        },
        transaction);
    wpeToplevelAndroidUpdateState(toplevel);

    // FIXME: This does not support multi webview layouts in the window.
    // The views stay in the toplevel when it loses its window: WebView takes its view out before giving the
    // window up.
    gboolean hasWindow = window != nullptr;
    wpe_toplevel_foreach_view(
        WPE_TOPLEVEL(toplevel),
        [](WPEToplevel* currentToplevel, WPEView* view, gpointer userData) {
            gboolean hasWindow = static_cast<gboolean>(GPOINTER_TO_INT(userData));

            if (hasWindow) {
                int width, height;
                wpe_toplevel_get_size(currentToplevel, &width, &height);
                if (width > 0 && height > 0)
                    wpe_view_resized(view, width, height);
                wpe_view_map(view);
            } else
                wpe_view_unmap(view);

            return FALSE;
        },
        GINT_TO_POINTER(hasWindow));

    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
    if (oldRoot)
        ASurfaceControl_release(oldRoot);
}

void wpe_toplevel_android_set_window_focused(WPEToplevelAndroid* toplevel, gboolean focused)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    toplevel->windowFocused = focused;
    wpeToplevelAndroidUpdateState(toplevel);
}

// Sets the state without the callback. When the toplevel leaves fullscreen this way, WebKit takes the page out of
// fullscreen.
void wpe_toplevel_android_set_fullscreen(WPEToplevelAndroid* toplevel, gboolean fullscreen)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    toplevel->fullscreen = !!fullscreen;
    wpeToplevelAndroidUpdateState(toplevel);
}

void wpe_toplevel_android_set_fullscreen_callback(
    WPEToplevelAndroid* toplevel, WPEToplevelAndroidFullscreenCallback callback, gpointer userData)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    toplevel->fullscreenCallback = callback;
    toplevel->fullscreenCallbackUserData = userData;
}

ASurfaceControl* wpe_toplevel_android_get_root_surface_control(WPEToplevelAndroid* toplevel)
{
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), nullptr);
    return toplevel->rootSurfaceControl;
}

ANativeWindow* wpe_toplevel_android_get_window(WPEToplevelAndroid* toplevel)
{
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), nullptr);
    return toplevel->window;
}

// Hides the layer that a view left once the toplevel has no view, at the next iteration of the main loop, which leaves
// time for a view to join right after the old one left.
static void wpeToplevelAndroidScheduleLeavingIdle(WPEToplevelAndroid* toplevel)
{
    if (toplevel->leavingIdleID)
        return;

    toplevel->leavingIdleID = g_idle_add_full(
        G_PRIORITY_DEFAULT,
        +[](gpointer userData) -> gboolean {
            auto* toplevel = WPE_TOPLEVEL_ANDROID(userData);
            toplevel->leavingIdleID = 0;
            if (!wpe_toplevel_get_n_views(WPE_TOPLEVEL(toplevel)))
                wpeToplevelAndroidHideLeavingLayer(toplevel);
            return G_SOURCE_REMOVE;
        },
        toplevel, nullptr);
}

// The layer of a view that leaves the toplevel stays on screen until the view that takes its place shows something,
// either its last frame when it joins or its first frame, so that the toplevel shows neither its background nor the
// old page in between. After the timeout the layer goes anyway, and the toplevel shows its background color. When no
// view takes its place, or the one that did leaves without showing anything, the layer goes at the next iteration of
// the main loop. A view whose layer is not under the root, because it never showed anything here, leaves nothing.
void wpe_toplevel_android_view_left(WPEToplevelAndroid* toplevel, WPEView* view, gboolean layerShown)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    g_return_if_fail(WPE_IS_VIEW(view));

    if (!layerShown) {
        if (toplevel->leavingView)
            wpeToplevelAndroidScheduleLeavingIdle(toplevel);
        return;
    }

    // Only the layer of the view that left last can still be on screen.
    wpeToplevelAndroidHideLeavingLayer(toplevel);
    toplevel->leavingView = WPE_VIEW(g_object_ref(view));
    wpeToplevelAndroidScheduleLeavingIdle(toplevel);
    toplevel->leavingTimeoutID = g_timeout_add_full(
        G_PRIORITY_DEFAULT, s_leavingLayerTimeoutMs,
        +[](gpointer userData) -> gboolean {
            auto* toplevel = WPE_TOPLEVEL_ANDROID(userData);
            toplevel->leavingTimeoutID = 0;
            wpeToplevelAndroidHideLeavingLayer(toplevel);
            return G_SOURCE_REMOVE;
        },
        toplevel, nullptr);
}

// The view took its layer back, or moved it to another toplevel.
void wpe_toplevel_android_forget_leaving_view(WPEToplevelAndroid* toplevel, WPEView* view)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    if (toplevel->leavingView == view)
        wpeToplevelAndroidForgetLeavingView(toplevel);
}

// Takes the layer that a view left out of the window with the transaction, which shows what takes its place. Dropping
// the reference can destroy the view, which then leaves its layer to the transaction.
void wpe_toplevel_android_remove_leaving_layer(WPEToplevelAndroid* toplevel, ASurfaceTransaction* transaction)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    if (!toplevel->leavingView)
        return;

    wpe_view_android_hide_layer(toplevel->leavingView, toplevel, transaction);
    wpeToplevelAndroidForgetLeavingView(toplevel);
}
