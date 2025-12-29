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

/**
 * WPEToplevelAndroid:
 *
 * Android implementation of #WPEToplevel for the WPE Platform API.
 * This provides a minimal toplevel implementation for Android applications.
 */

struct _WPEToplevelAndroid {
    WPEToplevel parent;
};

typedef struct {
    // Reserved for future use
} WPEToplevelAndroidPrivate;

G_DEFINE_TYPE_WITH_PRIVATE(WPEToplevelAndroid, wpe_toplevel_android, WPE_TYPE_TOPLEVEL)

static void wpeToplevelAndroidConstructed(GObject* object)
{
    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->constructed(object);

    Logging::logDebug("WPEToplevelAndroid::constructed(%p)", object);

    auto* toplevel = WPE_TOPLEVEL(object);

    // Initialize the toplevel with ACTIVE state
    // Android apps start in active state when visible
    wpe_toplevel_state_changed(toplevel, WPE_TOPLEVEL_STATE_ACTIVE);
}

static void wpeToplevelAndroidSetTitle(WPEToplevel* toplevel, const char* title)
{
    Logging::logDebug("WPEToplevelAndroid::set_title(%p, %s)", toplevel, title ? title : "(null)");
}

static WPEScreen* wpeToplevelAndroidGetScreen(WPEToplevel* toplevel)
{
    Logging::logDebug("WPEToplevelAndroid::get_screen(%p)", toplevel);
    return nullptr;
}

static gboolean wpeToplevelAndroidResize(WPEToplevel* toplevel, int width, int height)
{
    Logging::logDebug("WPEToplevelAndroid::resize(%p, %d, %d)", toplevel, width, height);
    wpe_toplevel_resized(toplevel, width, height);
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
    return TRUE;
}

static WPEBufferFormats* wpeToplevelAndroidGetPreferredBufferFormats(WPEToplevel* toplevel)
{
    auto* display = wpe_toplevel_get_display(toplevel);

    if (display) {
        return wpe_display_get_preferred_buffer_formats(display);
    }
    return nullptr;
}

static void wpe_toplevel_android_class_init(WPEToplevelAndroidClass* toplevelAndroidClass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(toplevelAndroidClass);
    objectClass->constructed = wpeToplevelAndroidConstructed;

    WPEToplevelClass* toplevelClass = WPE_TOPLEVEL_CLASS(toplevelAndroidClass);
    toplevelClass->set_title = wpeToplevelAndroidSetTitle;
    toplevelClass->get_screen = wpeToplevelAndroidGetScreen;
    toplevelClass->resize = wpeToplevelAndroidResize;
    toplevelClass->set_fullscreen = wpeToplevelAndroidSetFullscreen;
    toplevelClass->set_maximized = wpeToplevelAndroidSetMaximized;
    toplevelClass->set_minimized = wpeToplevelAndroidSetMinimized;
    toplevelClass->get_preferred_buffer_formats = wpeToplevelAndroidGetPreferredBufferFormats;
}

static void wpe_toplevel_android_init(WPEToplevelAndroid* toplevel)
{
    Logging::logDebug("WPEToplevelAndroid::init(%p)", toplevel);
}

/**
 * wpe_toplevel_android_new:
 * @display: a #WPEDisplay
 *
 * Create a new #WPEToplevel on @display.
 *
 * Returns: (transfer full): a #WPEToplevel
 */
WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);

    Logging::logDebug("wpe_toplevel_android_new(%p)", display);

    return WPE_TOPLEVEL(g_object_new(WPE_TYPE_TOPLEVEL_ANDROID, "display", display, nullptr));
}
