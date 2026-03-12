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

static inline WPEToplevelState operator|(WPEToplevelState a, WPEToplevelState b)
{
    // NOLINTNEXTLINE(clang-analyzer-optin.core.EnumCastOutOfRange)
    return static_cast<WPEToplevelState>(static_cast<unsigned>(a) | static_cast<unsigned>(b));
}
static inline WPEToplevelState operator&(WPEToplevelState a, WPEToplevelState b)
{
    // NOLINTNEXTLINE(clang-analyzer-optin.core.EnumCastOutOfRange)
    return static_cast<WPEToplevelState>(static_cast<unsigned>(a) & static_cast<unsigned>(b));
}
static inline WPEToplevelState operator~(WPEToplevelState a)
{
    // NOLINTNEXTLINE(clang-analyzer-optin.core.EnumCastOutOfRange)
    return static_cast<WPEToplevelState>(~static_cast<unsigned>(a));
}

struct _WPEToplevelAndroid {
    WPEToplevel parent;
    ANativeWindow* window;
};

G_DEFINE_FINAL_TYPE(WPEToplevelAndroid, wpe_toplevel_android, WPE_TYPE_TOPLEVEL)

static gboolean wpeToplevelAndroidResize(WPEToplevel* toplevel, int width, int height)
{
    Logging::logDebug("WPEToplevelAndroid::resize(%p, %d, %d)", toplevel, width, height);
    wpe_toplevel_resized(toplevel, width, height);
    return TRUE;
}

static void wpeToplevelAndroidSetStateFlag(WPEToplevel* toplevel, gboolean enabled, WPEToplevelState flag)
{
    WPEToplevelState currentState = wpe_toplevel_get_state(toplevel);
    WPEToplevelState newState = enabled ? (currentState | flag) : (currentState & ~flag);
    if (newState != currentState)
        wpe_toplevel_state_changed(toplevel, newState);
}

static gboolean wpeToplevelAndroidSetFullscreen(WPEToplevel* toplevel, gboolean fullscreen)
{
    Logging::logDebug("WPEToplevelAndroid::set_fullscreen(%p, %s)", toplevel, fullscreen ? "true" : "false");
    wpeToplevelAndroidSetStateFlag(toplevel, fullscreen, WPE_TOPLEVEL_STATE_FULLSCREEN);
    return TRUE;
}

static gboolean wpeToplevelAndroidSetMaximized(WPEToplevel* toplevel, gboolean maximized)
{
    Logging::logDebug("WPEToplevelAndroid::set_maximized(%p, %s)", toplevel, maximized ? "true" : "false");
    wpeToplevelAndroidSetStateFlag(toplevel, maximized, WPE_TOPLEVEL_STATE_MAXIMIZED);
    return TRUE;
}

static void wpeToplevelAndroidSetTitle(WPEToplevel*, const char*)
{
}

static WPEScreen* wpeToplevelAndroidGetScreen(WPEToplevel* toplevel)
{
    WPEDisplay* display = wpe_toplevel_get_display(toplevel);
    return display ? wpe_display_get_screen(display, 0) : nullptr;
}

static void wpeToplevelAndroidFinalize(GObject* object)
{
    auto* toplevel = WPE_TOPLEVEL_ANDROID(object);
    if (toplevel->window) {
        ANativeWindow_release(toplevel->window);
        toplevel->window = nullptr;
    }

    G_OBJECT_CLASS(wpe_toplevel_android_parent_class)->finalize(object);
}

static void wpe_toplevel_android_class_init(WPEToplevelAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->finalize = wpeToplevelAndroidFinalize;

    WPEToplevelClass* toplevelClass = WPE_TOPLEVEL_CLASS(klass);
    toplevelClass->resize = wpeToplevelAndroidResize;
    toplevelClass->set_title = wpeToplevelAndroidSetTitle;
    toplevelClass->set_fullscreen = wpeToplevelAndroidSetFullscreen;
    toplevelClass->set_maximized = wpeToplevelAndroidSetMaximized;
    toplevelClass->get_screen = wpeToplevelAndroidGetScreen;
}

static void wpe_toplevel_android_init(WPEToplevelAndroid*)
{
}

WPEToplevel* wpe_toplevel_android_new(WPEDisplay* display, ANativeWindow* window)
{
    g_return_val_if_fail(WPE_IS_DISPLAY(display), nullptr);
    auto* toplevel = WPE_TOPLEVEL_ANDROID(g_object_new(WPE_TYPE_TOPLEVEL_ANDROID, "display", display, nullptr));
    if (window)
        wpe_toplevel_android_on_window_created(toplevel, window);
    return WPE_TOPLEVEL(toplevel);
}

void wpe_toplevel_android_on_window_created(WPEToplevelAndroid* toplevel, ANativeWindow* window)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));
    g_return_if_fail(window != nullptr);

    Logging::logDebug("WPEToplevelAndroid::on_window_created(%p, %p)", toplevel, window);
    if (toplevel->window)
        ANativeWindow_release(toplevel->window);
    ANativeWindow_acquire(window);
    toplevel->window = window;
}

void wpe_toplevel_android_on_window_destroyed(WPEToplevelAndroid* toplevel)
{
    g_return_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel));

    Logging::logDebug("WPEToplevelAndroid::on_window_destroyed(%p)", toplevel);
    if (toplevel->window) {
        ANativeWindow_release(toplevel->window);
        toplevel->window = nullptr;
    }
}

ANativeWindow* wpe_toplevel_android_get_window(WPEToplevelAndroid* toplevel)
{
    g_return_val_if_fail(WPE_IS_TOPLEVEL_ANDROID(toplevel), nullptr);
    return toplevel->window;
}
