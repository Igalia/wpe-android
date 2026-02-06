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

#include "WPEDisplayAndroid.h"

#include "Logging.h"
#include "WPEInputMethodContextAndroid.h"
#include "WPEToplevelAndroid.h"
#include "WPEViewAndroid.h"

#include <EGL/egl.h>
#include <android/hardware_buffer.h>
#include <drm/drm_fourcc.h>

struct _WPEDisplayAndroid {
    WPEDisplay parent;
    EGLDisplay eglDisplay;
};

G_DEFINE_FINAL_TYPE(WPEDisplayAndroid, wpe_display_android, WPE_TYPE_DISPLAY)

static void wpeDisplayAndroidDispose(GObject* object)
{
    Logging::logDebug("WPEDisplayAndroid::dispose(%p)", object);

    auto* display = WPE_DISPLAY_ANDROID(object);

    if (display->eglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(display->eglDisplay);
        display->eglDisplay = EGL_NO_DISPLAY;
    }

    G_OBJECT_CLASS(wpe_display_android_parent_class)->dispose(object);
}

static gboolean wpeDisplayAndroidConnect(WPEDisplay* display, GError** error)
{
    Logging::logDebug("WPEDisplayAndroid::connect(%p)", display);

    auto* displayAndroid = WPE_DISPLAY_ANDROID(display);

    if (displayAndroid->eglDisplay != EGL_NO_DISPLAY)
        return TRUE;

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        Logging::logError("WPEDisplayAndroid::get_egl_display - eglGetDisplay failed with error 0x%04X", eglGetError());
        g_set_error_literal(error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "Failed to get EGL display");
        return FALSE;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(eglDisplay, &major, &minor)) {
        Logging::logError("WPEDisplayAndroid::get_egl_display - eglInitialize failed with error 0x%04X", eglGetError());
        g_set_error_literal(error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "Failed to initialize EGL");
        return FALSE;
    }

    Logging::logDebug("EGL initialized: version %d.%d", major, minor);
    displayAndroid->eglDisplay = eglDisplay;
    return TRUE;
}

static WPEView* wpeDisplayAndroidCreateView(WPEDisplay* display)
{
    Logging::logDebug("WPEDisplayAndroid::create_view(%p)", display);

    auto* view = wpe_view_android_new(display);

    return view;
}

static WPEToplevel* wpeDisplayAndroidCreateToplevel(WPEDisplay* display, guint /*max_views*/)
{
    Logging::logDebug("WPEDisplayAndroid::create_view(%p)", display);

    auto* toplevel = wpe_toplevel_android_new(WPE_DISPLAY(display));

    return toplevel;
}

static gpointer wpeDisplayAndroidGetEGLDisplay(WPEDisplay* display, GError** /*error*/)
{
    Logging::logDebug("WPEDisplayAndroid::get_egl_display(%p)", display);

    auto* displayAndroid = WPE_DISPLAY_ANDROID(display);

    return displayAndroid->eglDisplay;
}

static gboolean wpeDisplayAndroidUseExplicitSync(WPEDisplay*)
{
    return TRUE;
}

static WPEInputMethodContext* wpeDisplayAndroidCreateInputMethodContext(WPEDisplay* display, WPEView* view)
{
    Logging::logDebug("WPEDisplayAndroid::create_input_method_context(%p, %p)", display, view);
    return wpe_input_method_context_android_new(view);
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

    for (auto format : formats)
        wpe_buffer_formats_builder_append_format(builder, format, 0);

    return wpe_buffer_formats_builder_end(builder);
}

static void wpe_display_android_class_init(WPEDisplayAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeDisplayAndroidDispose;

    WPEDisplayClass* displayClass = WPE_DISPLAY_CLASS(klass);
    displayClass->connect = wpeDisplayAndroidConnect;
    displayClass->create_view = wpeDisplayAndroidCreateView;
    displayClass->create_toplevel = wpeDisplayAndroidCreateToplevel;
    displayClass->get_egl_display = wpeDisplayAndroidGetEGLDisplay;
    displayClass->get_preferred_buffer_formats = wpeDisplayAndroidGetPreferredBufferFormats;
    displayClass->use_explicit_sync = wpeDisplayAndroidUseExplicitSync;
    displayClass->create_input_method_context = wpeDisplayAndroidCreateInputMethodContext;
}

static void wpe_display_android_init(WPEDisplayAndroid* display)
{
    Logging::logDebug("WPEDisplayAndroid::init(%p)", display);

    display->eglDisplay = EGL_NO_DISPLAY;

    // NOLINTNEXTLINE(clang-analyzer-optin.core.EnumCastOutOfRange)
    auto inputDevices = static_cast<WPEAvailableInputDevices>(
        WPE_AVAILABLE_INPUT_DEVICE_TOUCHSCREEN | WPE_AVAILABLE_INPUT_DEVICE_KEYBOARD);
    wpe_display_set_available_input_devices(WPE_DISPLAY(display), inputDevices);
}

WPEDisplay* wpe_display_android_new(void)
{
    return WPE_DISPLAY(g_object_new(WPE_TYPE_DISPLAY_ANDROID, nullptr));
}

