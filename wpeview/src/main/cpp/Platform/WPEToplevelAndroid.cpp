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

#include <cmath>

struct _WPEToplevelAndroid {
    WPEToplevel parent;
    ANativeWindow* window;
    ASurfaceControl* surfaceControl;
    ASurfaceControl* rootSurfaceControl;
};

G_DEFINE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE_TYPE_TOPLEVEL)

static gboolean wpeToplevelAndroidResize(WPEToplevel* toplevel, int width, int height)
{
    wpe_toplevel_resized(toplevel, width, height);
    return TRUE;
}

static WPEScreen* wpeToplevelAndroidGetScreen(WPEToplevel* toplevel)
{
    WPEDisplay* display = wpe_toplevel_get_display(toplevel);
    return display ? wpe_display_get_screen(display, 0) : nullptr;
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

    auto* baseToplevel = WPE_TOPLEVEL(toplevel);
    float scale = wpeToplevelAndroidGetScreenScale(toplevel);
    if (wpe_toplevel_get_scale(baseToplevel) != scale)
        wpe_toplevel_scale_changed(baseToplevel, scale);

    int logicalWidth = wpeToplevelAndroidLogicalDimension(physicalWidth, scale);
    int logicalHeight = wpeToplevelAndroidLogicalDimension(physicalHeight, scale);
    wpe_toplevel_resized(baseToplevel, logicalWidth, logicalHeight);
}

void wpe_toplevel_android_set_physical_size(WPEToplevelAndroid* toplevel, int width, int height)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    wpeToplevelAndroidSyncScaleAndSize(toplevel, width, height);
}

static void wpeToplevelAndroidFinalize(GObject* object)
{
    auto* toplevel = WPE_TOPLEVEL_ANDROID(object);
    if (toplevel->surfaceControl)
        ASurfaceControl_release(toplevel->surfaceControl);
    if (toplevel->rootSurfaceControl)
        ASurfaceControl_release(toplevel->rootSurfaceControl);
    g_clear_pointer(&toplevel->window, ANativeWindow_release);

    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->finalize(object);
}

static void wpe_toplevel_android_class_init(WPEToplevelAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->finalize = wpeToplevelAndroidFinalize;

    WPEToplevelClass* toplevelClass = WPE_TOPLEVEL_CLASS(klass);
    toplevelClass->resize = wpeToplevelAndroidResize;
    toplevelClass->get_screen = wpeToplevelAndroidGetScreen;
}

static void wpe_toplevel_android_init(WPEToplevelAndroid*)
{
}

WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display, ANativeWindow* window)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);
    auto* toplevel = WPE_TOPLEVEL_ANDROID(g_object_new(WPE_TYPE_TOPLEVEL_ANDROID, "display", display, nullptr));
    wpeToplevelAndroidSyncScaleAndSize(toplevel, 0, 0);
    if (window)
        wpe_toplevel_android_set_window(toplevel, window);
    return WPE_TOPLEVEL(toplevel);
}

void wpe_toplevel_android_set_window(WPEToplevelAndroid* toplevel, ANativeWindow* window)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    if (window)
        ANativeWindow_acquire(window);

    g_clear_pointer(&toplevel->window, ANativeWindow_release);
    toplevel->window = window;

    int physicalWidth = window ? ANativeWindow_getWidth(window) : 0;
    int physicalHeight = window ? ANativeWindow_getHeight(window) : 0;
    wpeToplevelAndroidSyncScaleAndSize(toplevel, physicalWidth, physicalHeight);

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();

    if (window) {
        ASurfaceControl* newRoot = ASurfaceControl_createFromWindow(window, "WPEToplevelRoot");

        if (newRoot) {
            if (!toplevel->surfaceControl) {
                toplevel->surfaceControl = ASurfaceControl_create(newRoot, "WPEWebLayer");
            } else {
                ASurfaceTransaction_reparent(transaction, toplevel->surfaceControl, newRoot);
            }
            if (toplevel->surfaceControl) {
                ASurfaceTransaction_setVisibility(
                    transaction, toplevel->surfaceControl, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
                ASurfaceTransaction_setZOrder(transaction, toplevel->surfaceControl, 1);
                ASurfaceTransaction_setPosition(transaction, toplevel->surfaceControl, 0, 0);
            }
        }

        if (toplevel->rootSurfaceControl)
            ASurfaceControl_release(toplevel->rootSurfaceControl);
        toplevel->rootSurfaceControl = newRoot;

        wpe_toplevel_state_changed(WPE_TOPLEVEL(toplevel),
            static_cast<WPEToplevelState>(WPE_TOPLEVEL_STATE_MAXIMIZED | WPE_TOPLEVEL_STATE_ACTIVE));
    } else {
        if (toplevel->surfaceControl)
            ASurfaceTransaction_reparent(transaction, toplevel->surfaceControl, nullptr);
        if (toplevel->rootSurfaceControl) {
            ASurfaceControl_release(toplevel->rootSurfaceControl);
            toplevel->rootSurfaceControl = nullptr;
        }
        wpe_toplevel_state_changed(WPE_TOPLEVEL(toplevel), WPE_TOPLEVEL_STATE_NONE);
    }

    // FIXME: This does not support multi webview layouts in the window.
    gboolean hasWindow = window != nullptr;
    wpe_toplevel_foreach_view(
        WPE_TOPLEVEL(toplevel),
        [](WPEToplevel* currentToplevel, WPEView* view, gpointer userData) {
            gboolean hasWindow = static_cast<gboolean>(GPOINTER_TO_INT(userData));

            wpe_view_set_toplevel(view, hasWindow ? currentToplevel : nullptr);

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
}

ASurfaceControl* wpe_toplevel_android_get_surface_control(WPEToplevelAndroid* toplevel)
{
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), nullptr);
    return toplevel->surfaceControl;
}

ANativeWindow* wpe_toplevel_android_get_window(WPEToplevelAndroid* toplevel)
{
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), nullptr);
    return toplevel->window;
}
