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
#include <wpe/wpe-platform.h>

/***********************************************************************************************************************
 * JNI mapping with Java WebKitWebView class.
 *
 * This file is both the low-level JNI bridge for the WebKitWebView API and the adapter that forwards a limited set of
 * native WebKit signals back into the Java bridge layer.
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebView, "org/wpewebkit/wpe/WebKitWebView");
DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebViewEvalCallbackHolder, "org/wpewebkit/wpe/WebKitWebView$EvalCallbackHolder");

namespace WebKit {

class JNIWebKitWebViewEvalCallbackHolderCache final : public JNI::TypedClass<JNIWebKitWebViewEvalCallbackHolder> {
public:
    JNIWebKitWebViewEvalCallbackHolderCache()
        : JNI::TypedClass<JNIWebKitWebViewEvalCallbackHolder>(true)
        , m_commitResult(getMethod<void(jstring)>("commitResult"))
    {
    }

    void onResult(JNIWebKitWebViewEvalCallbackHolder holder, jstring result) const noexcept
    {
        try {
            m_commitResult.invoke(holder, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WebKitWebView eval callback (%s)", ex.what());
        }
        try {
            JNI::getCurrentThreadJNIEnv()->DeleteGlobalRef(holder);
        } catch (const std::exception& ex) {
            Logging::logError("Failed to release EvalCallbackHolder reference (%s)", ex.what());
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

class JNIWebKitWebViewCache;
const JNIWebKitWebViewCache& getJNIWebKitWebViewCache();

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
        registerNativeMethods(JNI::NativeMethod<jlong(jlong, jlong, jlong, jlong, jlong)>(
                                  "nativeInit", JNIWebKitWebViewCache::nativeInit),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWebKitWebViewCache::nativeDestroy),
            JNI::NativeMethod<void(jlong, jstring)>("nativeLoadUrl", JNIWebKitWebViewCache::nativeLoadUrl),
            JNI::NativeMethod<void(jlong, jstring, jstring)>("nativeLoadHtml", JNIWebKitWebViewCache::nativeLoadHtml),
            JNI::NativeMethod<void(jlong)>("nativeStopLoading", JNIWebKitWebViewCache::nativeStopLoading),
            JNI::NativeMethod<void(jlong)>("nativeReload", JNIWebKitWebViewCache::nativeReload),
            JNI::NativeMethod<void(jlong)>("nativeGoBack", JNIWebKitWebViewCache::nativeGoBack),
            JNI::NativeMethod<void(jlong)>("nativeGoForward", JNIWebKitWebViewCache::nativeGoForward),
            JNI::NativeMethod<jlong(jlong)>("nativeGetWPEView", JNIWebKitWebViewCache::nativeGetWPEView),
            JNI::NativeMethod<void(jlong, jdouble)>("nativeSetZoomLevel", JNIWebKitWebViewCache::nativeSetZoomLevel),
            JNI::NativeMethod<void(jlong, jstring, JNIWebKitWebViewEvalCallbackHolder)>(
                "nativeEvaluateJavascript", JNIWebKitWebViewCache::nativeEvaluateJavascript));
    }

    const JNI::Method<void()> m_onClose;
    const JNI::Method<void(jstring)> m_onLoadStarted;
    const JNI::Method<void(jstring)> m_onLoadFinished;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jdouble)> m_onEstimatedLoadProgress;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onTitleChanged;
    const JNI::Method<void(jstring, jstring, jstring, jint)> m_onReceivedHttpError;

private:
    static jlong nativeInit(JNIEnv* env, jobject obj, jlong displayPtr, jlong contextPtr, jlong toplevelPtr,
        jlong networkSessionPtr, jlong settingsPtr);
    static void nativeDestroy(JNIEnv* env, jobject obj, jlong nativePtr) noexcept;
    static void nativeLoadUrl(JNIEnv* env, jobject obj, jlong nativePtr, jstring url) noexcept;
    static void nativeLoadHtml(JNIEnv* env, jobject obj, jlong nativePtr, jstring content, jstring baseUri) noexcept;
    static void nativeStopLoading(JNIEnv* env, jobject obj, jlong nativePtr) noexcept;
    static void nativeReload(JNIEnv* env, jobject obj, jlong nativePtr) noexcept;
    static void nativeGoBack(JNIEnv* env, jobject obj, jlong nativePtr) noexcept;
    static void nativeGoForward(JNIEnv* env, jobject obj, jlong nativePtr) noexcept;
    static jlong nativeGetWPEView(JNIEnv* env, jobject obj, jlong nativePtr) noexcept;
    static void nativeSetZoomLevel(JNIEnv* env, jobject obj, jlong nativePtr, jdouble zoomLevel) noexcept;
    static void nativeEvaluateJavascript(JNIEnv* env, jobject obj, jlong nativePtr, jstring script,
        JNIWebKitWebViewEvalCallbackHolder callbackHolder) noexcept;
};

const JNIWebKitWebViewCache& getJNIWebKitWebViewCache()
{
    static const JNIWebKitWebViewCache s_singleton;
    return s_singleton;
}

template <typename Method, typename... Args>
static void invokeJavaWebViewCallback(const Method& method, jobject javaRef, Args&&... args)
{
    if (!javaRef)
        return;

    try {
        method.invoke(reinterpret_cast<JNIWebKitWebView>(javaRef), std::forward<Args>(args)...);
    } catch (const std::exception& ex) {
        Logging::logError("cannot deliver WebKitWebView callback (%s)", ex.what());
    }
}

static void onClose(WebKitWebView*, jobject javaRef)
{
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onClose, javaRef);
}

static void onLoadStarted(WebKitWebView* webView, jobject javaRef)
{
    if (!webView)
        return;

    auto jUri = JNI::String(webkit_web_view_get_uri(webView));
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onLoadStarted, javaRef, static_cast<jstring>(jUri));
}

static void onLoadFinished(WebKitWebView* webView, jobject javaRef)
{
    if (!webView)
        return;

    auto jUri = JNI::String(webkit_web_view_get_uri(webView));
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onLoadFinished, javaRef, static_cast<jstring>(jUri));
}

static void handleLoadChanged(WebKitWebView* webView, WebKitLoadEvent loadEvent, jobject javaRef)
{
    if (loadEvent == WEBKIT_LOAD_STARTED)
        onLoadStarted(webView, javaRef);
    else if (loadEvent == WEBKIT_LOAD_FINISHED)
        onLoadFinished(webView, javaRef);
}

static void onUriChanged(GObject* obj, GParamSpec*, jobject javaRef)
{
    if (!obj)
        return;

    auto jUri = JNI::String(webkit_web_view_get_uri(WEBKIT_WEB_VIEW(obj)));
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onUriChanged, javaRef, static_cast<jstring>(jUri));
}

static void onEstimatedLoadProgress(GObject* obj, GParamSpec*, jobject javaRef)
{
    if (!obj)
        return;

    double progress = webkit_web_view_get_estimated_load_progress(WEBKIT_WEB_VIEW(obj));
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onEstimatedLoadProgress, javaRef, progress);
}

static void onTitleChanged(GObject* obj, GParamSpec*, jobject javaRef)
{
    if (!obj)
        return;

    auto* webView = WEBKIT_WEB_VIEW(obj);
    auto jTitle = JNI::String(webkit_web_view_get_title(webView));
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onTitleChanged, javaRef, static_cast<jstring>(jTitle),
        static_cast<jboolean>(webkit_web_view_can_go_back(webView)),
        static_cast<jboolean>(webkit_web_view_can_go_forward(webView)));
}

static gboolean handleDecidePolicyForHttpError(
    WebKitWebView*, WebKitPolicyDecision* decision, WebKitPolicyDecisionType decisionType, jobject javaRef)
{
    if (decisionType != WEBKIT_POLICY_DECISION_TYPE_RESPONSE)
        return FALSE;
    if (!decision)
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
    invokeJavaWebViewCallback(getJNIWebKitWebViewCache().m_onReceivedHttpError, javaRef, static_cast<jstring>(jUri),
        static_cast<jstring>(jMethod), static_cast<jstring>(jMimeType), static_cast<jint>(statusCode));

    return FALSE;
}

jlong JNIWebKitWebViewCache::nativeInit(JNIEnv* env, jobject jniWebView, jlong displayPtr, jlong contextPtr,
    jlong toplevelPtr, jlong networkSessionPtr, jlong settingsPtr)
{
    auto* display = reinterpret_cast<WPEDisplay*>(displayPtr);
    auto* context = reinterpret_cast<WebKitWebContext*>(contextPtr);
    auto* toplevel = reinterpret_cast<WPEToplevel*>(toplevelPtr);
    auto* networkSession = reinterpret_cast<WebKitNetworkSession*>(networkSessionPtr);
    auto* settings = reinterpret_cast<WebKitSettings*>(settingsPtr);

    if (!display || !context)
        return 0;

    gboolean automation = webkit_web_context_is_automation_allowed(context);
    auto* webView = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "display", display, "web-context", context,
        "network-session", networkSession, "settings", settings, "is-controlled-by-automation", automation, nullptr));

    auto* wpeView = webkit_web_view_get_wpe_view(webView);
    if (toplevel)
        wpe_view_set_toplevel(wpeView, toplevel);

    // Attach the Java back-reference to the GObject lifetime. The destroy function is a safety net in case the
    // GObject is ever finalized without nativeDestroy being called first.
    auto* javaRef = env->NewGlobalRef(jniWebView);
    g_object_set_data_full(G_OBJECT(webView), "java_ref", javaRef,
        [](gpointer data) { JNI::getCurrentThreadJNIEnv()->DeleteGlobalRef(static_cast<jobject>(data)); });

    g_signal_connect(webView, "close", G_CALLBACK(onClose), javaRef);
    g_signal_connect(webView, "decide-policy", G_CALLBACK(handleDecidePolicyForHttpError), javaRef);
    g_signal_connect(webView, "load-changed", G_CALLBACK(handleLoadChanged), javaRef);
    g_signal_connect(webView, "notify::estimated-load-progress", G_CALLBACK(onEstimatedLoadProgress), javaRef);
    g_signal_connect(webView, "notify::title", G_CALLBACK(onTitleChanged), javaRef);
    g_signal_connect(webView, "notify::uri", G_CALLBACK(onUriChanged), javaRef);

    return reinterpret_cast<jlong>(webView);
}

void JNIWebKitWebViewCache::nativeDestroy(JNIEnv* env, jobject, jlong nativePtr) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (!webView)
        return;

    auto* javaRef = static_cast<jobject>(g_object_steal_data(G_OBJECT(webView), "java_ref"));
    if (javaRef) {
        g_signal_handlers_disconnect_by_data(G_OBJECT(webView), javaRef);
        env->DeleteGlobalRef(javaRef);
    }
    g_object_unref(webView);
}

void JNIWebKitWebViewCache::nativeLoadUrl(JNIEnv*, jobject, jlong nativePtr, jstring url) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView)
        webkit_web_view_load_uri(webView, JNI::String(url).getContent().get());
}

void JNIWebKitWebViewCache::nativeLoadHtml(JNIEnv*, jobject, jlong nativePtr, jstring content, jstring baseUri) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView) {
        webkit_web_view_load_html(
            webView, JNI::String(content).getContent().get(), JNI::String(baseUri).getContent().get());
    }
}

void JNIWebKitWebViewCache::nativeStopLoading(JNIEnv*, jobject, jlong nativePtr) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView)
        webkit_web_view_stop_loading(webView);
}

void JNIWebKitWebViewCache::nativeReload(JNIEnv*, jobject, jlong nativePtr) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView)
        webkit_web_view_reload(webView);
}

void JNIWebKitWebViewCache::nativeGoBack(JNIEnv*, jobject, jlong nativePtr) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView)
        webkit_web_view_go_back(webView);
}

void JNIWebKitWebViewCache::nativeGoForward(JNIEnv*, jobject, jlong nativePtr) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView)
        webkit_web_view_go_forward(webView);
}

jlong JNIWebKitWebViewCache::nativeGetWPEView(JNIEnv*, jobject, jlong nativePtr) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    return webView ? reinterpret_cast<jlong>(webkit_web_view_get_wpe_view(webView)) : 0;
}

void JNIWebKitWebViewCache::nativeSetZoomLevel(JNIEnv*, jobject, jlong nativePtr, jdouble zoomLevel) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (webView)
        webkit_web_view_set_zoom_level(webView, zoomLevel);
}

static void onEvalJavascriptReady(
    WebKitWebView* webView, GAsyncResult* result, JNIWebKitWebViewEvalCallbackHolder callbackHolder) noexcept
{
    GError* error = nullptr;
    JSCValue* value = webkit_web_view_evaluate_javascript_finish(webView, result, &error);
    if (value == nullptr) {
        if (error)
            g_error_free(error);
        if (callbackHolder) {
            auto jNull = JNI::String("null");
            getJNIWebKitWebViewEvalCallbackHolderCache().onResult(callbackHolder, static_cast<jstring>(jNull));
        }
        return;
    }

    JSCException* exception = jsc_context_get_exception(jsc_value_get_context(value));
    JNI::String resultStr("null");
    if (exception == nullptr && jsc_value_is_null(value) != TRUE && jsc_value_is_undefined(value) != TRUE) {
        gchar* strValue = jsc_value_to_json(value, 0);
        resultStr = JNI::String(strValue);
        g_free(strValue);
    }
    g_object_unref(value);
    if (callbackHolder)
        getJNIWebKitWebViewEvalCallbackHolderCache().onResult(callbackHolder, static_cast<jstring>(resultStr));
}

void JNIWebKitWebViewCache::nativeEvaluateJavascript(
    JNIEnv* env, jobject, jlong nativePtr, jstring script, JNIWebKitWebViewEvalCallbackHolder callbackHolder) noexcept
{
    auto* webView = reinterpret_cast<WebKitWebView*>(nativePtr);
    if (!webView)
        return;

    auto globalHolder = callbackHolder ? env->NewGlobalRef(callbackHolder) : nullptr;
    webkit_web_view_evaluate_javascript(webView, JNI::String(script).getContent().get(), -1, nullptr, nullptr, nullptr,
        globalHolder ? reinterpret_cast<GAsyncReadyCallback>(onEvalJavascriptReady) : nullptr, globalHolder);
}

void configureWebKitWebViewJNIMappings()
{
    getJNIWebKitWebViewEvalCallbackHolderCache();
    getJNIWebKitWebViewCache();
}

} // namespace WebKit
