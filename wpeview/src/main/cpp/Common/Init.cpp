/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
 *   Author: Adrian Perez de Castro <aperez@igalia.com>
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

#include "Init.h"
#include "Logging.h"
#include <gst/gst.h>
#include <wpe/webkit.h>

static void initializeWKVersions()
{
    auto klass = JNI::Class("org/wpewebkit/wpe/WKVersions");
    g_autofree char* webkitVersion = g_strdup_printf(
        "%u.%u.%u", webkit_get_major_version(), webkit_get_minor_version(), webkit_get_micro_version());

    klass.getStaticField<jstring>("WEBKIT").setValue(JNI::String(webkitVersion));
    klass.getStaticField<jint>("WEBKIT_MAJOR").setValue(webkit_get_major_version());
    klass.getStaticField<jint>("WEBKIT_MINOR").setValue(webkit_get_minor_version());
    klass.getStaticField<jint>("WEBKIT_MICRO").setValue(webkit_get_micro_version());

    g_autofree char* gstVersion = gst_version_string();
    unsigned gstMajor, gstMinor, gstMicro, gstNano;
    gst_version(&gstMajor, &gstMinor, &gstMicro, &gstNano);

    klass.getStaticField<jstring>("GSTREAMER").setValue(JNI::String(gstVersion));
    klass.getStaticField<jint>("GSTREAMER_MAJOR").setValue(gstMajor);
    klass.getStaticField<jint>("GSTREAMER_MINOR").setValue(gstMinor);
    klass.getStaticField<jint>("GSTREAMER_MICRO").setValue(gstMicro);
    klass.getStaticField<jint>("GSTREAMER_NANO").setValue(gstNano);

    Logging::logVerbose("WKRuntime::Init: WPE WebKit %s, GStreamer %s", webkitVersion, gstVersion);
}

JNIEnv* Init::initialize(JavaVM* javaVM)
{
    auto* env = JNI::initVM(javaVM);
    initializeWKVersions();
    return env;
}
