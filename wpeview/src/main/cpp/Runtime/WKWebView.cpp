/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include "WKWebView.h"

#include "Logging.h"
#include "RendererSurfaceControl.h"
#include "WKCallback.h"
#include "WKRuntime.h"
#include "WKWebContext.h"

#include <android/native_window_jni.h>
#include <libsoup/soup.h>
#include <unistd.h>
#include <wpe-android/view-backend.h>

namespace {

void handleCommitBuffer(void* context, WPEAndroidBuffer* buffer, int fenceID)
{
    auto* wkWebView = static_cast<WKWebView*>(context);
    wkWebView->commitBuffer(buffer, fenceID);
}

const int httpErrorsStart = 400;

} // namespace

/***********************************************************************************************************************
 * JNI mapping with Java WKWebView class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNISurface, "android/view/Surface");

class JNIWKWebViewCache;
const JNIWKWebViewCache& getJNIPageCache();

class JNIWKWebViewCache final : public JNI::TypedClass<JNIWKWebView> {
public:
    JNIWKWebViewCache();

    static void onClose(WKWebView* wkWebView, WebKitWebView* /*webView*/) noexcept
    {
        callJavaMethod(getJNIPageCache().m_onClose, wkWebView->m_webViewJavaInstance.get());
    }

    static void onLoadChanged(WKWebView* wkWebView, WebKitLoadEvent loadEvent, WebKitWebView* /*webView*/) noexcept
    {
        Logging::logDebug("WKWebView::onLoadChanged() [tid %d]", gettid());
        callJavaMethod(
            getJNIPageCache().m_onLoadChanged, wkWebView->m_webViewJavaInstance.get(), static_cast<int>(loadEvent));
    }

    static void onEstimatedLoadProgress(WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onEstimatedLoadProgress() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onEstimatedLoadProgress, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jdouble>(webkit_web_view_get_estimated_load_progress(webView)));
    }

    static void onUriChanged(WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        auto jUri = JNI::String(webkit_web_view_get_uri(webView));
        callJavaMethod(
            getJNIPageCache().m_onUriChanged, wkWebView->m_webViewJavaInstance.get(), static_cast<jstring>(jUri));
    }

    static void onTitleChanged(WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        auto jTitle = JNI::String(webkit_web_view_get_title(webView));
        callJavaMethod(getJNIPageCache().m_onTitleChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jstring>(jTitle), static_cast<jboolean>(webkit_web_view_can_go_back(webView)),
            static_cast<jboolean>(webkit_web_view_can_go_forward(webView)));
    }

    static gboolean onScriptDialog(WKWebView* wkWebView, WebKitScriptDialog* dialog, WebKitWebView* webView)
    {
        auto dialogPtr = reinterpret_cast<jlong>(webkit_script_dialog_ref(dialog));
        auto jActiveURL = JNI::String(webkit_web_view_get_uri(webView));
        auto jMessage = JNI::String(webkit_script_dialog_get_message(dialog));
        JNI::String const jDefaultText = webkit_script_dialog_get_dialog_type(dialog) == WEBKIT_SCRIPT_DIALOG_PROMPT
            ? JNI::String(webkit_script_dialog_prompt_get_default_text(dialog))
            : "";
        callJavaMethod(getJNIPageCache().m_onScriptDialog, wkWebView->m_webViewJavaInstance.get(), dialogPtr,
            webkit_script_dialog_get_dialog_type(dialog), static_cast<jstring>(jActiveURL),
            static_cast<jstring>(jMessage), static_cast<jstring>(jDefaultText));

        return TRUE;
    }

    static gboolean onDecidePolicy(WKWebView* wkWebView, WebKitPolicyDecision* decision,
        WebKitPolicyDecisionType decisionType, WebKitWebView* /*webView*/)
    {
        if (decisionType != WEBKIT_POLICY_DECISION_TYPE_RESPONSE)
            return FALSE;
        auto* responseDecision = WEBKIT_RESPONSE_POLICY_DECISION(decision);
        auto* uriRequest = webkit_response_policy_decision_get_request(responseDecision);
        auto* uriResponse = webkit_response_policy_decision_get_response(responseDecision);

        guint const responseStatusCode = webkit_uri_response_get_status_code(uriResponse);
        if (responseStatusCode >= httpErrorsStart) {
            // Request
            SoupMessageHeadersIter requestHeadersIter;
            soup_message_headers_iter_init(&requestHeadersIter, webkit_uri_request_get_http_headers(uriRequest));
            const char* requestHeaderName = nullptr;
            const char* requestHeaderValue = nullptr;
            std::vector<JNI::String> jniStringRequestHeaders;
            while (soup_message_headers_iter_next(&requestHeadersIter, &requestHeaderName, &requestHeaderValue) != 0) {
                jniStringRequestHeaders.emplace_back(requestHeaderName);
                jniStringRequestHeaders.emplace_back(requestHeaderValue);
            }

            auto jRequestUri = JNI::String(webkit_uri_request_get_uri(uriRequest));
            auto jRequestMethod = JNI::String(webkit_uri_request_get_http_method(uriRequest));
            auto jRequestHeaders
                = JNI::ObjectArray<jstring>(TypedClass<jstring>().createArray(jniStringRequestHeaders.size()));
            for (std::vector<JNI::String>::size_type i = 0; i != jniStringRequestHeaders.size(); i++) {
                jRequestHeaders.setValue(i, static_cast<jstring>(jniStringRequestHeaders[i]));
            }

            // Response

            SoupMessageHeadersIter responseHeadersIter;
            soup_message_headers_iter_init(&responseHeadersIter, webkit_uri_response_get_http_headers(uriResponse));
            const char* responseHeaderName = nullptr;
            const char* responseHeaderValue = nullptr;
            std::vector<JNI::String> jniStringResponseHeaders;
            while (
                soup_message_headers_iter_next(&responseHeadersIter, &responseHeaderName, &responseHeaderValue) != 0) {
                jniStringResponseHeaders.emplace_back(responseHeaderName);
                jniStringResponseHeaders.emplace_back(responseHeaderValue);
            }
            auto jResponseMimeType = JNI::String(webkit_uri_response_get_mime_type(uriResponse));
            auto jResponseHeaders
                = JNI::ObjectArray<jstring>(TypedClass<jstring>().createArray(jniStringResponseHeaders.size()));
            for (std::vector<JNI::String>::size_type i = 0; i != jniStringResponseHeaders.size(); i++) {
                jResponseHeaders.setValue(i, static_cast<jstring>(jniStringResponseHeaders[i]));
            }
            callJavaMethod(getJNIPageCache().m_onReceivedHttpError, wkWebView->m_webViewJavaInstance.get(),
                static_cast<jstring>(jRequestUri), static_cast<jstring>(jRequestMethod),
                static_cast<jstringArray>(jRequestHeaders), static_cast<jstring>(jResponseMimeType), responseStatusCode,
                static_cast<jstringArray>(jResponseHeaders));
        }

        return FALSE;
    }

    static bool onFullscreenRequest(WKWebView* wkWebView, bool fullscreen) noexcept
    {
        if (wkWebView->m_viewBackend != nullptr) {
            wkWebView->m_isFullscreenRequested = fullscreen;
            if (fullscreen) {
                callJavaMethod(getJNIPageCache().m_onEnterFullscreenMode, wkWebView->m_webViewJavaInstance.get());
            } else {
                callJavaMethod(getJNIPageCache().m_onExitFullscreenMode, wkWebView->m_webViewJavaInstance.get());
                wpe_view_backend_dispatch_did_exit_fullscreen(
                    WPEAndroidViewBackend_getWPEViewBackend(wkWebView->m_viewBackend));
            }
        }

        return true;
    }

    void onInputMethodContextIn(jobject obj) const noexcept { callJavaMethod(m_onInputMethodContextIn, obj); }

    void onInputMethodContextOut(jobject obj) const noexcept { callJavaMethod(m_onInputMethodContextOut, obj); }

    static void onEvaluateJavascriptReady(WebKitWebView* webView, GAsyncResult* result, JNIWKCallback callback)
    {
        GError* error = nullptr;
        JSCValue* value = webkit_web_view_evaluate_javascript_finish(webView, result, &error);
        if (value == nullptr) {
            g_error_free(error);
            WKCallback::onStringResult(callback, nullptr);
        } else {
            JSCException* exception = jsc_context_get_exception(jsc_value_get_context(value));
            if (exception != nullptr || jsc_value_is_null(value) == TRUE || jsc_value_is_undefined(value) == TRUE) {
                WKCallback::onStringResult(callback, JNI::String("null"));
            } else {
                gchar* strValue = jsc_value_to_json(value, 0);
                auto resultValue = JNI::String(strValue);
                g_free(strValue);
                WKCallback::onStringResult(callback, resultValue);
            }
            g_object_unref(value);
        }

        try {
            JNIEnv* env = JNI::getCurrentThreadJNIEnv();
            env->DeleteGlobalRef(callback);
        } catch (const std::exception& ex) {
            Logging::logError("Failed to release WKCallback reference (%s)", ex.what());
        }
    }

private:
    template <typename T, typename... Args>
    static void callJavaMethod(const JNI::Method<T> method, jobject obj, Args&&... args) noexcept
    {
        try {
            method.invoke(obj, std::forward<Args>(args)...);
        } catch (const std::exception& ex) {
            Logging::logError("Cannot send web view event to Java runtime (%s)", ex.what());
        }
    }

    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void()> m_onClose;
    const JNI::Method<void(jint)> m_onLoadChanged;
    const JNI::Method<void(jdouble)> m_onEstimatedLoadProgress;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onTitleChanged;
    const JNI::Method<jboolean(jlong, jint, jstring, jstring, jstring)> m_onScriptDialog;
    const JNI::Method<void()> m_onInputMethodContextIn;
    const JNI::Method<void()> m_onInputMethodContextOut;
    const JNI::Method<void()> m_onEnterFullscreenMode;
    const JNI::Method<void()> m_onExitFullscreenMode;
    const JNI::Method<void(jstring, jstring, jstringArray, jstring, jint, jstringArray)> m_onReceivedHttpError;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)

    static jlong nativeInit(JNIEnv* env, jobject obj, jlong wkWebContextPtr, jint width, jint height,
        jfloat deviceScale, jboolean headless);
    static void nativeClose(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeDestroy(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeLoadUrl(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jstring url) noexcept;
    static void nativeLoadHtml(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jstring content, jstring baseUri) noexcept;
    static jdouble nativeGetEstimatedLoadProgress(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeGoBack(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeGoForward(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeStopLoading(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeReload(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeSurfaceCreated(JNIEnv* env, jobject obj, jlong wkWebViewPtr, JNISurface surface) noexcept;
    static void nativeSurfaceDestroyed(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeSurfaceChanged(
        JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint format, jint width, jint height) noexcept;
    static void nativeSurfaceRedrawNeeded(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeSetZoomLevel(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jdouble zoomLevel) noexcept;
    static void nativeOnTouchEvent(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jlong time, jint type,
        jint pointerCount, jintArray ids, jfloatArray xs, jfloatArray ys) noexcept;
    static void nativeSetInputMethodContent(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint unicodeChar) noexcept;
    static void nativeDeleteInputMethodContent(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint offset) noexcept;
    static void nativeRequestExitFullscreenMode(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeEvaluateJavascript(
        JNIEnv* env, jobject obj, jlong wkWebViewPtr, jstring script, JNIWKCallback callback) noexcept;
    static void nativeScriptDialogClose(JNIEnv* env, jobject obj, jlong dialogPtr) noexcept;
    static void nativeScriptDialogConfirm(
        JNIEnv* env, jobject obj, jlong dialogPtr, jboolean confirm, jstring text) noexcept;
    static void nativeSetTLSErrorsPolicy(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint policy) noexcept;
};

const JNIWKWebViewCache& getJNIPageCache()
{
    static const JNIWKWebViewCache s_singleton;
    return s_singleton;
}

JNIWKWebViewCache::JNIWKWebViewCache()
    : JNI::TypedClass<JNIWKWebView>(true)
    , m_onClose(getMethod<void()>("onClose"))
    , m_onLoadChanged(getMethod<void(jint)>("onLoadChanged"))
    , m_onEstimatedLoadProgress(getMethod<void(jdouble)>("onEstimatedLoadProgress"))
    , m_onUriChanged(getMethod<void(jstring)>("onUriChanged"))
    , m_onTitleChanged(getMethod<void(jstring, jboolean, jboolean)>("onTitleChanged"))
    , m_onScriptDialog(getMethod<jboolean(jlong, jint, jstring, jstring, jstring)>("onScriptDialog"))
    , m_onInputMethodContextIn(getMethod<void()>("onInputMethodContextIn"))
    , m_onInputMethodContextOut(getMethod<void()>("onInputMethodContextOut"))
    , m_onEnterFullscreenMode(getMethod<void()>("onEnterFullscreenMode"))
    , m_onExitFullscreenMode(getMethod<void()>("onExitFullscreenMode"))
    , m_onReceivedHttpError(
          getMethod<void(jstring, jstring, jstringArray, jstring, jint, jstringArray)>("onReceivedHttpError"))
{
    registerNativeMethods(
        JNI::NativeMethod<jlong(jlong, jint, jint, jfloat, jboolean)>("nativeInit", JNIWKWebViewCache::nativeInit),
        JNI::NativeMethod<void(jlong)>("nativeClose", JNIWKWebViewCache::nativeClose),
        JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIWKWebViewCache::nativeDestroy),
        JNI::NativeMethod<void(jlong, jstring)>("nativeLoadUrl", JNIWKWebViewCache::nativeLoadUrl),
        JNI::NativeMethod<void(jlong, jstring, jstring)>("nativeLoadHtml", JNIWKWebViewCache::nativeLoadHtml),
        JNI::NativeMethod<jdouble(jlong)>(
            "nativeGetEstimatedLoadProgress", JNIWKWebViewCache::nativeGetEstimatedLoadProgress),
        JNI::NativeMethod<void(jlong)>("nativeGoBack", JNIWKWebViewCache::nativeGoBack),
        JNI::NativeMethod<void(jlong)>("nativeGoForward", JNIWKWebViewCache::nativeGoForward),
        JNI::NativeMethod<void(jlong)>("nativeStopLoading", JNIWKWebViewCache::nativeStopLoading),
        JNI::NativeMethod<void(jlong)>("nativeReload", JNIWKWebViewCache::nativeReload),
        JNI::NativeMethod<void(jlong, JNISurface)>("nativeSurfaceCreated", JNIWKWebViewCache::nativeSurfaceCreated),
        JNI::NativeMethod<void(jlong, jint, jint, jint)>(
            "nativeSurfaceChanged", JNIWKWebViewCache::nativeSurfaceChanged),
        JNI::NativeMethod<void(jlong)>("nativeSurfaceRedrawNeeded", JNIWKWebViewCache::nativeSurfaceRedrawNeeded),
        JNI::NativeMethod<void(jlong)>("nativeSurfaceDestroyed", JNIWKWebViewCache::nativeSurfaceDestroyed),
        JNI::NativeMethod<void(jlong, jdouble)>("nativeSetZoomLevel", JNIWKWebViewCache::nativeSetZoomLevel),
        JNI::NativeMethod<void(jlong, jlong, jint, jint, jintArray, jfloatArray, jfloatArray)>(
            "nativeOnTouchEvent", JNIWKWebViewCache::nativeOnTouchEvent),
        JNI::NativeMethod<void(jlong, jint)>(
            "nativeSetInputMethodContent", JNIWKWebViewCache::nativeSetInputMethodContent),
        JNI::NativeMethod<void(jlong, jint)>(
            "nativeDeleteInputMethodContent", JNIWKWebViewCache::nativeDeleteInputMethodContent),
        JNI::NativeMethod<void(jlong)>(
            "nativeRequestExitFullscreenMode", JNIWKWebViewCache::nativeRequestExitFullscreenMode),
        JNI::NativeMethod<void(jlong, jstring, JNIWKCallback)>(
            "nativeEvaluateJavascript", JNIWKWebViewCache::nativeEvaluateJavascript),
        JNI::NativeMethod<void(jlong)>("nativeScriptDialogClose", JNIWKWebViewCache::nativeScriptDialogClose),
        JNI::NativeMethod<void(jlong, jboolean, jstring)>(
            "nativeScriptDialogConfirm", JNIWKWebViewCache::nativeScriptDialogConfirm),
        JNI::NativeMethod<void(jlong, jint)>("nativeSetTLSErrorsPolicy", JNIWKWebViewCache::nativeSetTLSErrorsPolicy));
}

jlong JNIWKWebViewCache::nativeInit(
    JNIEnv* env, jobject obj, jlong wkWebContextPtr, jint width, jint height, jfloat deviceScale, jboolean headless)
{
    Logging::logDebug(
        "WKWebView::nativeInit(%p, %d, %d, [density %f] [tid %d]", obj, width, height, deviceScale, gettid());
    auto* wkWebContext = reinterpret_cast<WKWebContext*>(wkWebContextPtr); // NOLINT(performance-no-int-to-ptr)
    // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    auto* wkWebView = new WKWebView(env, reinterpret_cast<JNIWKWebView>(obj), wkWebContext, width, height, deviceScale,
        static_cast<bool>(headless));
    return reinterpret_cast<jlong>(wkWebView);
}

void JNIWKWebViewCache::nativeClose(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeClose() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr)
        wkWebView->close();
}

void JNIWKWebViewCache::nativeDestroy(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeDestroy() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    delete wkWebView; // NOLINT(cppcoreguidelines-owning-memory)
}

void JNIWKWebViewCache::nativeLoadUrl(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jstring url) noexcept
{
    Logging::logDebug("WKWebView::nativeLoadUrl('...') [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_load_uri(wkWebView->m_webView, JNI::String(url).getContent().get());
}

void JNIWKWebViewCache::nativeLoadHtml(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jstring content, jstring baseUri) noexcept
{
    Logging::logDebug("WKWebView::nativeLoadHtml(content, baseUri) [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr)) {
        webkit_web_view_load_html(
            wkWebView->m_webView, JNI::String(content).getContent().get(), JNI::String(baseUri).getContent().get());
    }
}

jdouble JNIWKWebViewCache::nativeGetEstimatedLoadProgress(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr)) {
        return webkit_web_view_get_estimated_load_progress(wkWebView->m_webView);
    }
    return 0;
}

void JNIWKWebViewCache::nativeGoBack(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeGoBack() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_go_back(wkWebView->m_webView);
}

void JNIWKWebViewCache::nativeGoForward(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeGoForward() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_go_forward(wkWebView->m_webView);
}

void JNIWKWebViewCache::nativeStopLoading(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeStopLoading() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_stop_loading(wkWebView->m_webView);
}

void JNIWKWebViewCache::nativeReload(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeReload() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_reload(wkWebView->m_webView);
}

void JNIWKWebViewCache::nativeSurfaceCreated(
    JNIEnv* env, jobject /*obj*/, jlong wkWebViewPtr, JNISurface surface) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceCreated(%p) [tid %d]", surface, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->m_renderer)
        wkWebView->m_renderer->onSurfaceCreated(ANativeWindow_fromSurface(env, surface));
}

void JNIWKWebViewCache::nativeSurfaceChanged(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint format, jint width, jint height) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceChanged(%d, %d, %d) [tid %d]", format, width, height, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_viewBackend != nullptr) && wkWebView->m_renderer) {
        const uint32_t physicalWidth = std::max(0, width);
        const uint32_t physicalHeight = std::max(0, height);
        const uint32_t logicalWidth = std::floor(static_cast<float>(physicalWidth) / wkWebView->deviceScale());
        const uint32_t logicalHeight = std::floor(static_cast<float>(physicalHeight) / wkWebView->deviceScale());

        wpe_view_backend_dispatch_set_size(
            WPEAndroidViewBackend_getWPEViewBackend(wkWebView->m_viewBackend), logicalWidth, logicalHeight);
        wkWebView->m_renderer->onSurfaceChanged(format, physicalWidth, physicalHeight);
    }
}

void JNIWKWebViewCache::nativeSurfaceRedrawNeeded(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceRedrawNeeded() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->m_renderer)
        wkWebView->m_renderer->onSurfaceRedrawNeeded();
}

void JNIWKWebViewCache::nativeSurfaceDestroyed(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceDestroyed() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->m_renderer)
        wkWebView->m_renderer->onSurfaceDestroyed();
}

void JNIWKWebViewCache::nativeSetZoomLevel(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jdouble zoomLevel) noexcept
{
    Logging::logDebug("WKWebView::nativeSetZoomLevel(%f) [tid %d]", zoomLevel, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_set_zoom_level(wkWebView->m_webView, zoomLevel);
}

void JNIWKWebViewCache::nativeOnTouchEvent(JNIEnv* env, jobject /*obj*/, jlong wkWebViewPtr, jlong time, jint type,
    jint pointerCount, jintArray ids, jfloatArray xs, jfloatArray ys) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_viewBackend != nullptr)) {
        wpe_input_touch_event_type touchEventType = wpe_input_touch_event_type_null;
        switch (type) {
        case 0:
            touchEventType = wpe_input_touch_event_type_down;
            break;

        case 1:
            touchEventType = wpe_input_touch_event_type_motion;
            break;

        case 2:
            touchEventType = wpe_input_touch_event_type_up;
            break;

        default:
            break;
        }

        std::vector<jint> idsVector(pointerCount);
        std::vector<jfloat> xsVector(pointerCount);
        std::vector<jfloat> ysVector(pointerCount);
        env->GetIntArrayRegion(ids, 0, pointerCount, idsVector.data());
        env->GetFloatArrayRegion(xs, 0, pointerCount, xsVector.data());
        env->GetFloatArrayRegion(ys, 0, pointerCount, ysVector.data());

        auto* touchPoints = new wpe_input_touch_event_raw[pointerCount];
        for (int i = 0; i < pointerCount; ++i) {
            touchPoints[i].type = touchEventType;
            touchPoints[i].time = static_cast<uint32_t>(time);
            touchPoints[i].id = idsVector[i];
            touchPoints[i].x = static_cast<int32_t>(std::round(xsVector[i]));
            touchPoints[i].y = static_cast<int32_t>(std::round(ysVector[i]));
        }

        wpe_input_touch_event touchEvent {
            .touchpoints = touchPoints,
            .touchpoints_length = static_cast<uint64_t>(pointerCount),
            .type = touchEventType,
            .id = touchPoints[0].id, // Use the first touchpoint's ID
            .time = static_cast<uint32_t>(time),
            .modifiers = 0, // Set modifiers if any
        };

        wpe_view_backend_dispatch_touch_event(
            WPEAndroidViewBackend_getWPEViewBackend(wkWebView->m_viewBackend), &touchEvent);

        delete[] touchPoints;
    }
}

void JNIWKWebViewCache::nativeSetInputMethodContent(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint unicodeChar) noexcept
{
    Logging::logDebug("WKWebView::nativeSetInputMethodContent(0x%08X) [tid %d]", unicodeChar, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->m_renderer) {
        static constexpr size_t GUNICHAR_UTF8_BUFFER_SIZE = 8;
        char utf8Content[GUNICHAR_UTF8_BUFFER_SIZE] = {};
        g_unichar_to_utf8(static_cast<gunichar>(unicodeChar), utf8Content);
        wkWebView->m_inputMethodContext.setContent(utf8Content);
    }
}

void JNIWKWebViewCache::nativeDeleteInputMethodContent(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint offset) noexcept
{
    Logging::logDebug("WKWebView::nativeDeleteInputMethodContent(%d) [tid %d]", offset, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->m_renderer)
        wkWebView->m_inputMethodContext.deleteContent(offset);
}

void JNIWKWebViewCache::nativeRequestExitFullscreenMode(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeRequestExitFullscreenMode() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_viewBackend != nullptr)) {
        wpe_view_backend_dispatch_request_exit_fullscreen(
            WPEAndroidViewBackend_getWPEViewBackend(wkWebView->m_viewBackend));
    }
}

void JNIWKWebViewCache::nativeEvaluateJavascript(
    JNIEnv* env, jobject /*obj*/, jlong wkWebViewPtr, jstring script, JNIWKCallback callback) noexcept
{
    Logging::logDebug("WKWebView::nativeEvaluateJavascript() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        webkit_web_view_evaluate_javascript(wkWebView->webView(), JNI::String(script).getContent().get(), -1, nullptr,
            nullptr, nullptr,
            callback != nullptr ? reinterpret_cast<GAsyncReadyCallback>(onEvaluateJavascriptReady) : nullptr,
            env->NewGlobalRef(callback));
    }
}

void JNIWKWebViewCache::nativeScriptDialogClose(JNIEnv* /*env*/, jobject /*obj*/, jlong dialogPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeScriptDialogClose() [tid %d]", gettid());
    auto* dialog = reinterpret_cast<WebKitScriptDialog*>(dialogPtr); // NOLINT(performance-no-int-to-ptr)
    webkit_script_dialog_unref(dialog); // Will call webkit_script_dialog_close if ref count is zero
}

void JNIWKWebViewCache::nativeScriptDialogConfirm(
    JNIEnv* /*env*/, jobject /*obj*/, jlong dialogPtr, jboolean confirm, jstring text) noexcept
{
    Logging::logDebug("WKWebView::nativeScriptDialogConfirm() [tid %d]", gettid());
    auto* dialog = reinterpret_cast<WebKitScriptDialog*>(dialogPtr); // NOLINT(performance-no-int-to-ptr)
    if (webkit_script_dialog_get_dialog_type(dialog) == WEBKIT_SCRIPT_DIALOG_PROMPT && text != nullptr)
        webkit_script_dialog_prompt_set_text(dialog, JNI::String(text).getContent().get());
    webkit_script_dialog_confirm_set_confirmed(dialog, static_cast<gboolean>(confirm));
}

void JNIWKWebViewCache::nativeSetTLSErrorsPolicy(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint policy) noexcept
{
    // FIXME: normally this method should be in the WKNetworkSession class as
    //        it is related to the network session associated with this WebView.
    //        Unfortunately, the network session wrapped in the associated
    //        WKNetworkSession instance is sometimes different from the result
    //        of `webkit_web_view_get_network_session(wkWebView->webView())`.
    //        So this method has been put here until we fix the wrapping class.

    Logging::logDebug("WKWebView::nativeSetTLSErrorsPolicy(%d) [tid %d]", policy, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        auto* networkSession = webkit_web_view_get_network_session(wkWebView->webView());
        webkit_network_session_set_tls_errors_policy(networkSession, static_cast<WebKitTLSErrorsPolicy>(policy));
    }
}

/***********************************************************************************************************************
 * Native WKWebView class implementation
 **********************************************************************************************************************/

void WKWebView::configureJNIMappings() { getJNIPageCache(); }

WKWebView::WKWebView(JNIEnv* env, JNIWKWebView jniWKWebView, WKWebContext* wkWebContext, int width, int height,
    float deviceScale, bool headless)
    : m_webViewJavaInstance(JNI::createTypedProtectedRef(env, jniWKWebView, true))
    , m_inputMethodContext(this)
    , m_isHeadless(headless)
    , m_deviceScale(deviceScale)
{
    const uint32_t uWidth = std::max(0, width);
    const uint32_t uHeight = std::max(0, height);

    m_viewBackend = WPEAndroidViewBackend_create(uWidth, uHeight);

    if (!m_isHeadless)
        m_renderer = std::make_shared<RendererSurfaceControl>(m_viewBackend, uWidth, uHeight);

    WPEViewBackend* wpeBackend = WPEAndroidViewBackend_getWPEViewBackend(m_viewBackend);
    WebKitWebViewBackend* viewBackend = webkit_web_view_backend_new(
        wpeBackend, reinterpret_cast<GDestroyNotify>(WPEAndroidViewBackend_destroy), m_viewBackend);

    gboolean const automationMode = wkWebContext->automationMode() ? TRUE : FALSE;

    m_webView = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "backend", viewBackend, "web-context",
        wkWebContext->webContext(), "is-controlled-by-automation", automationMode, nullptr));
    webkit_web_view_set_input_method_context(m_webView, m_inputMethodContext.webKitInputMethodContext());

    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "close", G_CALLBACK(JNIWKWebViewCache::onClose), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "load-changed", G_CALLBACK(JNIWKWebViewCache::onLoadChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::estimated-load-progress", G_CALLBACK(JNIWKWebViewCache::onEstimatedLoadProgress), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::uri", G_CALLBACK(JNIWKWebViewCache::onUriChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::title", G_CALLBACK(JNIWKWebViewCache::onTitleChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "script-dialog", G_CALLBACK(JNIWKWebViewCache::onScriptDialog), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "decide-policy", G_CALLBACK(JNIWKWebViewCache::onDecidePolicy), this));

    wpe_view_backend_set_fullscreen_handler(wpeBackend,
        reinterpret_cast<wpe_view_backend_fullscreen_handler>(JNIWKWebViewCache::onFullscreenRequest), this);

    WPEAndroidViewBackend_setCommitBufferHandler(m_viewBackend, this, handleCommitBuffer);

    wpe_view_backend_dispatch_set_device_scale_factor(
        WPEAndroidViewBackend_getWPEViewBackend(m_viewBackend), deviceScale);
}

void WKWebView::close() noexcept
{
    if (m_webView != nullptr) {
        // Ensure that renderer is destroyed first so that all pending commits will be cleared before page is gone
        m_renderer.reset();

        webkit_web_view_set_input_method_context(m_webView, nullptr);

        for (auto& handler : m_signalHandlers)
            g_signal_handler_disconnect(m_webView, handler);
        m_signalHandlers.clear();

        webkit_web_view_try_close(m_webView);

        m_viewBackend = nullptr;
        g_object_unref(m_webView);
        m_webView = nullptr;
    }
}

void WKWebView::onInputMethodContextIn() noexcept
{
    getJNIPageCache().onInputMethodContextIn(m_webViewJavaInstance.get());
}

void WKWebView::onInputMethodContextOut() noexcept
{
    getJNIPageCache().onInputMethodContextOut(m_webViewJavaInstance.get());
}

void WKWebView::commitBuffer(WPEAndroidBuffer* buffer, int fenceFD) noexcept // NOLINT(bugprone-exception-escape)
{
    auto scopedFenceFD = std::make_shared<ScopedFD>(fenceFD);
    if (m_viewBackend != nullptr) {
        if (m_isHeadless) {
            WPEAndroidViewBackend_dispatchReleaseBuffer(m_viewBackend, buffer);
            WPEAndroidViewBackend_dispatchFrameComplete(m_viewBackend);
        } else if (m_renderer) {
            auto scopedBuffer = std::make_shared<ScopedWPEAndroidBuffer>(buffer);

            if (m_isFullscreenRequested && (scopedBuffer->width() == m_renderer->width())
                && (scopedBuffer->height() == m_renderer->height())) {
                Logging::logDebug("Fullscreen ready");
                m_isFullscreenRequested = false;
                wpe_view_backend_dispatch_did_enter_fullscreen(WPEAndroidViewBackend_getWPEViewBackend(m_viewBackend));
            }

            m_renderer->commitBuffer(scopedBuffer, scopedFenceFD);
        }
    }
}
