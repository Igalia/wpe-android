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
        if (m_automationSignalId)
            g_signal_handler_disconnect(m_context, m_automationSignalId);
        if (m_createWebViewSignalId) {
            if (GObject* automationSession = static_cast<GObject*>(g_weak_ref_get(&m_automationSession))) {
                g_signal_handler_disconnect(automationSession, m_createWebViewSignalId);
                g_object_unref(automationSession);
            }
        }
        g_weak_ref_clear(&m_automationSession);
        g_object_unref(m_context);
    }

    WebKitWebContext* m_context;
    JNI::GlobalRef<JNIWebKitWebContext> m_javaRef;
    GWeakRef m_automationSession;
    gulong m_automationSignalId {0};
    gulong m_createWebViewSignalId {0};
};

class JNIWebKitWebContextCache final : public JNI::TypedClass<JNIWebKitWebContext> {
public:
    JNIWebKitWebContextCache()
        : JNI::TypedClass<JNIWebKitWebContext>(true)
        , m_invokeCreateWebKitWebViewForAutomation(getMethod<jlong()>("createWebKitWebViewForAutomation"))
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jboolean)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<jboolean(jlong)>("nativeIsAutomationMode", nativeIsAutomationMode),
            JNI::NativeMethod<jlong(jlong)>("nativeGetWebKitWebContextPtr", nativeGetWebKitWebContextPtr));
    }

    WebKitWebView* invokeCreateWebKitWebViewForAutomation(JNIWebKitWebContext javaContext) const;

private:
    static void onAutomationStarted(
        WebKitWebContext*, WebKitAutomationSession* session, WebKitWebContextBridge* bridge);
    static WebKitWebView* onCreateWebViewForAutomation(WebKitWebContextBridge* bridge);

    static jlong nativeInit(JNIEnv* env, jobject jniContext, jboolean automationMode);
    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr);
    static jboolean nativeIsAutomationMode(JNIEnv*, jobject, jlong nativePtr);
    static jlong nativeGetWebKitWebContextPtr(JNIEnv*, jobject, jlong nativePtr);

    const JNI::Method<jlong()>
        m_invokeCreateWebKitWebViewForAutomation; // NOLINT(cppcoreguidelines-avoid-const-or-ref-data-members)
};

const JNIWebKitWebContextCache& getJNIWebKitWebContextCache()
{
    static const JNIWebKitWebContextCache s_singleton;
    return s_singleton;
}

WebKitWebView* JNIWebKitWebContextCache::onCreateWebViewForAutomation(WebKitWebContextBridge* bridge)
{
    return getJNIWebKitWebContextCache().invokeCreateWebKitWebViewForAutomation(bridge->m_javaRef.get());
}

void JNIWebKitWebContextCache::onAutomationStarted(
    WebKitWebContext*, WebKitAutomationSession* session, WebKitWebContextBridge* bridge)
{
    g_autoptr(WebKitApplicationInfo) info = webkit_application_info_new();
    webkit_application_info_set_name(info, "WPEWebView");
    webkit_application_info_set_version(info, WEBKIT_MAJOR_VERSION, WEBKIT_MINOR_VERSION, WEBKIT_MICRO_VERSION);
    webkit_automation_session_set_application_info(session, info);

    if (GObject* previousAutomationSession = static_cast<GObject*>(g_weak_ref_get(&bridge->m_automationSession))) {
        if (bridge->m_createWebViewSignalId)
            g_signal_handler_disconnect(previousAutomationSession, bridge->m_createWebViewSignalId);
        g_object_unref(previousAutomationSession);
    }

    g_weak_ref_set(&bridge->m_automationSession, G_OBJECT(session));
    bridge->m_createWebViewSignalId
        = g_signal_connect_swapped(session, "create-web-view", G_CALLBACK(onCreateWebViewForAutomation), bridge);
}

jlong JNIWebKitWebContextCache::nativeInit(JNIEnv* env, jobject jniContext, jboolean automationMode)
{
    auto* context = webkit_web_context_new();
    webkit_web_context_set_automation_allowed(context, automationMode ? TRUE : FALSE);

    auto* bridge = new WebKitWebContextBridge(context);
    if (automationMode) {
        bridge->m_javaRef = JNI::GlobalRef<JNIWebKitWebContext>(env, jniContext);
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

WebKitWebView* JNIWebKitWebContextCache::invokeCreateWebKitWebViewForAutomation(JNIWebKitWebContext javaContext) const
{
    try {
        const auto webViewPtr = m_invokeCreateWebKitWebViewForAutomation.invoke(javaContext);
        return webViewPtr ? reinterpret_cast<WebKitWebView*>(webViewPtr) : nullptr;
    } catch (const std::exception& ex) {
        Logging::logError("cannot create automation web view (%s)", ex.what());
        return nullptr;
    }
}

void configureWebKitWebContextJNIMappings()
{
    getJNIWebKitWebContextCache();
}
} // namespace WebKit
