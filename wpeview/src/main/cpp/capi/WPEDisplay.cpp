/**
 * Copyright (C) 2026 Igalia S.L. <info@igalia.com>
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

#include "CAPI.h"
#include "JNI/JNI.h"
#include "Logging.h"
#include "WPEDisplayAndroid.h"

#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEDisplay, "org/wpewebkit/wpe/WPEDisplay");

namespace WebKit {

class JNIWPEDisplayCache final : public JNI::TypedClass<JNIWPEDisplay> {
public:
    JNIWPEDisplayCache()
        : JNI::TypedClass<JNIWPEDisplay>(true)
    {
        registerNativeMethods(JNI::NativeMethod<jlong()>("nativeInit", JNIWPEDisplayCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWPEDisplayCache::nativeDestroy));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject)
    {
        auto* display = wpe_display_android_new();

        GError* error = nullptr;
        if (!wpe_display_connect(WPE_DISPLAY(display), &error)) {
            Logging::logError("WPEDisplay: failed to connect: %s", error ? error->message : "unknown");
            g_clear_error(&error);
            g_object_unref(display);
            return 0;
        }

        return reinterpret_cast<jlong>(display);
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong displayPtr) noexcept
    {
        auto* display = reinterpret_cast<WPEDisplay*>(displayPtr);
        if (display)
            g_object_unref(display);
    }
};

const JNIWPEDisplayCache& getJNIWPEDisplayCache()
{
    static const JNIWPEDisplayCache s_singleton;
    return s_singleton;
}

void configureWPEDisplayJNIMappings()
{
    getJNIWPEDisplayCache();
}

} // namespace WebKit
