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

    void onResult(JNIWebKitWebsiteDataManagerCallbackHolder callbackHolder, jboolean result) const
    {
        try {
            m_commitResult.invoke(callbackHolder, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WebKitWebsiteDataManager callback result (%s)", ex.what());
        }
    }

private:
    const JNI::Method<void(jboolean)> m_commitResult; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
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
    static void onClearReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        std::unique_ptr<JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>> holder(
            static_cast<JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>*>(userData));
        const gboolean clearResult
            = webkit_website_data_manager_clear_finish(WEBKIT_WEBSITE_DATA_MANAGER(object), result, nullptr);
        if (holder && *holder) {
            getJNIWebKitWebsiteDataManagerCallbackHolderCache().onResult(
                holder->get(), static_cast<jboolean>(clearResult));
        }
    }

    static void nativeClear(JNIEnv* env, jobject, jlong nativePtr, jint typesToClear,
        JNIWebKitWebsiteDataManagerCallbackHolder callbackHolder)
    {
        auto* holder = callbackHolder
            ? new JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>(env, callbackHolder)
            : nullptr;
        webkit_website_data_manager_clear(JNI::from_jlong<WebKitWebsiteDataManager>(nativePtr),
            static_cast<WebKitWebsiteDataTypes>(typesToClear), 0, nullptr, onClearReady, holder);
    }
};

const JNIWebKitWebsiteDataManagerCache& getJNIWebKitWebsiteDataManagerCache()
{
    static const JNIWebKitWebsiteDataManagerCache s_singleton;
    return s_singleton;
}

void configureWebKitWebsiteDataManagerCallbackHolderJNIMappings()
{
    getJNIWebKitWebsiteDataManagerCallbackHolderCache();
}

void configureWebKitWebsiteDataManagerJNIMappings()
{
    getJNIWebKitWebsiteDataManagerCache();
}
} // namespace WebKit
