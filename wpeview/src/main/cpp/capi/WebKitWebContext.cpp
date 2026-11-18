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

#include <cmath>
#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebContext, "org/wpewebkit/wpe/WebKitWebContext");

namespace WebKit {

struct WebKitWebContextBridge {
    explicit WebKitWebContextBridge(WebKitWebContext* context)
        : m_context(context)
    {
        g_weak_ref_init(&m_automationSession, nullptr);
    }

    ~WebKitWebContextBridge()
    {
        g_signal_handlers_disconnect_by_data(webkit_web_context_get_geolocation_manager(m_context), this);
        if (m_automationSignalId)
            g_signal_handler_disconnect(m_context, m_automationSignalId);
        disconnectAutomationSession();
        g_weak_ref_clear(&m_automationSession);
        g_object_unref(m_context);
    }

    void disconnectAutomationSession()
    {
        if (GObject* automationSession = static_cast<GObject*>(g_weak_ref_get(&m_automationSession))) {
            if (m_createWebViewSignalId)
                g_signal_handler_disconnect(automationSession, m_createWebViewSignalId);
            if (m_willCloseSignalId)
                g_signal_handler_disconnect(automationSession, m_willCloseSignalId);
            g_object_unref(automationSession);
        }
        m_createWebViewSignalId = 0;
        m_willCloseSignalId = 0;
    }

    WebKitWebContext* m_context;
    JNI::GlobalRef<JNIWebKitWebContext> m_javaRef;
    GWeakRef m_automationSession;
    gulong m_automationSignalId {0};
    gulong m_createWebViewSignalId {0};
    gulong m_willCloseSignalId {0};
};

class JNIWebKitWebContextCache final : public JNI::TypedClass<JNIWebKitWebContext> {
public:
    JNIWebKitWebContextCache()
        : JNI::TypedClass<JNIWebKitWebContext>(true)
        , m_invokeCreateWebKitWebViewForAutomation(getMethod<jlong()>("createWebKitWebViewForAutomation"))
        , m_onAutomationSessionWillClose(getMethod<void()>("onAutomationSessionWillClose"))
        , m_onGeolocationStart(getMethod<void(jboolean)>("onGeolocationStart"))
        , m_onGeolocationStop(getMethod<void()>("onGeolocationStop"))
        , m_onGeolocationHighAccuracyChanged(getMethod<void(jboolean)>("onGeolocationHighAccuracyChanged"))
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jboolean)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<jboolean(jlong)>("nativeIsAutomationMode", nativeIsAutomationMode),
            JNI::NativeMethod<jlong(jlong)>("nativeGetWebKitWebContextPtr", nativeGetWebKitWebContextPtr),
            JNI::NativeMethod<void(jlong, jstringArray)>("nativeSetPreferredLanguages", nativeSetPreferredLanguages),
            JNI::NativeMethod<void(jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jlong)>(
                "nativeUpdateGeolocationPosition", nativeUpdateGeolocationPosition),
            JNI::NativeMethod<void(jlong, jstring)>("nativeGeolocationFailed", nativeGeolocationFailed),
            JNI::NativeMethod<void(jlong, jstringArray, jstringArray)>(
                "nativeSetNotificationPermissions", nativeSetNotificationPermissions));
    }

    WebKitWebView* invokeCreateWebKitWebViewForAutomation(JNIWebKitWebContext javaContext) const;

private:
    static void onAutomationStarted(
        WebKitWebContext*, WebKitAutomationSession* session, WebKitWebContextBridge* bridge);
    static WebKitWebView* onCreateWebViewForAutomation(WebKitWebContextBridge* bridge);
    static void onAutomationSessionWillClose(WebKitWebContextBridge* bridge);
    static gboolean onGeolocationStart(WebKitGeolocationManager* manager, WebKitWebContextBridge* bridge);
    static void onGeolocationStop(WebKitGeolocationManager*, WebKitWebContextBridge* bridge);
    static void onGeolocationHighAccuracyChanged(GObject* manager, GParamSpec*, WebKitWebContextBridge* bridge);

    static jlong nativeInit(JNIEnv* env, jobject jniContext, jboolean automationMode);
    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr);
    static jboolean nativeIsAutomationMode(JNIEnv*, jobject, jlong nativePtr);
    static jlong nativeGetWebKitWebContextPtr(JNIEnv*, jobject, jlong nativePtr);
    static void nativeSetPreferredLanguages(JNIEnv*, jobject, jlong nativePtr, jstringArray languagesArray);
    static void nativeUpdateGeolocationPosition(JNIEnv*, jobject, jlong nativePtr, jdouble latitude, jdouble longitude,
        jdouble accuracy, jdouble altitude, jdouble altitudeAccuracy, jdouble heading, jdouble speed,
        jlong timeSeconds);
    static void nativeGeolocationFailed(JNIEnv*, jobject, jlong nativePtr, jstring message);
    static void nativeSetNotificationPermissions(
        JNIEnv*, jobject, jlong nativePtr, jstringArray allowedArray, jstringArray deniedArray);

    const JNI::Method<jlong()>
        m_invokeCreateWebKitWebViewForAutomation; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void()>
        m_onAutomationSessionWillClose; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jboolean)> m_onGeolocationStart; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void()> m_onGeolocationStop; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jboolean)>
        m_onGeolocationHighAccuracyChanged; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
};

static const JNIWebKitWebContextCache& getJNIWebKitWebContextCache()
{
    static const JNIWebKitWebContextCache s_singleton;
    return s_singleton;
}

WebKitWebView* JNIWebKitWebContextCache::onCreateWebViewForAutomation(WebKitWebContextBridge* bridge)
{
    return getJNIWebKitWebContextCache().invokeCreateWebKitWebViewForAutomation(bridge->m_javaRef.get());
}

void JNIWebKitWebContextCache::onAutomationSessionWillClose(WebKitWebContextBridge* bridge)
{
    if (!getJNIWebKitWebContextCache().m_onAutomationSessionWillClose.invoke(bridge->m_javaRef.get()))
        Logging::logError("cannot deliver WebKitWebContext onAutomationSessionWillClose callback");
}

void JNIWebKitWebContextCache::onAutomationStarted(
    WebKitWebContext*, WebKitAutomationSession* session, WebKitWebContextBridge* bridge)
{
    g_autoptr(WebKitApplicationInfo) info = webkit_application_info_new();
    webkit_application_info_set_name(info, "WPEWebView");
    webkit_application_info_set_version(info, WEBKIT_MAJOR_VERSION, WEBKIT_MINOR_VERSION, WEBKIT_MICRO_VERSION);
    webkit_automation_session_set_application_info(session, info);

    bridge->disconnectAutomationSession();

    g_weak_ref_set(&bridge->m_automationSession, G_OBJECT(session));
    bridge->m_createWebViewSignalId
        = g_signal_connect_swapped(session, "create-web-view", G_CALLBACK(onCreateWebViewForAutomation), bridge);
    bridge->m_willCloseSignalId
        = g_signal_connect_swapped(session, "will-close", G_CALLBACK(onAutomationSessionWillClose), bridge);
}

// WebKit wants the location of the device for the pages of the context. Java starts its source, which gives the
// positions later. Unhandled, WebKit would look for GeoClue, a location service of desktop systems.
gboolean JNIWebKitWebContextCache::onGeolocationStart(WebKitGeolocationManager* manager, WebKitWebContextBridge* bridge)
{
    if (!getJNIWebKitWebContextCache().m_onGeolocationStart.invoke(bridge->m_javaRef.get(),
            static_cast<jboolean>(webkit_geolocation_manager_get_enable_high_accuracy(manager)))) {
        Logging::logError("cannot deliver WebKitWebContext onGeolocationStart callback");
        // Later, as WebKit is still in its start signal.
        g_idle_add_full(
            G_PRIORITY_DEFAULT,
            +[](gpointer manager) -> gboolean {
                webkit_geolocation_manager_failed(
                    WEBKIT_GEOLOCATION_MANAGER(manager), "The location source failed to start");
                return G_SOURCE_REMOVE;
            },
            g_object_ref(manager), g_object_unref);
    }
    return TRUE;
}

void JNIWebKitWebContextCache::onGeolocationStop(WebKitGeolocationManager*, WebKitWebContextBridge* bridge)
{
    if (!getJNIWebKitWebContextCache().m_onGeolocationStop.invoke(bridge->m_javaRef.get()))
        Logging::logError("cannot deliver WebKitWebContext onGeolocationStop callback");
}

void JNIWebKitWebContextCache::onGeolocationHighAccuracyChanged(
    GObject* manager, GParamSpec*, WebKitWebContextBridge* bridge)
{
    if (!getJNIWebKitWebContextCache().m_onGeolocationHighAccuracyChanged.invoke(bridge->m_javaRef.get(),
            static_cast<jboolean>(
                webkit_geolocation_manager_get_enable_high_accuracy(WEBKIT_GEOLOCATION_MANAGER(manager)))))
        Logging::logError("cannot deliver WebKitWebContext onGeolocationHighAccuracyChanged callback");
}

jlong JNIWebKitWebContextCache::nativeInit(JNIEnv* env, jobject jniContext, jboolean automationMode)
{
    auto* context = webkit_web_context_new();
    webkit_web_context_set_automation_allowed(context, automationMode ? TRUE : FALSE);

    auto* bridge = new WebKitWebContextBridge(context);
    bridge->m_javaRef = JNI::GlobalRef<JNIWebKitWebContext>(env, jniContext);
    auto* geolocationManager = webkit_web_context_get_geolocation_manager(context);
    g_signal_connect(geolocationManager, "start", G_CALLBACK(onGeolocationStart), bridge);
    g_signal_connect(geolocationManager, "stop", G_CALLBACK(onGeolocationStop), bridge);
    g_signal_connect(
        geolocationManager, "notify::enable-high-accuracy", G_CALLBACK(onGeolocationHighAccuracyChanged), bridge);
    if (automationMode) {
        bridge->m_automationSignalId
            = g_signal_connect(context, "automation-started", G_CALLBACK(onAutomationStarted), bridge);
    }
    return reinterpret_cast<jlong>(bridge);
}

void JNIWebKitWebContextCache::nativeDestroy(JNIEnv*, jobject, jlong nativePtr)
{
    delete JNI::from_jlong<WebKitWebContextBridge>(nativePtr);
}

jboolean JNIWebKitWebContextCache::nativeIsAutomationMode(JNIEnv*, jobject, jlong nativePtr)
{
    return static_cast<jboolean>(
        webkit_web_context_is_automation_allowed(JNI::from_jlong<WebKitWebContextBridge>(nativePtr)->m_context));
}

jlong JNIWebKitWebContextCache::nativeGetWebKitWebContextPtr(JNIEnv*, jobject, jlong nativePtr)
{
    return reinterpret_cast<jlong>(JNI::from_jlong<WebKitWebContextBridge>(nativePtr)->m_context);
}

void JNIWebKitWebContextCache::nativeSetPreferredLanguages(
    JNIEnv*, jobject, jlong nativePtr, jstringArray languagesArray)
{
    g_autoptr(GStrvBuilder) builder = g_strv_builder_new();
    for (auto language : JNI::ObjectArray<jstring>(languagesArray).getReadOnlyContent()) {
        // The JNI::String must outlive its content, whose release uses the Java string.
        auto javaLanguage = JNI::String(language);
        auto languageContent = javaLanguage.getContent();
        if (languageContent)
            g_strv_builder_add(builder, languageContent.get());
    }
    g_auto(GStrv) languages = g_strv_builder_end(builder);

    webkit_web_context_set_preferred_languages(
        JNI::from_jlong<WebKitWebContextBridge>(nativePtr)->m_context, languages);
}

// The values a position does not have are NaN, and a time of 0 is the current one.
void JNIWebKitWebContextCache::nativeUpdateGeolocationPosition(JNIEnv*, jobject, jlong nativePtr, jdouble latitude,
    jdouble longitude, jdouble accuracy, jdouble altitude, jdouble altitudeAccuracy, jdouble heading, jdouble speed,
    jlong timeSeconds)
{
    auto* position = webkit_geolocation_position_new(latitude, longitude, accuracy);
    if (!std::isnan(altitude))
        webkit_geolocation_position_set_altitude(position, altitude);
    if (!std::isnan(altitudeAccuracy))
        webkit_geolocation_position_set_altitude_accuracy(position, altitudeAccuracy);
    if (!std::isnan(heading))
        webkit_geolocation_position_set_heading(position, heading);
    if (!std::isnan(speed))
        webkit_geolocation_position_set_speed(position, speed);
    webkit_geolocation_position_set_timestamp(position, static_cast<guint64>(timeSeconds));
    webkit_geolocation_manager_update_position(
        webkit_web_context_get_geolocation_manager(JNI::from_jlong<WebKitWebContextBridge>(nativePtr)->m_context),
        position);
    webkit_geolocation_position_free(position);
}

void JNIWebKitWebContextCache::nativeGeolocationFailed(JNIEnv*, jobject, jlong nativePtr, jstring message)
{
    auto javaMessage = JNI::String(message);
    auto content = javaMessage.getContent();
    webkit_geolocation_manager_failed(
        webkit_web_context_get_geolocation_manager(JNI::from_jlong<WebKitWebContextBridge>(nativePtr)->m_context),
        content ? content.get() : "");
}

// The origins of the addresses, as WebKit's security origins.
static GList* securityOrigins(jstringArray originsArray)
{
    GList* origins = nullptr;
    for (auto origin : JNI::ObjectArray<jstring>(originsArray).getReadOnlyContent()) {
        // The JNI::String must outlive its content, whose release uses the Java string.
        auto javaOrigin = JNI::String(origin);
        auto content = javaOrigin.getContent();
        if (content)
            origins = g_list_prepend(origins, webkit_security_origin_new_for_uri(content.get()));
    }
    return g_list_reverse(origins);
}

// WebKit gives the lists to each web process it starts from now on, for Notification.permission and new
// Notification() in its pages. The processes that run keep what they had.
void JNIWebKitWebContextCache::nativeSetNotificationPermissions(
    JNIEnv*, jobject, jlong nativePtr, jstringArray allowedArray, jstringArray deniedArray)
{
    GList* allowed = securityOrigins(allowedArray);
    GList* denied = securityOrigins(deniedArray);
    webkit_web_context_initialize_notification_permissions(
        JNI::from_jlong<WebKitWebContextBridge>(nativePtr)->m_context, allowed, denied);
    g_list_free_full(allowed, reinterpret_cast<GDestroyNotify>(webkit_security_origin_unref));
    g_list_free_full(denied, reinterpret_cast<GDestroyNotify>(webkit_security_origin_unref));
}

WebKitWebView* JNIWebKitWebContextCache::invokeCreateWebKitWebViewForAutomation(JNIWebKitWebContext javaContext) const
{
    const auto webViewPtr = m_invokeCreateWebKitWebViewForAutomation.invoke(javaContext);
    return webViewPtr ? reinterpret_cast<WebKitWebView*>(webViewPtr) : nullptr;
}

void configureWebKitWebContextJNIMappings()
{
    getJNIWebKitWebContextCache();
}
} // namespace WebKit
