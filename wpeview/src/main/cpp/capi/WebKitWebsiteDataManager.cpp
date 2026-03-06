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

#include <unistd.h>
#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebsiteDataManager, "org/wpewebkit/wpe/WebKitWebsiteDataManager");
DECLARE_JNI_CLASS_SIGNATURE(
    JNIWebKitWebsiteDataManagerCallbackHolder, "org/wpewebkit/wpe/WebKitWebsiteDataManager$CallbackHolder");

namespace WebKit {

class JNIWebKitWebsiteDataManagerCallbackHolderCache final
    : public JNI::TypedClass<JNIWebKitWebsiteDataManagerCallbackHolder> {
public:
    JNIWebKitWebsiteDataManagerCallbackHolderCache()
        : JNI::TypedClass<JNIWebKitWebsiteDataManagerCallbackHolder>(true)
        , m_commitResult(getMethod<void(jboolean)>("commitResult"))
    {
    }

    void onResult(JNIWebKitWebsiteDataManagerCallbackHolder callbackHolder, jboolean result) const noexcept
    {
        try {
            m_commitResult.invoke(callbackHolder, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WebKitWebsiteDataManager callback result (%s)", ex.what());
        }

        try {
            JNI::getCurrentThreadJNIEnv()->DeleteGlobalRef(callbackHolder);
        } catch (const std::exception& ex) {
            Logging::logError("Failed to release WebKitWebsiteDataManager callback reference (%s)", ex.what());
        }
    }

private:
    const JNI::Method<void(jboolean)> m_commitResult;
};

const JNIWebKitWebsiteDataManagerCallbackHolderCache& getJNIWebKitWebsiteDataManagerCallbackHolderCache()
{
    static const JNIWebKitWebsiteDataManagerCallbackHolderCache s_singleton;
    return s_singleton;
}

class JNIWebKitWebsiteDataManagerCache final : public JNI::TypedClass<JNIWebKitWebsiteDataManager> {
public:
    JNIWebKitWebsiteDataManagerCache()
        : JNI::TypedClass<JNIWebKitWebsiteDataManager>(true)
    {
        registerNativeMethods(JNI::NativeMethod<void(jlong, jint, JNIWebKitWebsiteDataManagerCallbackHolder)>(
            "nativeClear", JNIWebKitWebsiteDataManagerCache::nativeClear));
    }

private:
    static void onClearReady(WebKitWebsiteDataManager* manager, GAsyncResult* result,
        JNIWebKitWebsiteDataManagerCallbackHolder callbackHolder)
    {
        const gboolean clearResult = webkit_website_data_manager_clear_finish(manager, result, nullptr);
        if (callbackHolder) {
            getJNIWebKitWebsiteDataManagerCallbackHolderCache().onResult(
                callbackHolder, static_cast<jboolean>(clearResult));
        }
    }

    static void nativeClear(JNIEnv* env, jobject jniObject, jlong nativePtr, jint typesToClear,
        JNIWebKitWebsiteDataManagerCallbackHolder callbackHolder) noexcept
    {
        UNUSED_PARAM(jniObject);
        Logging::logDebug("WebKitWebsiteDataManager::nativeClear() [tid %d]", gettid());
        auto* manager = reinterpret_cast<WebKitWebsiteDataManager*>(nativePtr);
        if (!manager)
            return;

        auto values = static_cast<WebKitWebsiteDataTypes>(typesToClear);
        webkit_website_data_manager_clear(manager, values, 0, nullptr,
            reinterpret_cast<GAsyncReadyCallback>(onClearReady),
            callbackHolder ? env->NewGlobalRef(callbackHolder) : nullptr);
    }
};

const JNIWebKitWebsiteDataManagerCache& getJNIWebKitWebsiteDataManagerCache()
{
    static const JNIWebKitWebsiteDataManagerCache s_singleton;
    return s_singleton;
}

void configureWebKitWebsiteDataManagerJNIMappings()
{
    getJNIWebKitWebsiteDataManagerCache();
    getJNIWebKitWebsiteDataManagerCallbackHolderCache();
}

} // namespace WebKit
