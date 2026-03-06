/**
 * Copyright (C) 2026 Igalia S.L. <info@igalia.com>
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

#include "CAPI.h"
#include "JNI/JNI.h"
#include "Logging.h"

#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebContext, "org/wpewebkit/wpe/WebKitWebContext");

namespace WebKit {

class JNIWebKitWebContextCache;
const JNIWebKitWebContextCache& getJNIWebKitWebContextCache();

class JNIWebKitWebContextCache final : public JNI::TypedClass<JNIWebKitWebContext> {
public:
    JNIWebKitWebContextCache()
        : JNI::TypedClass<JNIWebKitWebContext>(true)
        , m_invokeCreateWebKitWebViewForAutomation(getMethod<jlong()>("createWebKitWebViewForAutomation"))
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jboolean)>("nativeInit", JNIWebKitWebContextCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWebKitWebContextCache::nativeDestroy),
            JNI::NativeMethod<jboolean(jlong)>(
                "nativeIsAutomationMode", JNIWebKitWebContextCache::nativeIsAutomationMode));
    }

    WebKitWebView* invokeCreateWebKitWebViewForAutomation(jobject javaRef) const noexcept
    {
        try {
            const auto webViewPtr
                = m_invokeCreateWebKitWebViewForAutomation.invoke(reinterpret_cast<JNIWebKitWebContext>(javaRef));
            return webViewPtr > 0 ? reinterpret_cast<WebKitWebView*>(webViewPtr) : nullptr;
        } catch (const std::exception& ex) {
            Logging::logError("cannot create automation web view (%s)", ex.what());
            return nullptr;
        }
    }

private:
    static void onAutomationStarted(WebKitWebContext*, WebKitAutomationSession* session, jobject javaRef)
    {
        auto* info = webkit_application_info_new();
        webkit_application_info_set_name(info, "MiniBrowser");
        webkit_application_info_set_version(info, WEBKIT_MAJOR_VERSION, WEBKIT_MINOR_VERSION, WEBKIT_MICRO_VERSION);
        webkit_automation_session_set_application_info(session, info);
        webkit_application_info_unref(info);

        g_signal_connect_swapped(
            session, "create-web-view", G_CALLBACK(JNIWebKitWebContextCache::onCreateWebViewForAutomation), javaRef);
    }

    static WebKitWebView* onCreateWebViewForAutomation(jobject javaRef)
    {
        return getJNIWebKitWebContextCache().invokeCreateWebKitWebViewForAutomation(javaRef);
    }

    static jlong nativeInit(JNIEnv* env, jobject obj, jboolean automationMode)
    {
        auto* context = webkit_web_context_new();
        webkit_web_context_set_automation_allowed(context, automationMode ? TRUE : FALSE);
        if (automationMode) {
            auto* javaRef = env->NewGlobalRef(obj);
            g_object_set_data_full(G_OBJECT(context), "java_ref", javaRef,
                [](gpointer data) { JNI::getCurrentThreadJNIEnv()->DeleteGlobalRef(static_cast<jobject>(data)); });
            g_signal_connect(
                context, "automation-started", G_CALLBACK(JNIWebKitWebContextCache::onAutomationStarted), javaRef);
        }
        return reinterpret_cast<jlong>(context);
    }

    static void nativeDestroy(JNIEnv*, jobject, jlong contextPtr) noexcept
    {
        auto* context = reinterpret_cast<WebKitWebContext*>(contextPtr);
        if (!context)
            return;

        auto* javaRef = static_cast<jobject>(g_object_steal_data(G_OBJECT(context), "java_ref"));
        if (javaRef) {
            g_signal_handlers_disconnect_by_data(G_OBJECT(context), javaRef);
            JNI::getCurrentThreadJNIEnv()->DeleteGlobalRef(javaRef);
        }
        g_object_unref(context);
    }

    static jboolean nativeIsAutomationMode(JNIEnv*, jobject, jlong contextPtr) noexcept
    {
        auto* context = reinterpret_cast<WebKitWebContext*>(contextPtr);
        return context ? static_cast<jboolean>(webkit_web_context_is_automation_allowed(context)) : JNI_FALSE;
    }

    const JNI::Method<jlong()> m_invokeCreateWebKitWebViewForAutomation;
};

const JNIWebKitWebContextCache& getJNIWebKitWebContextCache()
{
    static const JNIWebKitWebContextCache s_singleton;
    return s_singleton;
}

void configureWebKitWebContextJNIMappings()
{
    getJNIWebKitWebContextCache();
}

} // namespace WebKit
