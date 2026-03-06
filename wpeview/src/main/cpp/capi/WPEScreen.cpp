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

#include "JNI/JNI.h"

#include "Logging.h"

#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEScreen, "org/wpewebkit/wpe/WPEScreen");

namespace WebKit {

class JNIWPEScreenCache final : public JNI::TypedClass<JNIWPEScreen> {
public:
    JNIWPEScreenCache()
        : JNI::TypedClass<JNIWPEScreen>(true)
    {
        registerNativeMethods(JNI::NativeMethod<jfloat(jlong)>("nativeGetScale", JNIWPEScreenCache::nativeGetScale),
            JNI::NativeMethod<void(jlong, jfloat)>("nativeSetScale", JNIWPEScreenCache::nativeSetScale));
    }

private:
    static jfloat nativeGetScale(JNIEnv* env, jobject jniObject, jlong nativePtr) noexcept
    {
        UNUSED_PARAM(env);
        UNUSED_PARAM(jniObject);
        auto* screen = reinterpret_cast<WPEScreen*>(nativePtr);
        return screen ? static_cast<jfloat>(wpe_screen_get_scale(screen)) : 1.0F;
    }

    static void nativeSetScale(JNIEnv* env, jobject jniObject, jlong nativePtr, jfloat scale) noexcept
    {
        UNUSED_PARAM(env);
        UNUSED_PARAM(jniObject);
        auto* screen = reinterpret_cast<WPEScreen*>(nativePtr);
        if (screen)
            wpe_screen_set_scale(screen, scale);
    }
};

const JNIWPEScreenCache& getJNIWPEScreenCache()
{
    static const JNIWPEScreenCache s_singleton;
    return s_singleton;
}

void configureWPEScreenJNIMappings()
{
    getJNIWPEScreenCache();
}

} // namespace WebKit
