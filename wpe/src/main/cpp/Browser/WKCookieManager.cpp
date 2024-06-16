#include "WKCookieManager.h"

#include "Logging.h"

#include <unistd.h>

/***********************************************************************************************************************
 * JNI mapping with Java WKCookieManager class
 **********************************************************************************************************************/

class JNIWKCookieManagerCache;
const JNIWKCookieManagerCache& getJNIWKCookieManagerCache();

class JNIWKCookieManagerCache final : public JNI::TypedClass<JNIWKCookieManager> {
public:
    JNIWKCookieManagerCache();

private:
    static void nativeSetCookieAcceptPolicy(JNIEnv* env, jclass cls, jlong webkitCookieManagerPtr, jint jpolicy);
};

void JNIWKCookieManagerCache::nativeSetCookieAcceptPolicy(
    JNIEnv* /*env*/, jclass /*cls*/, jlong webkitCookieManagerPtr, jint jpolicy)
{
    auto webkitPolicy = static_cast<WebKitCookieAcceptPolicy>(jpolicy);
    auto* webkitCookieManager
        = reinterpret_cast<WebKitCookieManager*>(webkitCookieManagerPtr); // NOLINT(performance-no-int-to-ptr)
    webkit_cookie_manager_set_accept_policy(webkitCookieManager, webkitPolicy);
}

const JNIWKCookieManagerCache& getJNIWKCookieManagerCache()
{
    static const JNIWKCookieManagerCache s_singleton;
    return s_singleton;
}

JNIWKCookieManagerCache::JNIWKCookieManagerCache()
    : JNI::TypedClass<JNIWKCookieManager>(true)
{
    registerNativeMethods(JNI::StaticNativeMethod<void(jlong, jint)>(
        "nativeSetCookieAcceptPolicy", JNIWKCookieManagerCache::nativeSetCookieAcceptPolicy));
}

/***********************************************************************************************************************
 * Native WKCookieManager class implementation
 **********************************************************************************************************************/

namespace WKCookieManager {

void configureJNIMappings() { getJNIWKCookieManagerCache(); }

} // namespace WKCookieManager
