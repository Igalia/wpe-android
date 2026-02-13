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

#include "WPEToplevelAndroid.h"

#include "Logging.h"
#include "RendererSurfaceControl.h"
#include "WPEDisplayAndroid.h"
#include "WPEInputMethodContextAndroid.h"

struct _WPEToplevelAndroid {
    WPEToplevel parent;
    std::shared_ptr<RendererSurfaceControl> renderer;
};

G_DEFINE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE_TYPE_TOPLEVEL)

static void wpeToplevelAndroidConstructed(GObject* object)
{
    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->constructed(object);

    Logging::logDebug("WPEToplevelAndroid::constructed(%p)", object);

    auto* toplevel = WPE_TOPLEVEL(object);

    // Initialize the toplevel with ACTIVE state
    // Android apps start in active state when visible
    wpe_toplevel_state_changed(toplevel, WPE_TOPLEVEL_STATE_ACTIVE);
}

static void wpeToplevelAndroidDispose(GObject* object)
{
    Logging::logDebug("WPEToplevelAndroid::dispose(%p)", object);

    auto* toplevel = WPE_TOPLEVEL_ANDROID(object);
    toplevel->renderer.reset();

    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->dispose(object);
}

static gboolean wpeToplevelAndroidResize(WPEToplevel* toplevel, int width, int height)
{
    Logging::logDebug("WPEToplevelAndroid::resize(%p, %d, %d)", toplevel, width, height);

    struct Size {
        int width;
        int height;
    } size = {width, height};

    wpe_toplevel_resized(toplevel, width, height);
    wpe_toplevel_foreach_view(
        toplevel,
        +[](WPEToplevel* /*toplevel*/, WPEView* item, gpointer userData) -> gboolean {
            auto& size = *static_cast<Size*>(userData);
            wpe_view_resized(item, size.width, size.height);
            return TRUE;
        },
        &size);

    return TRUE;
}

static gboolean wpeToplevelAndroidSetFullscreen(WPEToplevel* toplevel, gboolean fullscreen)
{
    Logging::logDebug("WPEToplevelAndroid::set_fullscreen(%p, %s)", toplevel, fullscreen ? "true" : "false");

    WPEToplevelState const currentState = wpe_toplevel_get_state(toplevel);
    WPEToplevelState newState;

    if (fullscreen) {
        newState = static_cast<WPEToplevelState>(currentState | WPE_TOPLEVEL_STATE_FULLSCREEN);
    } else {
        newState = static_cast<WPEToplevelState>(currentState & ~WPE_TOPLEVEL_STATE_FULLSCREEN);
    }

    if (newState != currentState)
        wpe_toplevel_state_changed(toplevel, newState);

    return TRUE;
}

static gboolean wpeToplevelAndroidSetMaximized(WPEToplevel* toplevel, gboolean maximized)
{
    Logging::logDebug("WPEToplevelAndroid::set_maximized(%p, %s)", toplevel, maximized ? "true" : "false");

    WPEToplevelState const currentState = wpe_toplevel_get_state(toplevel);
    WPEToplevelState newState;

    if (maximized) {
        newState = static_cast<WPEToplevelState>(currentState | WPE_TOPLEVEL_STATE_MAXIMIZED);
    } else {
        newState = static_cast<WPEToplevelState>(currentState & ~WPE_TOPLEVEL_STATE_MAXIMIZED);
    }

    if (newState != currentState)
        wpe_toplevel_state_changed(toplevel, newState);

    return TRUE;
}

static gboolean wpeToplevelAndroidSetMinimized(WPEToplevel* toplevel)
{
    Logging::logDebug("WPEToplevelAndroid::set_minimized(%p)", toplevel);

    WPEToplevelState const currentState = wpe_toplevel_get_state(toplevel);
    auto newState = static_cast<WPEToplevelState>(currentState & ~WPE_TOPLEVEL_STATE_ACTIVE);

    if (newState != currentState)
        wpe_toplevel_state_changed(toplevel, newState);

    return TRUE;
}

static void wpe_toplevel_android_class_init(WPEToplevelAndroidClass* toplevelAndroidClass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(toplevelAndroidClass);
    objectClass->dispose = wpeToplevelAndroidDispose;
    objectClass->constructed = wpeToplevelAndroidConstructed;

    WPEToplevelClass* toplevelClass = WPE_TOPLEVEL_CLASS(toplevelAndroidClass);
    toplevelClass->resize = wpeToplevelAndroidResize;
    toplevelClass->set_fullscreen = wpeToplevelAndroidSetFullscreen;
    toplevelClass->set_maximized = wpeToplevelAndroidSetMaximized;
    toplevelClass->set_minimized = wpeToplevelAndroidSetMinimized;
}

static void wpe_toplevel_android_init(WPEToplevelAndroid* toplevel)
{
    Logging::logDebug("WPEToplevelAndroid::init(%p)", toplevel);
}

WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);

    Logging::logDebug("wpe_toplevel_android_new(%p)", display);

    return WPE_TOPLEVEL(g_object_new(WPE_TYPE_TOPLEVEL_ANDROID, "display", display, nullptr));
}

void wpe_toplevel_android_resize(WPEToplevelAndroid* toplevel, int width, int height)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::resize(%p, %d, %d)", toplevel, width, height);
    wpe_toplevel_resized(WPE_TOPLEVEL(toplevel), width, height);
}

void wpe_toplevel_android_set_scale(WPEToplevel* toplevel, double scale)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::set_scale(%p, %f)", toplevel, scale);

    wpe_toplevel_scale_changed(toplevel, scale);
}

void wpe_toplevel_android_set_toplevel_state(WPEToplevel* toplevel, WPEToplevelState state)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::set_toplevel_state(%p, %u)", toplevel, static_cast<unsigned>(state));

    wpe_toplevel_state_changed(toplevel, state);
}

void wpe_toplevel_android_set_renderer(
    WPEToplevelAndroid* toplevel, const std::shared_ptr<RendererSurfaceControl>& renderer)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::set_renderer(%p, %p)", toplevel, renderer.get());

    toplevel->renderer = renderer;
    if (renderer)
        renderer->setWPEToplevel(WPE_TOPLEVEL(toplevel));
}

void wpe_toplevel_android_on_surface_created(WPEToplevelAndroid* toplevel, ANativeWindow* window)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::on_surface_created(%p, %p)", toplevel, window);

    if (toplevel->renderer)
        toplevel->renderer->onSurfaceCreated(window);
}

void wpe_toplevel_android_on_surface_changed(WPEToplevelAndroid* toplevel, int format, uint32_t width, uint32_t height)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::on_surface_changed(%p, %d, %u, %u)", toplevel, format, width, height);

    if (toplevel->renderer)
        toplevel->renderer->onSurfaceChanged(format, width, height);
}

void wpe_toplevel_android_on_surface_redraw_needed(WPEToplevelAndroid* toplevel)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::on_surface_redraw_needed(%p)", toplevel);

    if (toplevel->renderer)
        toplevel->renderer->onSurfaceRedrawNeeded();
}

void wpe_toplevel_android_on_surface_destroyed(WPEToplevelAndroid* toplevel)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::on_surface_destroyed(%p)", toplevel);

    if (toplevel->renderer)
        toplevel->renderer->onSurfaceDestroyed();
}

void wpe_toplevel_android_commit_buffer(WPEToplevelAndroid* toplevel, AHardwareBuffer* hardwareBuffer,
    WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::wpe_toplevel_android_commit_buffer(%p)", toplevel);

    if (toplevel->renderer)
        toplevel->renderer->commitBuffer(hardwareBuffer, wpeBuffer, fenceFD);
}

void wpe_toplevel_android_set_visible_view(WPEToplevelAndroid* toplevel, WPEViewAndroid* visibleView)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::set_visible_view(%p)", toplevel);

    if (toplevel->renderer)
        toplevel->renderer->setVisibleWPEView(WPE_VIEW(visibleView));
}
