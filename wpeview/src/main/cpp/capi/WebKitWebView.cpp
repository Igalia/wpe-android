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
#include <vector>
#include <wpe/webkit.h>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebViewEvalCallbackHolder, "org/wpewebkit/wpe/WebKitWebView$EvalCallbackHolder");
DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebView, "org/wpewebkit/wpe/WebKitWebView");

namespace WebKit {

struct WebKitWebViewBridge {
    WebKitWebViewBridge(WebKitWebView* webView, JNIEnv* env, jobject javaWebView)
        : m_webView(webView)
        , m_javaRef(env, javaWebView)
    {
    }

    ~WebKitWebViewBridge()
    {
        for (gulong id : m_signalIds)
            g_signal_handler_disconnect(m_webView, id);
        m_signalIds.clear();
        g_object_unref(m_webView);
    }

    WebKitWebView* m_webView;
    JNI::GlobalRef<JNIWebKitWebView> m_javaRef;
    std::vector<gulong> m_signalIds;
};

class JNIWebKitWebViewEvalCallbackHolderCache final : public JNI::TypedClass<JNIWebKitWebViewEvalCallbackHolder> {
public:
    JNIWebKitWebViewEvalCallbackHolderCache()
        : JNI::TypedClass<JNIWebKitWebViewEvalCallbackHolder>(true)
        , m_commitResult(getMethod<void(jstring)>("commitResult"))
    {
    }

    void onResult(JNIWebKitWebViewEvalCallbackHolder holder, jstring result) const
    {
        if (!holder)
            return;

        try {
            m_commitResult.invoke(holder, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WebKitWebView eval callback (%s)", ex.what());
        }
    }

private:
    const JNI::Method<void(jstring)> m_commitResult;
};

const JNIWebKitWebViewEvalCallbackHolderCache& getJNIWebKitWebViewEvalCallbackHolderCache()
{
    static const JNIWebKitWebViewEvalCallbackHolderCache s_singleton;
    return s_singleton;
}

class JNIWebKitWebViewCache final : public JNI::TypedClass<JNIWebKitWebView> {
public:
    JNIWebKitWebViewCache()
        : JNI::TypedClass<JNIWebKitWebView>(true)
        , m_onClose(getMethod<void()>("onClose"))
        , m_onLoadStarted(getMethod<void(jstring)>("onLoadStarted"))
        , m_onLoadFinished(getMethod<void(jstring)>("onLoadFinished"))
        , m_onUriChanged(getMethod<void(jstring)>("onUriChanged"))
        , m_onEstimatedLoadProgress(getMethod<void(jdouble)>("onEstimatedLoadProgress"))
        , m_onTitleChanged(getMethod<void(jstring, jboolean, jboolean)>("onTitleChanged"))
        , m_onReceivedHttpError(getMethod<void(jstring, jstring, jstring, jint)>("onReceivedHttpError"))
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jlong, jlong, jlong, jlong, jlong)>("nativeInit", nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<void(jlong, jstring)>("nativeLoadUrl", nativeLoadUrl),
            JNI::NativeMethod<void(jlong, jstring, jstring)>("nativeLoadHtml", nativeLoadHtml),
            JNI::NativeMethod<void(jlong)>("nativeStopLoading", nativeStopLoading),
            JNI::NativeMethod<void(jlong)>("nativeReload", nativeReload),
            JNI::NativeMethod<void(jlong)>("nativeGoBack", nativeGoBack),
            JNI::NativeMethod<void(jlong)>("nativeGoForward", nativeGoForward),
            JNI::NativeMethod<jlong(jlong)>("nativeGetWebKitWebViewPtr", nativeGetWebKitWebViewPtr),
            JNI::NativeMethod<jlong(jlong)>("nativeGetWPEView", nativeGetWPEView),
            JNI::NativeMethod<void(jlong, jdouble)>("nativeSetZoomLevel", nativeSetZoomLevel),
            JNI::NativeMethod<void(jlong, jstring, JNIWebKitWebViewEvalCallbackHolder)>(
                "nativeEvaluateJavascript", nativeEvaluateJavascript));
    }

    const JNI::Method<void()> m_onClose;
    const JNI::Method<void(jstring)> m_onLoadStarted;
    const JNI::Method<void(jstring)> m_onLoadFinished;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jdouble)> m_onEstimatedLoadProgress;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onTitleChanged;
    const JNI::Method<void(jstring, jstring, jstring, jint)> m_onReceivedHttpError;

private:
    static void onClose(WebKitWebView*, WebKitWebViewBridge* bridge);
    static void onLoadChanged(WebKitWebView* webView, WebKitLoadEvent loadEvent, WebKitWebViewBridge* bridge);
    static void onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onTitleChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static gboolean onDecidePolicy(WebKitWebView*, WebKitPolicyDecision* decision,
        WebKitPolicyDecisionType decisionType, WebKitWebViewBridge* bridge);
    static void onEvalJavascriptReady(GObject* object, GAsyncResult* result, gpointer userData);

    static jlong nativeInit(JNIEnv* env, jobject jniWebView, jlong displayPtr, jlong contextPtr, jlong toplevelPtr,
        jlong networkSessionPtr, jlong settingsPtr);
    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr);
    static void nativeLoadUrl(JNIEnv*, jobject, jlong nativePtr, jstring url);
    static void nativeLoadHtml(JNIEnv*, jobject, jlong nativePtr, jstring content, jstring baseUri);
    static void nativeStopLoading(JNIEnv*, jobject, jlong nativePtr);
    static void nativeReload(JNIEnv*, jobject, jlong nativePtr);
    static void nativeGoBack(JNIEnv*, jobject, jlong nativePtr);
    static void nativeGoForward(JNIEnv*, jobject, jlong nativePtr);
    static jlong nativeGetWebKitWebViewPtr(JNIEnv*, jobject, jlong nativePtr);
    static jlong nativeGetWPEView(JNIEnv*, jobject, jlong nativePtr);
    static void nativeSetZoomLevel(JNIEnv*, jobject, jlong nativePtr, jdouble zoomLevel);
    static void nativeEvaluateJavascript(
        JNIEnv* env, jobject, jlong nativePtr, jstring script, JNIWebKitWebViewEvalCallbackHolder callbackHolder);
};

const JNIWebKitWebViewCache& getJNIWebKitWebViewCache()
{
    static const JNIWebKitWebViewCache s_singleton;
    return s_singleton;
}

void JNIWebKitWebViewCache::onClose(WebKitWebView*, WebKitWebViewBridge* bridge)
{
    try {
        getJNIWebKitWebViewCache().m_onClose.invoke(bridge->m_javaRef.get());
    } catch (const std::exception& ex) {
        Logging::logError("cannot deliver WebKitWebView onClose callback (%s)", ex.what());
    }
}

void JNIWebKitWebViewCache::onLoadChanged(
    WebKitWebView* webView, WebKitLoadEvent loadEvent, WebKitWebViewBridge* bridge)
{
    // WEBKIT_LOAD_REDIRECTED and WEBKIT_LOAD_COMMITTED are intentionally not surfaced.
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        auto jUri = JNI::String(webkit_web_view_get_uri(webView));
        try {
            getJNIWebKitWebViewCache().m_onLoadStarted.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri));
        } catch (const std::exception& ex) {
            Logging::logError("cannot deliver WebKitWebView onLoadStarted callback (%s)", ex.what());
        }
    } else if (loadEvent == WEBKIT_LOAD_FINISHED) {
        auto jUri = JNI::String(webkit_web_view_get_uri(webView));
        try {
            getJNIWebKitWebViewCache().m_onLoadFinished.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri));
        } catch (const std::exception& ex) {
            Logging::logError("cannot deliver WebKitWebView onLoadFinished callback (%s)", ex.what());
        }
    }
}

void JNIWebKitWebViewCache::onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    auto jUri = JNI::String(webkit_web_view_get_uri(WEBKIT_WEB_VIEW(obj)));
    try {
        getJNIWebKitWebViewCache().m_onUriChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri));
    } catch (const std::exception& ex) {
        Logging::logError("cannot deliver WebKitWebView onUriChanged callback (%s)", ex.what());
    }
}

void JNIWebKitWebViewCache::onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    double progress = webkit_web_view_get_estimated_load_progress(WEBKIT_WEB_VIEW(obj));
    try {
        getJNIWebKitWebViewCache().m_onEstimatedLoadProgress.invoke(bridge->m_javaRef.get(), progress);
    } catch (const std::exception& ex) {
        Logging::logError("cannot deliver WebKitWebView onEstimatedLoadProgress callback (%s)", ex.what());
    }
}

void JNIWebKitWebViewCache::onTitleChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    auto* webView = WEBKIT_WEB_VIEW(obj);
    auto jTitle = JNI::String(webkit_web_view_get_title(webView));
    try {
        getJNIWebKitWebViewCache().m_onTitleChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jTitle),
            static_cast<jboolean>(webkit_web_view_can_go_back(webView)),
            static_cast<jboolean>(webkit_web_view_can_go_forward(webView)));
    } catch (const std::exception& ex) {
        Logging::logError("cannot deliver WebKitWebView onTitleChanged callback (%s)", ex.what());
    }
}

gboolean JNIWebKitWebViewCache::onDecidePolicy(
    WebKitWebView*, WebKitPolicyDecision* decision, WebKitPolicyDecisionType decisionType, WebKitWebViewBridge* bridge)
{
    if (decisionType != WEBKIT_POLICY_DECISION_TYPE_RESPONSE || !decision)
        return FALSE;

    auto* responseDecision = WEBKIT_RESPONSE_POLICY_DECISION(decision);
    auto* uriResponse = webkit_response_policy_decision_get_response(responseDecision);
    guint statusCode = webkit_uri_response_get_status_code(uriResponse);
    if (statusCode < 400)
        return FALSE;

    auto* uriRequest = webkit_response_policy_decision_get_request(responseDecision);
    const char* method = webkit_uri_request_get_http_method(uriRequest);
    auto jUri = JNI::String(webkit_uri_request_get_uri(uriRequest));
    auto jMethod = JNI::String(method ? method : "GET");
    auto jMimeType = JNI::String(webkit_uri_response_get_mime_type(uriResponse));
    try {
        getJNIWebKitWebViewCache().m_onReceivedHttpError.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri),
            static_cast<jstring>(jMethod), static_cast<jstring>(jMimeType), static_cast<jint>(statusCode));
    } catch (const std::exception& ex) {
        Logging::logError("cannot deliver WebKitWebView onReceivedHttpError callback (%s)", ex.what());
    }

    return FALSE;
}

void JNIWebKitWebViewCache::onEvalJavascriptReady(GObject* object, GAsyncResult* result, gpointer userData)
{
    std::unique_ptr<JNI::GlobalRef<JNIWebKitWebViewEvalCallbackHolder>> holder(
        static_cast<JNI::GlobalRef<JNIWebKitWebViewEvalCallbackHolder>*>(userData));
    auto* webView = WEBKIT_WEB_VIEW(object);

    g_autoptr(GError) error = nullptr;
    g_autoptr(JSCValue) value = webkit_web_view_evaluate_javascript_finish(webView, result, &error);
    if (!value) {
        if (*holder) {
            auto jNull = JNI::String("null");
            getJNIWebKitWebViewEvalCallbackHolderCache().onResult(holder->get(), static_cast<jstring>(jNull));
        }
        return;
    }

    JSCException* exception = jsc_context_get_exception(jsc_value_get_context(value));
    JNI::String resultStr("null");
    if (!exception && !jsc_value_is_null(value) && !jsc_value_is_undefined(value)) {
        g_autofree gchar* strValue = jsc_value_to_json(value, 0);
        resultStr = JNI::String(strValue);
    }
    if (*holder)
        getJNIWebKitWebViewEvalCallbackHolderCache().onResult(holder->get(), static_cast<jstring>(resultStr));
}

jlong JNIWebKitWebViewCache::nativeInit(JNIEnv* env, jobject jniWebView, jlong displayPtr, jlong contextPtr,
    jlong toplevelPtr, jlong networkSessionPtr, jlong settingsPtr)
{
    auto* display = JNI::from_jlong<WPEDisplay>(displayPtr);
    auto* context = JNI::from_jlong<WebKitWebContext>(contextPtr);
    auto* toplevel = JNI::from_jlong<WPEToplevel>(toplevelPtr);
    auto* networkSession = JNI::from_jlong<WebKitNetworkSession>(networkSessionPtr);
    auto* settings = JNI::from_jlong<WebKitSettings>(settingsPtr);

    g_assert(display);
    g_assert(context);
    if (!display || !context)
        return 0;

    gboolean isAutomation = webkit_web_context_is_automation_allowed(context);
    auto* webView = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "display", display, "web-context", context,
        "network-session", networkSession, "settings", settings, "is-controlled-by-automation", isAutomation, nullptr));

    auto* wpeView = webkit_web_view_get_wpe_view(webView);
    if (toplevel)
        wpe_view_set_toplevel(wpeView, toplevel);

    auto* bridge = new WebKitWebViewBridge(webView, env, jniWebView);
    bridge->m_signalIds = {
        g_signal_connect(webView, "close", G_CALLBACK(onClose), bridge),
        g_signal_connect(webView, "decide-policy", G_CALLBACK(onDecidePolicy), bridge),
        g_signal_connect(webView, "load-changed", G_CALLBACK(onLoadChanged), bridge),
        g_signal_connect(webView, "notify::estimated-load-progress", G_CALLBACK(onEstimatedLoadProgress), bridge),
        g_signal_connect(webView, "notify::title", G_CALLBACK(onTitleChanged), bridge),
        g_signal_connect(webView, "notify::uri", G_CALLBACK(onUriChanged), bridge),
    };

    return reinterpret_cast<jlong>(bridge);
}

void JNIWebKitWebViewCache::nativeDestroy(JNIEnv*, jobject, jlong nativePtr)
{
    delete JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
}

void JNIWebKitWebViewCache::nativeLoadUrl(JNIEnv*, jobject, jlong nativePtr, jstring url)
{
    webkit_web_view_load_uri(
        JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView, JNI::String(url).getContent().get());
}

void JNIWebKitWebViewCache::nativeLoadHtml(JNIEnv*, jobject, jlong nativePtr, jstring content, jstring baseUri)
{
    webkit_web_view_load_html(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView,
        JNI::String(content).getContent().get(), JNI::String(baseUri).getContent().get());
}

void JNIWebKitWebViewCache::nativeStopLoading(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_stop_loading(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

void JNIWebKitWebViewCache::nativeReload(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_reload(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

void JNIWebKitWebViewCache::nativeGoBack(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_go_back(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

void JNIWebKitWebViewCache::nativeGoForward(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_go_forward(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

jlong JNIWebKitWebViewCache::nativeGetWebKitWebViewPtr(JNIEnv*, jobject, jlong nativePtr)
{
    return reinterpret_cast<jlong>(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

jlong JNIWebKitWebViewCache::nativeGetWPEView(JNIEnv*, jobject, jlong nativePtr)
{
    return reinterpret_cast<jlong>(
        webkit_web_view_get_wpe_view(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView));
}

void JNIWebKitWebViewCache::nativeSetZoomLevel(JNIEnv*, jobject, jlong nativePtr, jdouble zoomLevel)
{
    webkit_web_view_set_zoom_level(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView, zoomLevel);
}

void JNIWebKitWebViewCache::nativeEvaluateJavascript(
    JNIEnv* env, jobject, jlong nativePtr, jstring script, JNIWebKitWebViewEvalCallbackHolder callbackHolder)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    auto* holder
        = callbackHolder ? new JNI::GlobalRef<JNIWebKitWebViewEvalCallbackHolder>(env, callbackHolder) : nullptr;
    webkit_web_view_evaluate_javascript(bridge->m_webView, JNI::String(script).getContent().get(), -1, nullptr, nullptr,
        nullptr, holder ? onEvalJavascriptReady : nullptr, holder);
}

void configureWebKitWebViewJNIMappings()
{
    getJNIWebKitWebViewCache();
}
void configureWebKitWebViewEvalCallbackHolderJNIMappings()
{
    getJNIWebKitWebViewEvalCallbackHolderCache();
}
} // namespace WebKit
