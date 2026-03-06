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

#include "WPEDisplayAndroid.h"

#include "Logging.h"
#include "WPEInputMethodContextAndroid.h"
#include "WPEScreenAndroid.h"
#include "WPEToplevelAndroid.h"
#include "WPEViewAndroid.h"

#include <wpe/WPEKeymapXKB.h>

#include <EGL/egl.h>
#include <drm/drm_fourcc.h>
#include <gio/gio.h>

static inline WPEAvailableInputDevices operator|(WPEAvailableInputDevices a, WPEAvailableInputDevices b)
{
    // NOLINTNEXTLINE(clang-analyzer-optin.core.EnumCastOutOfRange)
    return static_cast<WPEAvailableInputDevices>(static_cast<unsigned>(a) | static_cast<unsigned>(b));
}

struct _WPEDisplayAndroid {
    WPEDisplay parent;
    EGLDisplay eglDisplay;
    WPEKeymap* keymap;
    WPEScreen* screen;
};

G_DEFINE_FINAL_TYPE(WPEDisplayAndroid, wpe_display_android, WPE_TYPE_DISPLAY)

static void wpeDisplayAndroidDispose(GObject* object)
{
    auto* display = WPE_DISPLAY_ANDROID(object);

    g_clear_object(&display->keymap);
    g_clear_object(&display->screen);

    G_OBJECT_CLASS(wpe_display_android_parent_class)->dispose(object);
}

static void wpeDisplayAndroidFinalize(GObject* object)
{
    auto* display = WPE_DISPLAY_ANDROID(object);

    if (display->eglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(display->eglDisplay);
        display->eglDisplay = EGL_NO_DISPLAY;
    }

    G_OBJECT_CLASS(wpe_display_android_parent_class)->finalize(object);
}

static gboolean wpeDisplayAndroidConnect(WPEDisplay* display, GError** error)
{
    auto* displayAndroid = WPE_DISPLAY_ANDROID(display);

    if (displayAndroid->eglDisplay != EGL_NO_DISPLAY) {
        g_set_error_literal(
            error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "WPEDisplayAndroid: already connected");
        return FALSE;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        Logging::logError("WPEDisplayAndroid: eglGetDisplay failed with error 0x%04X", eglGetError());
        g_set_error_literal(error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "Failed to get EGL display");
        return FALSE;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(eglDisplay, &major, &minor)) {
        Logging::logError("WPEDisplayAndroid: eglInitialize failed with error 0x%04X", eglGetError());
        g_set_error_literal(error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "Failed to initialize EGL");
        return FALSE;
    }

    Logging::logDebug("WPEDisplayAndroid: EGL initialized: version %d.%d", major, minor);
    displayAndroid->eglDisplay = eglDisplay;
    displayAndroid->keymap = wpe_keymap_xkb_new();
    displayAndroid->screen = wpe_screen_android_new();
    return TRUE;
}

static gpointer wpeDisplayAndroidGetEGLDisplay(WPEDisplay* display, GError**)
{
    auto* displayAndroid = WPE_DISPLAY_ANDROID(display);
    return displayAndroid->eglDisplay;
}

static gboolean wpeDisplayAndroidUseExplicitSync(WPEDisplay*)
{
    return TRUE;
}

static WPEBufferFormats* wpeDisplayAndroidGetPreferredBufferFormats(WPEDisplay*)
{
    static constexpr uint32_t formats[] = {
        DRM_FORMAT_RGBA8888,
        DRM_FORMAT_RGBX8888,
        DRM_FORMAT_RGB888,
        DRM_FORMAT_RGBA1010102,
        DRM_FORMAT_RGB565,
    };

    auto* builder = wpe_buffer_formats_builder_new(nullptr);
    wpe_buffer_formats_builder_append_group(builder, nullptr, WPE_BUFFER_FORMAT_USAGE_RENDERING);

    // Android does not use DRM_FORMAT but we use NONE to set it to zero.
    for (auto format : formats)
        wpe_buffer_formats_builder_append_format(builder, format, DRM_FORMAT_MOD_NONE);

    return wpe_buffer_formats_builder_end(builder);
}

static WPEView* wpeDisplayAndroidCreateView(WPEDisplay* display)
{
    return wpe_view_android_new(display);
}

static WPEToplevel* wpeDisplayAndroidCreateToplevel(WPEDisplay* display, guint)
{
    return wpe_toplevel_android_new(display, nullptr);
}

static WPEInputMethodContext* wpeDisplayAndroidCreateInputMethodContext(WPEDisplay*, WPEView* view)
{
    return wpe_input_method_context_android_new(view);
}

static WPEKeymap* wpeDisplayAndroidGetKeymap(WPEDisplay* display)
{
    return WPE_DISPLAY_ANDROID(display)->keymap;
}

static guint wpeDisplayAndroidGetNScreens(WPEDisplay* display)
{
    return WPE_DISPLAY_ANDROID(display)->screen ? 1 : 0;
}

static WPEScreen* wpeDisplayAndroidGetScreen(WPEDisplay* display, guint index)
{
    auto* displayAndroid = WPE_DISPLAY_ANDROID(display);
    return index == 0 ? displayAndroid->screen : nullptr;
}

static void wpe_display_android_class_init(WPEDisplayAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeDisplayAndroidDispose;
    objectClass->finalize = wpeDisplayAndroidFinalize;

    WPEDisplayClass* displayClass = WPE_DISPLAY_CLASS(klass);
    displayClass->connect = wpeDisplayAndroidConnect;
    displayClass->get_egl_display = wpeDisplayAndroidGetEGLDisplay;
    displayClass->get_keymap = wpeDisplayAndroidGetKeymap;
    displayClass->get_n_screens = wpeDisplayAndroidGetNScreens;
    displayClass->get_screen = wpeDisplayAndroidGetScreen;
    displayClass->use_explicit_sync = wpeDisplayAndroidUseExplicitSync;
    displayClass->get_preferred_buffer_formats = wpeDisplayAndroidGetPreferredBufferFormats;
    displayClass->create_toplevel = wpeDisplayAndroidCreateToplevel;
    displayClass->create_view = wpeDisplayAndroidCreateView;
    displayClass->create_input_method_context = wpeDisplayAndroidCreateInputMethodContext;

    g_io_extension_point_implement(WPE_DISPLAY_EXTENSION_POINT_NAME, WPE_TYPE_DISPLAY_ANDROID, "android", 10);
}

static void wpe_display_android_init(WPEDisplayAndroid* display)
{
    auto inputDevices = WPE_AVAILABLE_INPUT_DEVICE_TOUCHSCREEN | WPE_AVAILABLE_INPUT_DEVICE_KEYBOARD;
    wpe_display_set_available_input_devices(WPE_DISPLAY(display), inputDevices);
}

WPEDisplay* wpe_display_android_new(void)
{
    return WPE_DISPLAY(g_object_new(WPE_TYPE_DISPLAY_ANDROID, nullptr));
}
