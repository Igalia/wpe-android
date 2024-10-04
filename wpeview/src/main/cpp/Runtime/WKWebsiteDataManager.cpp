#include "WKWebsiteDataManager.h"

#include "Logging.h"

#include <unistd.h>

/***********************************************************************************************************************
 * JNI mapping with Java WKWebsiteDataManager.CallbackHolder class
 **********************************************************************************************************************/

class JNIWKWebsiteDataManagerCallbackHolderCache final : public JNI::TypedClass<JNIWKWebsiteDataManagerCallbackHolder> {
public:
    JNIWKWebsiteDataManagerCallbackHolderCache()
        : JNI::TypedClass<JNIWKWebsiteDataManagerCallbackHolder>(true)
        , m_commitResult(getMethod<void(jboolean)>("commitResult"))
    {
    }

    void onResult(JNIWKWebsiteDataManagerCallbackHolder callbackHolder, jboolean result) const noexcept
    {
        try {
            m_commitResult.invoke(callbackHolder, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WKWebsiteDataManager callback result (%s)", ex.what());
        }

        try {
            JNIEnv* env = JNI::getCurrentThreadJNIEnv();
            env->DeleteGlobalRef(callbackHolder);
        } catch (const std::exception& ex) {
            Logging::logError("Failed to release WKWebsiteDataManager.CallbackHolder reference (%s)", ex.what());
        }
    }

private:
    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jboolean)> m_commitResult;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

const JNIWKWebsiteDataManagerCallbackHolderCache& getJNIWKWebsiteDataManagerCallbackHolderCache()
{
    static const JNIWKWebsiteDataManagerCallbackHolderCache s_singleton;
    return s_singleton;
}

/***********************************************************************************************************************
 * JNI mapping with Java WKWebsiteDataManager class
 **********************************************************************************************************************/

class JNIWKWebsiteDataManagerCache;
const JNIWKWebsiteDataManagerCache& getJNIWKWebsiteDataManagerCache();

class JNIWKWebsiteDataManagerCache final : public JNI::TypedClass<JNIWKWebsiteDataManager> {
public:
    JNIWKWebsiteDataManagerCache();

    static void onRemoveAllCookiesReady(
        WebKitWebsiteDataManager* manager, GAsyncResult* result, JNIWKWebsiteDataManagerCallbackHolder callbackHolder)
    {
        const gboolean clearResult = webkit_website_data_manager_clear_finish(manager, result, nullptr);
        getJNIWKWebsiteDataManagerCallbackHolderCache().onResult(callbackHolder, static_cast<jboolean>(clearResult));
    }

private:
    static void nativeClear(JNIEnv* env, jobject obj, jlong websiteDataManagerPtr, jint typesToClear,
        JNIWKWebsiteDataManagerCallbackHolder callbackHolder) noexcept;
};

const JNIWKWebsiteDataManagerCache& getJNIWKWebsiteDataManagerCache()
{
    static const JNIWKWebsiteDataManagerCache s_singleton;
    return s_singleton;
}

JNIWKWebsiteDataManagerCache::JNIWKWebsiteDataManagerCache()
    : JNI::TypedClass<JNIWKWebsiteDataManager>(true)
{
    registerNativeMethods(JNI::NativeMethod<void(jlong, jint, JNIWKWebsiteDataManagerCallbackHolder)>(
        "nativeClear", JNIWKWebsiteDataManagerCache::nativeClear));
}

void JNIWKWebsiteDataManagerCache::nativeClear(JNIEnv* env, jobject /*obj*/, jlong webkitWebsiteDataManagerPtr,
    jint typesToClear, JNIWKWebsiteDataManagerCallbackHolder callbackHolder) noexcept
{
    Logging::logDebug("WKWebsiteDataManager::nativeClear() [tid %d]", gettid());
    auto* webkitWebsiteDataManager
        = reinterpret_cast<WebKitWebsiteDataManager*>(webkitWebsiteDataManagerPtr); // NOLINT(performance-no-int-to-ptr)
    auto values = static_cast<WebKitWebsiteDataTypes>(typesToClear); // NOLINT(performance-no-int-to-ptr)
    webkit_website_data_manager_clear(webkitWebsiteDataManager, values, 0, nullptr,
        reinterpret_cast<GAsyncReadyCallback>(onRemoveAllCookiesReady), env->NewGlobalRef(callbackHolder));
}

/***********************************************************************************************************************
 * Native KWebsiteDataManager class implementation
 **********************************************************************************************************************/

namespace WKWebsiteDataManager {

void configureJNIMappings()
{
    getJNIWKWebsiteDataManagerCache();
    getJNIWKWebsiteDataManagerCallbackHolderCache();
}

} // namespace WKWebsiteDataManager
