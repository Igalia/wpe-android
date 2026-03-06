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

#include <memory>
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
    }

private:
    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jint)> m_commitResult;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
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
        registerNativeMethods(JNI::NativeMethod<jlong(jlong)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<void(jlong, JNIWebKitCookieManagerAcceptPolicyCallbackHolder)>(
                "nativeGetAcceptPolicy", nativeGetAcceptPolicy),
            JNI::NativeMethod<void(jlong, jint)>("nativeSetAcceptPolicy", nativeSetAcceptPolicy));
    }

private:
    static void onGetAcceptPolicyReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        std::unique_ptr<JNI::GlobalRef<JNIWebKitCookieManagerAcceptPolicyCallbackHolder>> holder(
            static_cast<JNI::GlobalRef<JNIWebKitCookieManagerAcceptPolicyCallbackHolder>*>(userData));
        auto policy = webkit_cookie_manager_get_accept_policy_finish(WEBKIT_COOKIE_MANAGER(object), result, nullptr);
        if (*holder) {
            getJNIWebKitCookieManagerAcceptPolicyCallbackHolderCache().onResult(
                holder->get(), static_cast<jint>(policy));
        }
    }

    static jlong nativeInit(JNIEnv*, jobject, jlong sessionPtr) noexcept
    {
        return reinterpret_cast<jlong>(
            g_object_ref(webkit_network_session_get_cookie_manager(JNI::from_jlong<WebKitNetworkSession>(sessionPtr))));
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr) noexcept
    {
        g_object_unref(JNI::from_jlong<WebKitCookieManager>(nativePtr));
    }

    static void nativeGetAcceptPolicy(
        JNIEnv* env, jobject, jlong nativePtr, JNIWebKitCookieManagerAcceptPolicyCallbackHolder callbackHolder) noexcept
    {
        auto* holder = callbackHolder
            ? new JNI::GlobalRef<JNIWebKitCookieManagerAcceptPolicyCallbackHolder>(env, callbackHolder)
            : nullptr;
        webkit_cookie_manager_get_accept_policy(
            JNI::from_jlong<WebKitCookieManager>(nativePtr), nullptr, onGetAcceptPolicyReady, holder);
    }

    static void nativeSetAcceptPolicy(JNIEnv*, jobject, jlong nativePtr, jint policy) noexcept
    {
        webkit_cookie_manager_set_accept_policy(
            JNI::from_jlong<WebKitCookieManager>(nativePtr), static_cast<WebKitCookieAcceptPolicy>(policy));
    }
};

const JNIWebKitCookieManagerCache& getJNIWebKitCookieManagerCache()
{
    static const JNIWebKitCookieManagerCache s_singleton;
    return s_singleton;
}

void configureWebKitCookieManagerAcceptPolicyCallbackHolderJNIMappings()
{
    getJNIWebKitCookieManagerAcceptPolicyCallbackHolderCache();
}

void configureWebKitCookieManagerJNIMappings()
{
    getJNIWebKitCookieManagerCache();
}
} // namespace WebKit
