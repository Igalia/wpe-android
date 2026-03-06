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

#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitCookieManager, "org/wpewebkit/wpe/WebKitCookieManager");
DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitCookieManagerAcceptPolicyCallbackHolder,
    "org/wpewebkit/wpe/WebKitCookieManager$AcceptPolicyCallbackHolder");

namespace WebKit {

class JNIWebKitCookieManagerAcceptPolicyCallbackHolderCache final
    : public JNI::TypedClass<JNIWebKitCookieManagerAcceptPolicyCallbackHolder> {
public:
    JNIWebKitCookieManagerAcceptPolicyCallbackHolderCache()
        : JNI::TypedClass<JNIWebKitCookieManagerAcceptPolicyCallbackHolder>(true)
        , m_commitResult(getMethod<void(jint)>("commitResult"))
    {
    }

    void onResult(JNIWebKitCookieManagerAcceptPolicyCallbackHolder callbackHolder, jint policy) const noexcept
    {
        try {
            m_commitResult.invoke(callbackHolder, policy);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WebKitCookieManager accept-policy callback (%s)", ex.what());
        }

        try {
            JNI::getCurrentThreadJNIEnv()->DeleteGlobalRef(callbackHolder);
        } catch (const std::exception& ex) {
            Logging::logError("Failed to release WebKitCookieManager callback reference (%s)", ex.what());
        }
    }

private:
    const JNI::Method<void(jint)> m_commitResult;
};

const JNIWebKitCookieManagerAcceptPolicyCallbackHolderCache& getJNIWebKitCookieManagerAcceptPolicyCallbackHolderCache()
{
    static const JNIWebKitCookieManagerAcceptPolicyCallbackHolderCache s_singleton;
    return s_singleton;
}

class JNIWebKitCookieManagerCache final : public JNI::TypedClass<JNIWebKitCookieManager> {
public:
    JNIWebKitCookieManagerCache()
        : JNI::TypedClass<JNIWebKitCookieManager>(true)
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jlong)>("nativeInit", JNIWebKitCookieManagerCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWebKitCookieManagerCache::nativeDestroy),
            JNI::NativeMethod<void(jlong, JNIWebKitCookieManagerAcceptPolicyCallbackHolder)>(
                "nativeGetAcceptPolicy", JNIWebKitCookieManagerCache::nativeGetAcceptPolicy),
            JNI::NativeMethod<void(jlong, jint)>(
                "nativeSetAcceptPolicy", JNIWebKitCookieManagerCache::nativeSetAcceptPolicy));
    }

private:
    static void onGetAcceptPolicyReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        auto callbackHolder = static_cast<JNIWebKitCookieManagerAcceptPolicyCallbackHolder>(userData);
        auto policy = webkit_cookie_manager_get_accept_policy_finish(WEBKIT_COOKIE_MANAGER(object), result, nullptr);
        if (callbackHolder) {
            getJNIWebKitCookieManagerAcceptPolicyCallbackHolderCache().onResult(
                callbackHolder, static_cast<jint>(policy));
        }
    }

    static jlong nativeInit(JNIEnv*, jobject, jlong sessionPtr)
    {
        auto* session = reinterpret_cast<WebKitNetworkSession*>(sessionPtr);
        return session ? reinterpret_cast<jlong>(g_object_ref(webkit_network_session_get_cookie_manager(session))) : 0;
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr) noexcept
    {
        if (nativePtr)
            g_object_unref(reinterpret_cast<WebKitCookieManager*>(nativePtr));
    }

    static void nativeGetAcceptPolicy(
        JNIEnv* env, jobject, jlong nativePtr, JNIWebKitCookieManagerAcceptPolicyCallbackHolder callbackHolder) noexcept
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        auto* cookieManager = reinterpret_cast<WebKitCookieManager*>(nativePtr);
        if (!cookieManager)
            return;
        webkit_cookie_manager_get_accept_policy(cookieManager, nullptr, onGetAcceptPolicyReady,
            callbackHolder ? env->NewGlobalRef(callbackHolder) : nullptr);
    }

    static void nativeSetAcceptPolicy(JNIEnv*, jobject, jlong nativePtr, jint policy) noexcept
    {
        auto* cookieManager = reinterpret_cast<WebKitCookieManager*>(nativePtr);
        if (cookieManager)
            webkit_cookie_manager_set_accept_policy(cookieManager, static_cast<WebKitCookieAcceptPolicy>(policy));
    }
};

const JNIWebKitCookieManagerCache& getJNIWebKitCookieManagerCache()
{
    static const JNIWebKitCookieManagerCache s_singleton;
    return s_singleton;
}

void configureWebKitCookieManagerJNIMappings()
{
    getJNIWebKitCookieManagerAcceptPolicyCallbackHolderCache();
    getJNIWebKitCookieManagerCache();
}

} // namespace WebKit
