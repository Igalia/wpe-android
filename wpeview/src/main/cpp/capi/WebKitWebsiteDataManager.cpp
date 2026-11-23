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
#include "JNIMappings.h"
#include "Logging.h"

#include <memory>
#include <string>
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
        if (!m_commitResult.invoke(callbackHolder, result))
            Logging::logError("cannot call WebKitWebsiteDataManager callback result");
    }

private:
    const JNI::Method<void(jboolean)> m_commitResult; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
};

static const JNIWebKitWebsiteDataManagerCallbackHolderCache& getJNIWebKitWebsiteDataManagerCallbackHolderCache()
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
                                  "nativeClear", JNIWebKitWebsiteDataManagerCache::nativeClear),
            JNI::NativeMethod<void(jlong)>(
                "nativeClearFavicons", JNIWebKitWebsiteDataManagerCache::nativeClearFavicons),
            JNI::NativeMethod<void(jlong, jint, jstring, JNIWebKitWebsiteDataManagerCallbackHolder)>(
                "nativeRemoveForSite", JNIWebKitWebsiteDataManagerCache::nativeRemoveForSite));
    }

private:
    static void onClearReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        std::unique_ptr<JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>> holder(
            static_cast<JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>*>(userData));
        const gboolean clearResult
            = webkit_website_data_manager_clear_finish(WEBKIT_WEBSITE_DATA_MANAGER(object), result, nullptr);
        if (holder)
            commitResult(*holder, clearResult);
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

    // Clears the icons and the addresses of their pages from the database, and the icons that WebKit keeps in memory.
    static void nativeClearFavicons(JNIEnv*, jobject, jlong nativePtr)
    {
        if (auto* database
            = webkit_website_data_manager_get_favicon_database(JNI::from_jlong<WebKitWebsiteDataManager>(nativePtr)))
            webkit_favicon_database_clear(database);
    }

    // A removal of the data of one site, from the fetch of the data of every site to the end of the removal.
    struct SiteRemoval {
        JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder> holder;
        WebKitWebsiteDataTypes types;
        std::string site;
    };

    static void commitResult(const JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>& holder, gboolean result)
    {
        if (holder)
            getJNIWebKitWebsiteDataManagerCallbackHolderCache().onResult(holder.get(), static_cast<jboolean>(result));
    }

    static void onRemoveForSiteReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        std::unique_ptr<SiteRemoval> removal(static_cast<SiteRemoval*>(userData));
        commitResult(removal->holder,
            webkit_website_data_manager_remove_finish(WEBKIT_WEBSITE_DATA_MANAGER(object), result, nullptr));
    }

    static void onFetchForSiteReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        std::unique_ptr<SiteRemoval> removal(static_cast<SiteRemoval*>(userData));
        auto* manager = WEBKIT_WEBSITE_DATA_MANAGER(object);
        g_autoptr(GError) error = nullptr;
        GList* allData = webkit_website_data_manager_fetch_finish(manager, result, &error);
        if (error) {
            commitResult(removal->holder, FALSE);
            return;
        }
        // WebKit gives the data of each site in one item, named after the site.
        GList* siteData = nullptr;
        for (GList* item = allData; item; item = g_list_next(item)) {
            auto* data = static_cast<WebKitWebsiteData*>(item->data);
            const char* name = webkit_website_data_get_name(data);
            if (name && removal->site == name)
                siteData = g_list_prepend(siteData, data);
        }
        if (siteData) {
            // WebKit reads the list before this returns.
            auto types = removal->types;
            webkit_website_data_manager_remove(
                manager, types, siteData, nullptr, onRemoveForSiteReady, removal.release());
            g_list_free(siteData);
        } else {
            // WebKit would not call back for an empty list.
            commitResult(removal->holder, TRUE);
        }
        g_list_free_full(allData, reinterpret_cast<GDestroyNotify>(webkit_website_data_unref));
    }

    static void nativeRemoveForSite(JNIEnv* env, jobject, jlong nativePtr, jint typesToRemove, jstring site,
        JNIWebKitWebsiteDataManagerCallbackHolder callbackHolder)
    {
        // The JNI::String must outlive its content, whose release uses the Java string.
        auto javaSite = JNI::String(site);
        auto siteContent = javaSite.getContent();
        auto types = static_cast<WebKitWebsiteDataTypes>(typesToRemove);
        auto* removal = new SiteRemoval {JNI::GlobalRef<JNIWebKitWebsiteDataManagerCallbackHolder>(env, callbackHolder),
            types, siteContent ? siteContent.get() : ""};
        // WebKit has no call that fetches or removes the data of one site, so this fetches the data of every site.
        webkit_website_data_manager_fetch(
            JNI::from_jlong<WebKitWebsiteDataManager>(nativePtr), types, nullptr, onFetchForSiteReady, removal);
    }
};

static const JNIWebKitWebsiteDataManagerCache& getJNIWebKitWebsiteDataManagerCache()
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
