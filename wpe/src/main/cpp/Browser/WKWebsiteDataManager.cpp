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
    const JNI::Method<void(jboolean)> m_commitResult;
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
        gboolean clearResult = webkit_website_data_manager_clear_finish(manager, result, nullptr);
        getJNIWKWebsiteDataManagerCallbackHolderCache().onResult(callbackHolder, static_cast<jboolean>(clearResult));
    }

private:
    static jlong nativeInit(JNIEnv* env, jobject obj, jboolean automationMode, jstring dataDir, jstring cacheDir);
    static void nativeDestroy(JNIEnv* env, jobject obj, jlong websiteDataManagerPtr) noexcept;
    static void nativeClear(JNIEnv* env, jobject obj, jlong websiteDataManagerPtr, jint typesToClear,
        JNIWKWebsiteDataManagerCallbackHolder callbackHolder) noexcept;
    static jlong nativeCookieManager(JNIEnv* env, jobject obj, jlong websiteDataManagerPtr) noexcept;
};

const JNIWKWebsiteDataManagerCache& getJNIWKWebsiteDataManagerCache()
{
    static const JNIWKWebsiteDataManagerCache s_singleton;
    return s_singleton;
}

JNIWKWebsiteDataManagerCache::JNIWKWebsiteDataManagerCache()
    : JNI::TypedClass<JNIWKWebsiteDataManager>(true)
{
    registerNativeMethods(
        JNI::NativeMethod<jlong(jboolean, jstring, jstring)>("nativeInit", JNIWKWebsiteDataManagerCache::nativeInit),
        JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWKWebsiteDataManagerCache::nativeDestroy),
        JNI::NativeMethod<void(jlong, jint, JNIWKWebsiteDataManagerCallbackHolder)>(
            "nativeClear", JNIWKWebsiteDataManagerCache::nativeClear),
        JNI::NativeMethod<jlong(jlong)>("nativeCookieManager", JNIWKWebsiteDataManagerCache::nativeCookieManager));
}

jlong JNIWKWebsiteDataManagerCache::nativeInit(
    JNIEnv* env, jobject obj, jboolean automationMode, jstring dataDir, jstring cacheDir)
{
    Logging::logDebug("WKWebsiteDataManager::nativeInit(%p, %d, %d) [tid %d]", obj, gettid());
    // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    auto* wkWebsiteDataManager = new WKWebsiteDataManager(env, reinterpret_cast<JNIWKWebsiteDataManager>(obj),
        static_cast<unsigned int>(automationMode) != 0U, JNI::String(dataDir).getContent().get(),
        JNI::String(cacheDir).getContent().get());
    return reinterpret_cast<jlong>(wkWebsiteDataManager);
}

void JNIWKWebsiteDataManagerCache::nativeDestroy(JNIEnv* /*env*/, jobject /*obj*/, jlong websiteDataManagerPtr) noexcept
{
    Logging::logDebug("WKWebsiteDataManager::nativeDestroy() [tid %d]", gettid());
    auto* wkWebsiteDataManager
        = reinterpret_cast<WKWebsiteDataManager*>(websiteDataManagerPtr); // NOLINT(performance-no-int-to-ptr)
    delete wkWebsiteDataManager; // NOLINT(cppcoreguidelines-owning-memory)
}

void JNIWKWebsiteDataManagerCache::nativeClear(JNIEnv* env, jobject /*obj*/, jlong websiteDataManagerPtr,
    jint typesToClear, JNIWKWebsiteDataManagerCallbackHolder callbackHolder) noexcept
{
    Logging::logDebug("WKWebsiteDataManager::nativeClear() [tid %d]", gettid());
    auto* wkWebsiteDataManager
        = reinterpret_cast<WKWebsiteDataManager*>(websiteDataManagerPtr); // NOLINT(performance-no-int-to-ptr)
    auto values = static_cast<WebKitWebsiteDataTypes>(typesToClear); // NOLINT(performance-no-int-to-ptr)
    webkit_website_data_manager_clear(wkWebsiteDataManager->websiteDataManager(), values, 0, nullptr,
        reinterpret_cast<GAsyncReadyCallback>(onRemoveAllCookiesReady), env->NewGlobalRef(callbackHolder));
}

jlong JNIWKWebsiteDataManagerCache::nativeCookieManager(
    JNIEnv* /*env*/, jobject /*obj*/, jlong websiteDataManagerPtr) noexcept
{
    auto* wkWebsiteDataManager
        = reinterpret_cast<WKWebsiteDataManager*>(websiteDataManagerPtr); // NOLINT(performance-no-int-to-ptr)
    return reinterpret_cast<jlong>(
        webkit_website_data_manager_get_cookie_manager(wkWebsiteDataManager->websiteDataManager()));
}

/***********************************************************************************************************************
 * Native WKWebContext class implementation
 **********************************************************************************************************************/

void WKWebsiteDataManager::configureJNIMappings()
{
    getJNIWKWebsiteDataManagerCache();
    getJNIWKWebsiteDataManagerCallbackHolderCache();
}

WKWebsiteDataManager::WKWebsiteDataManager(JNIEnv* env, JNIWKWebsiteDataManager jniWKWebsiteDataManager, bool ephemeral,
    const char* dataDir, const char* cacheDir)
    : m_websiteDataManagerJavaInstance(JNI::createTypedProtectedRef(env, jniWKWebsiteDataManager, true))
{
    if (ephemeral) {
        m_websiteDataManager = {webkit_website_data_manager_new_ephemeral(), [](auto* ptr) { g_object_unref(ptr); }};
    } else {
        m_websiteDataManager = {
            webkit_website_data_manager_new("base-data-directory", dataDir, "base-cache-directory", cacheDir, nullptr),
            [](auto* ptr) { g_object_unref(ptr); }};
    }
}
