/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

#include "WPECookieManager.h"

#include <wpe/webkit.h>

#include "Browser.h"
#include "JNI/JNI.h"
#include "Logging.h"

DECLARE_JNI_CLASS_SIGNATURE(JNIWPECookieManagerCallbackHolder, "com/wpe/wpeview/WPECookieManager$CallbackHolder");
DECLARE_JNI_CLASS_SIGNATURE(JNIWPECookieManager, "com/wpe/wpeview/WPECookieManager");

namespace {

class WPECookieManagerCallbackHolderCache final : public JNI::TypedClass<JNIWPECookieManagerCallbackHolder> {
public:
    WPECookieManagerCallbackHolderCache()
        : JNI::TypedClass<JNIWPECookieManagerCallbackHolder>(true)
        , m_commitResult(getMethod<void(jboolean)>("commitResult"))
    {
    }

    void onResult(JNIWPECookieManagerCallbackHolder callbackHolder, jboolean result) const noexcept
    {
        try {
            m_commitResult.invoke(callbackHolder, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WPECookieManager callback result (%s)", ex.what());
        }

        try {
            JNIEnv* env = JNI::getCurrentThreadJNIEnv();
            env->DeleteGlobalRef(callbackHolder);
        } catch (const std::exception& ex) {
            Logging::logError("Failed to release WPECallback reference (%s)", ex.what());
        }
    }

private:
    const JNI::Method<void(jboolean)> m_commitResult;
};

const WPECookieManagerCallbackHolderCache& getWPECookieManagerCallbackHolderCache()
{
    static const WPECookieManagerCallbackHolderCache s_singleton;
    return s_singleton;
}

class WPECookieManagerCache final : public JNI::TypedClass<JNIWPECookieManager> {
public:
    WPECookieManagerCache();

    static void onRemoveAllCookiesReady(
        WebKitWebsiteDataManager* manager, GAsyncResult* result, JNIWPECookieManagerCallbackHolder callbackHolder)
    {
        gboolean clearResult = webkit_website_data_manager_clear_finish(manager, result, nullptr);
        getWPECookieManagerCallbackHolderCache().onResult(callbackHolder, static_cast<jboolean>(clearResult));
    }

private:
    static void nativeSetCookieAcceptPolicy(JNIEnv* env, jclass cls, jint jpolicy);
    static void nativeRemoveAllCookies(JNIEnv* env, jclass cls, JNIWPECookieManagerCallbackHolder callbackHolder);
};

const WPECookieManagerCache& getWPECookieManagerCache()
{
    static const WPECookieManagerCache s_singleton;
    return s_singleton;
}

WPECookieManagerCache::WPECookieManagerCache()
    : JNI::TypedClass<JNIWPECookieManager>(true)
{
    registerNativeMethods(JNI::StaticNativeMethod<void(jint)>(
                              "nativeSetCookieAcceptPolicy", WPECookieManagerCache::nativeSetCookieAcceptPolicy),
        JNI::StaticNativeMethod<void(JNIWPECookieManagerCallbackHolder)>(
            "nativeRemoveAllCookies", WPECookieManagerCache::nativeRemoveAllCookies));
}

void WPECookieManagerCache::nativeSetCookieAcceptPolicy(JNIEnv* /*env*/, jclass /*cls*/, jint jpolicy)
{
    auto webkitPolicy = static_cast<WebKitCookieAcceptPolicy>(jpolicy);
    webkit_cookie_manager_set_accept_policy(Browser::instance().cookieManager(), webkitPolicy);
}

void WPECookieManagerCache::nativeRemoveAllCookies(
    JNIEnv* env, jclass /*cls*/, JNIWPECookieManagerCallbackHolder callbackHolder)
{
    webkit_website_data_manager_clear(Browser::instance().websiteDataManager(), WEBKIT_WEBSITE_DATA_COOKIES, 0, nullptr,
        reinterpret_cast<GAsyncReadyCallback>(onRemoveAllCookiesReady), env->NewGlobalRef(callbackHolder));
}

} // namespace

void WPECookieManager::configureJNIMappings()
{
    getWPECookieManagerCache();
    getWPECookieManagerCallbackHolderCache();
}
