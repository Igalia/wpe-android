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

#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitNetworkSession, "org/wpewebkit/wpe/WebKitNetworkSession");

namespace WebKit {

class JNIWebKitNetworkSessionCache final : public JNI::TypedClass<JNIWebKitNetworkSession> {
public:
    JNIWebKitNetworkSessionCache()
        : JNI::TypedClass<JNIWebKitNetworkSession>(true)
    {
        registerNativeMethods(
            JNI::NativeMethod<jlong(jlong, jboolean, jboolean, jstring, jstring)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<jlong(jlong)>("nativeWebsiteDataManager", nativeWebsiteDataManager),
            JNI::NativeMethod<void(jlong, jint)>("nativeSetTLSErrorsPolicy", nativeSetTLSErrorsPolicy));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject, jlong contextPtr, jboolean automationMode, jboolean ephemeral,
        jstring dataDir, jstring cacheDir)
    {
        WebKitNetworkSession* session = nullptr;
        if (automationMode) {
            auto* context = JNI::from_jlong<WebKitWebContext>(contextPtr);
            if (!context) {
                Logging::logError("WebKitNetworkSession: automation mode requires a WebKitWebContext");
                return 0;
            }
            session = g_object_ref(webkit_web_context_get_network_session_for_automation(context));
        } else if (ephemeral)
            session = webkit_network_session_new_ephemeral();
        else {
            session = webkit_network_session_new(
                JNI::String(dataDir).getContent().get(), JNI::String(cacheDir).getContent().get());
        }
        return reinterpret_cast<jlong>(session);
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong sessionPtr)
    {
        g_object_unref(JNI::from_jlong<WebKitNetworkSession>(sessionPtr));
    }

    static jlong nativeWebsiteDataManager(JNIEnv*, jobject, jlong sessionPtr)
    {
        return reinterpret_cast<jlong>(
            webkit_network_session_get_website_data_manager(JNI::from_jlong<WebKitNetworkSession>(sessionPtr)));
    }

    static void nativeSetTLSErrorsPolicy(JNIEnv*, jobject, jlong sessionPtr, jint policy)
    {
        webkit_network_session_set_tls_errors_policy(
            JNI::from_jlong<WebKitNetworkSession>(sessionPtr), static_cast<WebKitTLSErrorsPolicy>(policy));
    }
};

const JNIWebKitNetworkSessionCache& getJNIWebKitNetworkSessionCache()
{
    static const JNIWebKitNetworkSessionCache s_singleton;
    return s_singleton;
}

void configureWebKitNetworkSessionJNIMappings()
{
    getJNIWebKitNetworkSessionCache();
}
} // namespace WebKit
