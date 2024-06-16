#include "WKNetworkSession.h"

#include "Logging.h"
#include "WKWebContext.h"

#include <unistd.h>

/***********************************************************************************************************************
 * JNI mapping with Java WKNetworkSession class
 **********************************************************************************************************************/

class JNIWKNetworkSessionCache;
const JNIWKNetworkSessionCache& getJNIWKNetworkSessionCache();

class JNIWKNetworkSessionCache final : public JNI::TypedClass<JNIWKNetworkSession> {
public:
    JNIWKNetworkSessionCache();

private:
    static jlong nativeInit(
        JNIEnv* env, jobject obj, jlong wkWebContextPtr, jboolean automationMode, jstring dataDir, jstring cacheDir);
    static void nativeDestroy(JNIEnv* env, jobject obj, jlong networkSessionPtr) noexcept;
    static jlong nativeCookieManager(JNIEnv* env, jobject obj, jlong networkSessionPtr) noexcept;
    static jlong nativeWebsiteDataManager(JNIEnv* env, jobject obj, jlong networkSessionPtr) noexcept;
};

const JNIWKNetworkSessionCache& getJNIWKNetworkSessionCache()
{
    static const JNIWKNetworkSessionCache s_singleton;
    return s_singleton;
}

JNIWKNetworkSessionCache::JNIWKNetworkSessionCache()
    : JNI::TypedClass<JNIWKNetworkSession>(true)
{
    registerNativeMethods(
        JNI::NativeMethod<jlong(jlong, jboolean, jstring, jstring)>("nativeInit", JNIWKNetworkSessionCache::nativeInit),
        JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWKNetworkSessionCache::nativeDestroy),
        JNI::NativeMethod<jlong(jlong)>("nativeCookieManager", JNIWKNetworkSessionCache::nativeCookieManager),
        JNI::NativeMethod<jlong(jlong)>(
            "nativeWebsiteDataManager", JNIWKNetworkSessionCache::nativeWebsiteDataManager));
}

jlong JNIWKNetworkSessionCache::nativeInit(
    JNIEnv* env, jobject obj, jlong wkWebContextPtr, jboolean automationMode, jstring dataDir, jstring cacheDir)
{
    Logging::logDebug("JNIWKNetworkSessionCache::nativeInit(%p, %d, %d) [tid %d]", obj, gettid());
    auto* wkWebContext = reinterpret_cast<WKWebContext*>(wkWebContextPtr); // NOLINT(performance-no-int-to-ptr)
    // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    auto* wkNetworkSession = new WKNetworkSession(env, reinterpret_cast<JNIWKNetworkSession>(obj), wkWebContext,
        static_cast<unsigned int>(automationMode) != 0U, JNI::String(dataDir).getContent().get(),
        JNI::String(cacheDir).getContent().get());
    return reinterpret_cast<jlong>(wkNetworkSession);
}

void JNIWKNetworkSessionCache::nativeDestroy(JNIEnv* /*env*/, jobject /*obj*/, jlong networkSessionPtr) noexcept
{
    Logging::logDebug("JNIWKNetworkSessionCache::nativeDestroy() [tid %d]", gettid());
    auto* wkNetworkSession
        = reinterpret_cast<WKNetworkSession*>(networkSessionPtr); // NOLINT(performance-no-int-to-ptr)
    delete wkNetworkSession; // NOLINT(cppcoreguidelines-owning-memory)
}

jlong JNIWKNetworkSessionCache::nativeCookieManager(JNIEnv* /*env*/, jobject /*obj*/, jlong networkSessionPtr) noexcept
{
    auto* wkNetworkSession
        = reinterpret_cast<WKNetworkSession*>(networkSessionPtr); // NOLINT(performance-no-int-to-ptr)
    return reinterpret_cast<jlong>(webkit_network_session_get_cookie_manager(wkNetworkSession->networkSession()));
}

jlong JNIWKNetworkSessionCache::nativeWebsiteDataManager(
    JNIEnv* /*env*/, jobject /*obj*/, jlong networkSessionPtr) noexcept
{
    auto* wkNetworkSession
        = reinterpret_cast<WKNetworkSession*>(networkSessionPtr); // NOLINT(performance-no-int-to-ptr)
    return reinterpret_cast<jlong>(webkit_network_session_get_website_data_manager(wkNetworkSession->networkSession()));
}

/***********************************************************************************************************************
 * Native WKWebsiteDataManager class implementation
 **********************************************************************************************************************/

void WKNetworkSession::configureJNIMappings() { getJNIWKNetworkSessionCache(); }

WKNetworkSession::WKNetworkSession(JNIEnv* env, JNIWKNetworkSession jniWKNetworkSession, WKWebContext* wkWebContext,
    bool automationMode, const char* dataDir, const char* cacheDir)
    : m_networkSessionJavaInstance(JNI::createTypedProtectedRef(env, jniWKNetworkSession, true))
{
    if (automationMode) {
        m_networkSession
            = {g_object_ref(webkit_web_context_get_network_session_for_automation(wkWebContext->webContext())),
                [](auto* ptr) { g_object_unref(ptr); }};
    } else {
        m_networkSession = {webkit_network_session_new(dataDir, cacheDir), [](auto* ptr) { g_object_unref(ptr); }};
    }
}
