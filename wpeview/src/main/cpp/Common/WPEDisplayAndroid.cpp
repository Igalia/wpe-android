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
#include "WPEToplevelAndroid.h"
#include "WPEViewAndroid.h"

#include <EGL/egl.h>
#include <android/hardware_buffer.h>

struct _WPEDisplayAndroid {
    WPEDisplay parent;
};

typedef struct {
    gpointer eglDisplay;
    WPEToplevel* toplevel;
} WPEDisplayAndroidPrivate;

G_DEFINE_TYPE_WITH_PRIVATE(WPEDisplayAndroid, wpe_display_android, WPE_TYPE_DISPLAY)

static void wpeDisplayAndroidDispose(GObject* object)
{
    Logging::logDebug("WPEDisplayAndroid::dispose(%p)", object);

    auto* priv
        = static_cast<WPEDisplayAndroidPrivate*>(wpe_display_android_get_instance_private(WPE_DISPLAY_ANDROID(object)));

    // Clean up toplevel
    g_clear_object(&priv->toplevel);

    // Clean up EGL display if initialized
    if (priv->eglDisplay != nullptr) {
        eglTerminate(static_cast<EGLDisplay>(priv->eglDisplay));
        priv->eglDisplay = nullptr;
    }

    G_OBJECT_CLASS(wpe_display_android_parent_class)->dispose(object);
}

static gboolean wpeDisplayAndroidConnect(WPEDisplay* display, GError** /*error*/)
{
    Logging::logDebug("WPEDisplayAndroid::connect(%p)", display);
    return TRUE;
}

static WPEView* wpeDisplayAndroidCreateView(WPEDisplay* display)
{
    Logging::logDebug("WPEDisplayAndroid::create_view(%p)", display);
    return wpe_view_android_new(display);
}

static gpointer wpeDisplayAndroidGetEGLDisplay(WPEDisplay* display, GError** error)
{
    Logging::logDebug("WPEDisplayAndroid::get_egl_display(%p)", display);

    auto* priv = static_cast<WPEDisplayAndroidPrivate*>(
        wpe_display_android_get_instance_private(WPE_DISPLAY_ANDROID(display)));

    if (priv->eglDisplay != nullptr) {
        return priv->eglDisplay;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        EGLint eglError = eglGetError();
        Logging::logError("WPEDisplayAndroid::get_egl_display - eglGetDisplay failed with error 0x%x", eglError);
        g_set_error_literal(error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "Failed to get EGL display");
        return nullptr;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(eglDisplay, &major, &minor)) {
        EGLint eglError = eglGetError();
        Logging::logError("WPEDisplayAndroid::get_egl_display - eglInitialize failed with error 0x%x", eglError);
        g_set_error_literal(error, WPE_DISPLAY_ERROR, WPE_DISPLAY_ERROR_CONNECTION_FAILED, "Failed to initialize EGL");
        return nullptr;
    }

    Logging::logDebug("EGL initialized: version %d.%d", major, minor);

    priv->eglDisplay = eglDisplay;
    return priv->eglDisplay;
}

static WPEBufferDMABufFormats* wpeDisplayAndroidGetPreferredDMABufFormats(WPEDisplay* /*display*/)
{
    static const struct {
        uint32_t fourcc;
        uint64_t modifier;
    } formats[] = {
        {0x34325241, 0}, // DRM_FORMAT_RGBA8888
        {0x34325258, 0}, // DRM_FORMAT_RGBX8888
        {0x34324742, 0}, // DRM_FORMAT_RGB888
        {0x36314752, 0}, // DRM_FORMAT_RGB565
    };

    auto* builder = wpe_buffer_dma_buf_formats_builder_new(nullptr);
    wpe_buffer_dma_buf_formats_builder_append_group(builder, nullptr, WPE_BUFFER_DMA_BUF_FORMAT_USAGE_RENDERING);

    for (const auto& format : formats) {
        wpe_buffer_dma_buf_formats_builder_append_format(builder, format.fourcc, format.modifier);
    }

    return wpe_buffer_dma_buf_formats_builder_end(builder);
}

static void wpe_display_android_class_init(WPEDisplayAndroidClass* klass)
{
    GObjectClass* objectClass = G_OBJECT_CLASS(klass);
    objectClass->dispose = wpeDisplayAndroidDispose;

    WPEDisplayClass* displayClass = WPE_DISPLAY_CLASS(klass);
    displayClass->connect = wpeDisplayAndroidConnect;
    displayClass->create_view = wpeDisplayAndroidCreateView;
    displayClass->get_egl_display = wpeDisplayAndroidGetEGLDisplay;
    displayClass->get_preferred_dma_buf_formats = wpeDisplayAndroidGetPreferredDMABufFormats;
}

static void wpe_display_android_init(WPEDisplayAndroid* display)
{
    Logging::logDebug("WPEDisplayAndroid::init(%p)", display);

    auto* priv = static_cast<WPEDisplayAndroidPrivate*>(
        wpe_display_android_get_instance_private(WPE_DISPLAY_ANDROID(display)));

    // Set available input devices for Android
    auto inputDevices = static_cast<WPEAvailableInputDevices>(
        WPE_AVAILABLE_INPUT_DEVICE_TOUCHSCREEN | WPE_AVAILABLE_INPUT_DEVICE_KEYBOARD);
    wpe_display_set_available_input_devices(WPE_DISPLAY(display), inputDevices);

    // Create the toplevel for this display
    priv->toplevel = wpe_toplevel_android_new(WPE_DISPLAY(display));
}

WPEDisplay* wpe_display_android_new(void) { return WPE_DISPLAY(g_object_new(WPE_TYPE_DISPLAY_ANDROID, nullptr)); }

WPEToplevel* wpe_display_android_get_toplevel(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY_ANDROID(display), nullptr);

    auto* priv = static_cast<WPEDisplayAndroidPrivate*>(
        wpe_display_android_get_instance_private(WPE_DISPLAY_ANDROID(display)));

    return priv->toplevel;
}
