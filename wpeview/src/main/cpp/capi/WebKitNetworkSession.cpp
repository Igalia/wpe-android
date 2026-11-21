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

#include <algorithm>
#include <libsoup/soup.h>
#include <memory>
#include <string>
#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitNetworkSession, "org/wpewebkit/wpe/WebKitNetworkSession");
DECLARE_JNI_CLASS_SIGNATURE(
    JNIWebKitNetworkSessionITPSummaryCallbackHolder, "org/wpewebkit/wpe/WebKitNetworkSession$ITPSummaryCallbackHolder");

namespace WebKit {

class JNIWebKitNetworkSessionITPSummaryCallbackHolderCache final
    : public JNI::TypedClass<JNIWebKitNetworkSessionITPSummaryCallbackHolder> {
public:
    JNIWebKitNetworkSessionITPSummaryCallbackHolderCache()
        : JNI::TypedClass<JNIWebKitNetworkSessionITPSummaryCallbackHolder>(true)
        , m_addThirdParty(getMethod<void(jstring)>("addThirdParty"))
        , m_addFirstParty(getMethod<void(jstring, jboolean, jlong)>("addFirstParty"))
        , m_commitResult(getMethod<void()>("commitResult"))
    {
    }

    // Each third party, then each first party it was seen on, in WebKit's order. The result is given in any case, with
    // what could be passed, so that the app's callback is called once.
    void onResult(JNIWebKitNetworkSessionITPSummaryCallbackHolder callbackHolder, GList* thirdParties) const
    {
        passThirdParties(callbackHolder, thirdParties);
        if (!m_commitResult.invoke(callbackHolder))
            Logging::logError("cannot call WebKitNetworkSession ITP summary callback");
    }

private:
    void passThirdParties(JNIWebKitNetworkSessionITPSummaryCallbackHolder callbackHolder, GList* thirdParties) const
    {
        for (GList* item = thirdParties; item; item = item->next) {
            auto* thirdParty = static_cast<WebKitITPThirdParty*>(item->data);
            auto jThirdPartyDomain = JNI::String(webkit_itp_third_party_get_domain(thirdParty));
            if (!m_addThirdParty.invoke(callbackHolder, static_cast<jstring>(jThirdPartyDomain))) {
                Logging::logError("cannot pass a WebKitNetworkSession ITP third party");
                return;
            }
            for (GList* firstItem = webkit_itp_third_party_get_first_parties(thirdParty); firstItem;
                 firstItem = firstItem->next) {
                auto* firstParty = static_cast<WebKitITPFirstParty*>(firstItem->data);
                GDateTime* lastUpdate = webkit_itp_first_party_get_last_update_time(firstParty);
                auto jFirstPartyDomain = JNI::String(webkit_itp_first_party_get_domain(firstParty));
                if (!m_addFirstParty.invoke(callbackHolder, static_cast<jstring>(jFirstPartyDomain),
                        static_cast<jboolean>(webkit_itp_first_party_get_website_data_access_allowed(firstParty)),
                        static_cast<jlong>(lastUpdate ? g_date_time_to_unix_usec(lastUpdate) / 1000 : 0))) {
                    Logging::logError("cannot pass a WebKitNetworkSession ITP first party");
                    return;
                }
            }
        }
    }

    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jstring)> m_addThirdParty;
    const JNI::Method<void(jstring, jboolean, jlong)> m_addFirstParty;
    const JNI::Method<void()> m_commitResult;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

static const JNIWebKitNetworkSessionITPSummaryCallbackHolderCache&
getJNIWebKitNetworkSessionITPSummaryCallbackHolderCache()
{
    static const JNIWebKitNetworkSessionITPSummaryCallbackHolderCache s_singleton;
    return s_singleton;
}

// WebKit's registrableDomainFromHost() (RegistrableDomain.h) with PublicSuffixStore::topPrivatelyControlledDomain()
// and its libsoup part (PublicSuffixStoreSoup.cpp), which give the site that tracking prevention files a host under.
// The host is lowercase, as WebKit's URL parser leaves it. An empty result means no site.
static std::string registrableDomain(const std::string& host)
{
    if (host.empty())
        return {};
    for (unsigned char character : host) {
        if (character > 127)
            return host;
    }
    // An IPv6 address keeps its brackets in a URL's host, which then is no address for GLib nor a known name for
    // libsoup, and WebKit keeps the host.
    if (host == "localhost" || host.front() == '[' || g_hostname_is_ip_address(host.c_str()))
        return host;

    // Cookie domains may start with dots, which WebKit skips.
    std::string domain = host.substr(std::min(host.find_first_not_of('.'), host.size()));
    if (domain.empty())
        return host;

    GError* error = nullptr;
    if (const char* baseDomain = soup_tld_get_base_domain(domain.c_str(), &error))
        return baseDomain;
    std::string result;
    if (g_error_matches(error, SOUP_TLD_ERROR, SOUP_TLD_ERROR_NO_BASE_DOMAIN)) {
        // A domain with no known public suffix: its last two labels, or itself if it has only two.
        size_t lastDot = domain.rfind('.');
        if (lastDot != std::string::npos) {
            size_t previousDot = lastDot ? domain.rfind('.', lastDot - 1) : std::string::npos;
            result = previousDot != std::string::npos ? domain.substr(previousDot + 1) : domain;
        }
    } else if (g_error_matches(error, SOUP_TLD_ERROR, SOUP_TLD_ERROR_IS_IP_ADDRESS))
        result = domain;
    g_clear_error(&error);
    return result.empty() ? host : result;
}

class JNIWebKitNetworkSessionCache final : public JNI::TypedClass<JNIWebKitNetworkSession> {
public:
    JNIWebKitNetworkSessionCache()
        : JNI::TypedClass<JNIWebKitNetworkSession>(true)
    {
        registerNativeMethods(
            JNI::NativeMethod<jlong(jlong, jboolean, jboolean, jstring, jstring)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<jlong(jlong)>("nativeWebsiteDataManager", nativeWebsiteDataManager),
            JNI::NativeMethod<void(jlong, jint)>("nativeSetTLSErrorsPolicy", nativeSetTLSErrorsPolicy),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetITPEnabled", nativeSetITPEnabled),
            JNI::NativeMethod<jboolean(jlong)>("nativeIsITPEnabled", nativeIsITPEnabled),
            JNI::NativeMethod<jboolean(jlong)>("nativeIsEphemeral", nativeIsEphemeral),
            JNI::NativeMethod<void(jlong, JNIWebKitNetworkSessionITPSummaryCallbackHolder)>(
                "nativeGetITPSummary", nativeGetITPSummary),
            JNI::StaticNativeMethod<jstring(jstring)>("nativeGetRegistrableDomain", nativeGetRegistrableDomain));
    }

private:
    static jlong nativeInit(JNIEnv*, jobject, jlong contextPtr, jboolean automationMode, jboolean ephemeral,
        jstring dataDir, jstring cacheDir)
    {
        WebKitNetworkSession* session = nullptr;
        if (automationMode) {
            auto* context = JNI::from_jlong<WebKitWebContext>(contextPtr);
            if (!context) {
                Logging::logError("WebKitNetworkSession: automation mode requires a WebKitWebContext");
                return 0;
            }
            session = g_object_ref(webkit_web_context_get_network_session_for_automation(context));
        } else if (ephemeral)
            session = webkit_network_session_new_ephemeral();
        else {
            // The JNI::String must outlive its content, whose release uses the Java string.
            auto dataDirectory = JNI::String(dataDir);
            auto dataDirectoryContent = dataDirectory.getContent();
            session = webkit_network_session_new(dataDirectoryContent.get(), JNI::String(cacheDir).getContent().get());
            // WebKit keeps cookies in memory only, unless it has a file for them. Ephemeral sessions have none.
            g_autofree char* cookieFile = g_build_filename(dataDirectoryContent.get(), "cookies.sqlite", nullptr);
            webkit_cookie_manager_set_persistent_storage(webkit_network_session_get_cookie_manager(session), cookieFile,
                WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
        }
        // The icons of the pages, which WebKit keeps in its icon database, in the folder icondatabase of the cache
        // folder, or in memory only for an ephemeral session. Those of a WebDriver session stay off, as WebKit has
        // them.
        // FIXME: The sessions that keep their data all open the same file, each with its own copy in memory of the page
        // addresses in it, which WebKit reads when the session starts. Once one of them clears the file, the others
        // keep addresses whose icons are gone, and never load those icons again; clearing it from several sessions at
        // once made WebKit abort on a phone. WebKit needs one icon database for each file, or to read it again.
        if (!automationMode)
            webkit_website_data_manager_set_favicons_enabled(
                webkit_network_session_get_website_data_manager(session), TRUE);
        return reinterpret_cast<jlong>(session);
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong sessionPtr)
    {
        g_object_unref(JNI::from_jlong<WebKitNetworkSession>(sessionPtr));
    }

    static jlong nativeWebsiteDataManager(JNIEnv*, jobject, jlong sessionPtr)
    {
        return reinterpret_cast<jlong>(
            webkit_network_session_get_website_data_manager(JNI::from_jlong<WebKitNetworkSession>(sessionPtr)));
    }

    static void nativeSetTLSErrorsPolicy(JNIEnv*, jobject, jlong sessionPtr, jint policy)
    {
        webkit_network_session_set_tls_errors_policy(
            JNI::from_jlong<WebKitNetworkSession>(sessionPtr), static_cast<WebKitTLSErrorsPolicy>(policy));
    }

    static void nativeSetITPEnabled(JNIEnv*, jobject, jlong sessionPtr, jboolean enabled)
    {
        webkit_network_session_set_itp_enabled(JNI::from_jlong<WebKitNetworkSession>(sessionPtr), enabled);
    }

    static jboolean nativeIsITPEnabled(JNIEnv*, jobject, jlong sessionPtr)
    {
        return webkit_network_session_get_itp_enabled(JNI::from_jlong<WebKitNetworkSession>(sessionPtr));
    }

    static jboolean nativeIsEphemeral(JNIEnv*, jobject, jlong sessionPtr)
    {
        return webkit_network_session_is_ephemeral(JNI::from_jlong<WebKitNetworkSession>(sessionPtr));
    }

    static void onITPSummaryReady(GObject* object, GAsyncResult* result, gpointer userData)
    {
        std::unique_ptr<JNI::GlobalRef<JNIWebKitNetworkSessionITPSummaryCallbackHolder>> holder(
            static_cast<JNI::GlobalRef<JNIWebKitNetworkSessionITPSummaryCallbackHolder>*>(userData));
        GList* thirdParties
            = webkit_network_session_get_itp_summary_finish(WEBKIT_NETWORK_SESSION(object), result, nullptr);
        if (*holder)
            getJNIWebKitNetworkSessionITPSummaryCallbackHolderCache().onResult(holder->get(), thirdParties);
        g_list_free_full(thirdParties, reinterpret_cast<GDestroyNotify>(webkit_itp_third_party_unref));
    }

    static void nativeGetITPSummary(
        JNIEnv* env, jobject, jlong sessionPtr, JNIWebKitNetworkSessionITPSummaryCallbackHolder callbackHolder)
    {
        webkit_network_session_get_itp_summary(JNI::from_jlong<WebKitNetworkSession>(sessionPtr), nullptr,
            onITPSummaryReady,
            new JNI::GlobalRef<JNIWebKitNetworkSessionITPSummaryCallbackHolder>(env, callbackHolder));
    }

    static jstring nativeGetRegistrableDomain(JNIEnv* env, jclass, jstring host)
    {
        // The JNI::String must outlive its content, whose release uses the Java string.
        auto javaHost = JNI::String(host);
        auto hostContent = javaHost.getContent();
        // Not a JNI::String: it deletes its reference when it goes away, before Java gets the result.
        return env->NewStringUTF(registrableDomain(hostContent ? hostContent.get() : "").c_str());
    }
};

static const JNIWebKitNetworkSessionCache& getJNIWebKitNetworkSessionCache()
{
    static const JNIWebKitNetworkSessionCache s_singleton;
    return s_singleton;
}

void configureWebKitNetworkSessionJNIMappings()
{
    getJNIWebKitNetworkSessionCache();
}

void configureWebKitNetworkSessionITPSummaryCallbackHolderJNIMappings()
{
    getJNIWebKitNetworkSessionITPSummaryCallbackHolderCache();
}
} // namespace WebKit
