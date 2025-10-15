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

static gboolean wpeDisplayAndroidConnect(WPEDisplay* display, GError** error);
static WPEView* wpeDisplayAndroidCreateView(WPEDisplay* display);
static gpointer wpeDisplayAndroidGetEGLDisplay(WPEDisplay* display, GError** error);
static WPEBufferDMABufFormats* wpeDisplayAndroidGetPreferredDMABufFormats(WPEDisplay* display);
static void wpeDisplayAndroidDispose(GObject* object);

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

// Convert DRM fourcc format to AHardwareBuffer format
static uint32_t drmFormatToAHBFormat(uint32_t drmFormat)
{
    switch (drmFormat) {
    case 0x34325241: // DRM_FORMAT_RGBA8888
        return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    case 0x34325258: // DRM_FORMAT_RGBX8888
        return AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
    case 0x34324742: // DRM_FORMAT_RGB888
        return AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM;
    case 0x36314752: // DRM_FORMAT_RGB565
        return AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM;
    default:
        return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM; // default to RGBA8888
    }
}

AHardwareBuffer* wpe_display_android_allocate_buffer(WPEDisplay* display, int width, int height, guint32 format)
{
    g_return_val_if_fail(WPE_IS_DISPLAY_ANDROID(display), nullptr);
    g_return_val_if_fail(width > 0 && height > 0, nullptr);

    Logging::logDebug("wpe_display_android_allocate_buffer(%dx%d, format=0x%08x)", width, height, format);

    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = drmFormatToAHBFormat(format);
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
        | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    desc.stride = 0;
    desc.rfu0 = 0;
    desc.rfu1 = 0;

    AHardwareBuffer* buffer = nullptr;
    int ret = AHardwareBuffer_allocate(&desc, &buffer);
    if (ret != 0 || buffer == nullptr) {
        Logging::logError("Failed to allocate AHardwareBuffer: ret=%d", ret);
        return nullptr;
    }

    Logging::logDebug("Allocated AHardwareBuffer %p", buffer);
    return buffer;
}

WPEToplevel* wpe_display_android_get_toplevel(WPEDisplay* display)
{
    g_return_val_if_fail(WPE_IS_DISPLAY_ANDROID(display), nullptr);

    auto* priv = static_cast<WPEDisplayAndroidPrivate*>(
        wpe_display_android_get_instance_private(WPE_DISPLAY_ANDROID(display)));

    return priv->toplevel;
}
