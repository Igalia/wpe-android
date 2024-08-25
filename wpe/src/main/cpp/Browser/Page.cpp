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

#include "Page.h"

#include "Browser.h"
#include "Logging.h"
#include "RendererSurfaceControl.h"
#include "WKCallback.h"
#include "WKWebContext.h"

#include <android/native_window_jni.h>
#include <libsoup/soup.h>
#include <unistd.h>
#include <wpe-android/view-backend.h>

namespace {

void handleCommitBuffer(void* context, WPEAndroidBuffer* buffer, int fenceID)
{
    auto* page = static_cast<Page*>(context);
    page->commitBuffer(buffer, fenceID);
}

const int httpErrorsStart = 400;

} // namespace

/***********************************************************************************************************************
 * JNI mapping with Java Page class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNISurface, "android/view/Surface");

class JNIPageCache;
const JNIPageCache& getJNIPageCache();

class JNIPageCache final : public JNI::TypedClass<JNIPage> {
public:
    JNIPageCache();

    static void onClose(Page* page, WebKitWebView* /*webView*/) noexcept
    {
        callJavaMethod(getJNIPageCache().m_onClose, page->m_pageJavaInstance.get());
    }

    static void onLoadChanged(Page* page, WebKitLoadEvent loadEvent, WebKitWebView* /*webView*/) noexcept
    {
        Logging::logDebug("Page::onLoadChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onLoadChanged, page->m_pageJavaInstance.get(), static_cast<int>(loadEvent));
    }

    static void onLoadProgress(Page* page, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("Page::onLoadProgress() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onLoadProgress, page->m_pageJavaInstance.get(),
            static_cast<jdouble>(webkit_web_view_get_estimated_load_progress(webView)));
    }

    static void onUriChanged(Page* page, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        auto jUri = JNI::String(webkit_web_view_get_uri(webView));
        callJavaMethod(getJNIPageCache().m_onUriChanged, page->m_pageJavaInstance.get(), static_cast<jstring>(jUri));
    }

    static void onTitleChanged(Page* page, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        auto jTitle = JNI::String(webkit_web_view_get_title(webView));
        callJavaMethod(getJNIPageCache().m_onTitleChanged, page->m_pageJavaInstance.get(), static_cast<jstring>(jTitle),
            static_cast<jboolean>(webkit_web_view_can_go_back(webView)),
            static_cast<jboolean>(webkit_web_view_can_go_forward(webView)));
    }

    static gboolean onScriptDialog(Page* page, WebKitScriptDialog* dialog, WebKitWebView* webView)
    {
        auto dialogPtr = reinterpret_cast<jlong>(webkit_script_dialog_ref(dialog));
        auto jActiveURL = JNI::String(webkit_web_view_get_uri(webView));
        auto jMessage = JNI::String(webkit_script_dialog_get_message(dialog));
        JNI::String const jDefaultText = webkit_script_dialog_get_dialog_type(dialog) == WEBKIT_SCRIPT_DIALOG_PROMPT
            ? JNI::String(webkit_script_dialog_prompt_get_default_text(dialog))
            : "";
        callJavaMethod(getJNIPageCache().m_onScriptDialog, page->m_pageJavaInstance.get(), dialogPtr,
            webkit_script_dialog_get_dialog_type(dialog), static_cast<jstring>(jActiveURL),
            static_cast<jstring>(jMessage), static_cast<jstring>(jDefaultText));

        return TRUE;
    }

    static gboolean onDecidePolicy(
        Page* page, WebKitPolicyDecision* decision, WebKitPolicyDecisionType decisionType, WebKitWebView* /*webView*/)
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
            callJavaMethod(getJNIPageCache().m_onReceivedHttpError, page->m_pageJavaInstance.get(),
                static_cast<jstring>(jRequestUri), static_cast<jstring>(jRequestMethod),
                static_cast<jstringArray>(jRequestHeaders), static_cast<jstring>(jResponseMimeType), responseStatusCode,
                static_cast<jstringArray>(jResponseHeaders));
        }

        return FALSE;
    }

    static bool onFullscreenRequest(Page* page, bool fullscreen) noexcept
    {
        if (page->m_viewBackend != nullptr) {
            page->m_isFullscreenRequested = fullscreen;
            if (fullscreen) {
                callJavaMethod(getJNIPageCache().m_onEnterFullscreenMode, page->m_pageJavaInstance.get());
            } else {
                callJavaMethod(getJNIPageCache().m_onExitFullscreenMode, page->m_pageJavaInstance.get());
                wpe_view_backend_dispatch_did_exit_fullscreen(
                    WPEAndroidViewBackend_getWPEViewBackend(page->m_viewBackend));
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
            Logging::logError("Cannot send page event to Java runtime (%s)", ex.what());
        }
    }

    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void()> m_onClose;
    const JNI::Method<void(jint)> m_onLoadChanged;
    const JNI::Method<void(jdouble)> m_onLoadProgress;
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
    static void nativeClose(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeDestroy(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeLoadUrl(JNIEnv* env, jobject obj, jlong pagePtr, jstring url) noexcept;
    static void nativeLoadHtml(JNIEnv* env, jobject obj, jlong pagePtr, jstring content, jstring baseUri) noexcept;
    static void nativeGoBack(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeGoForward(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeStopLoading(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeReload(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeSurfaceCreated(JNIEnv* env, jobject obj, jlong pagePtr, JNISurface surface) noexcept;
    static void nativeSurfaceDestroyed(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeSurfaceChanged(
        JNIEnv* env, jobject obj, jlong pagePtr, jint format, jint width, jint height) noexcept;
    static void nativeSurfaceRedrawNeeded(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeSetZoomLevel(JNIEnv* env, jobject obj, jlong pagePtr, jdouble zoomLevel) noexcept;
    static void nativeOnTouchEvent(
        JNIEnv* env, jobject obj, jlong pagePtr, jlong time, jint type, jfloat xCoord, jfloat yCoord) noexcept;
    static void nativeSetInputMethodContent(JNIEnv* env, jobject obj, jlong pagePtr, jint unicodeChar) noexcept;
    static void nativeDeleteInputMethodContent(JNIEnv* env, jobject obj, jlong pagePtr, jint offset) noexcept;
    static void nativeRequestExitFullscreenMode(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeEvaluateJavascript(
        JNIEnv* env, jobject obj, jlong pagePtr, jstring script, JNIWKCallback callback) noexcept;
    static void nativeScriptDialogClose(JNIEnv* env, jobject obj, jlong dialogPtr) noexcept;
    static void nativeScriptDialogConfirm(
        JNIEnv* env, jobject obj, jlong dialogPtr, jboolean confirm, jstring text) noexcept;
};

const JNIPageCache& getJNIPageCache()
{
    static const JNIPageCache s_singleton;
    return s_singleton;
}

JNIPageCache::JNIPageCache()
    : JNI::TypedClass<JNIPage>(true)
    , m_onClose(getMethod<void()>("onClose"))
    , m_onLoadChanged(getMethod<void(jint)>("onLoadChanged"))
    , m_onLoadProgress(getMethod<void(jdouble)>("onLoadProgress"))
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
        JNI::NativeMethod<jlong(jlong, jint, jint, jfloat, jboolean)>("nativeInit", JNIPageCache::nativeInit),
        JNI::NativeMethod<void(jlong)>("nativeClose", JNIPageCache::nativeClose),
        JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIPageCache::nativeDestroy),
        JNI::NativeMethod<void(jlong, jstring)>("nativeLoadUrl", JNIPageCache::nativeLoadUrl),
        JNI::NativeMethod<void(jlong, jstring, jstring)>("nativeLoadHtml", JNIPageCache::nativeLoadHtml),
        JNI::NativeMethod<void(jlong)>("nativeGoBack", JNIPageCache::nativeGoBack),
        JNI::NativeMethod<void(jlong)>("nativeGoForward", JNIPageCache::nativeGoForward),
        JNI::NativeMethod<void(jlong)>("nativeStopLoading", JNIPageCache::nativeStopLoading),
        JNI::NativeMethod<void(jlong)>("nativeReload", JNIPageCache::nativeReload),
        JNI::NativeMethod<void(jlong, JNISurface)>("nativeSurfaceCreated", JNIPageCache::nativeSurfaceCreated),
        JNI::NativeMethod<void(jlong, jint, jint, jint)>("nativeSurfaceChanged", JNIPageCache::nativeSurfaceChanged),
        JNI::NativeMethod<void(jlong)>("nativeSurfaceRedrawNeeded", JNIPageCache::nativeSurfaceRedrawNeeded),
        JNI::NativeMethod<void(jlong)>("nativeSurfaceDestroyed", JNIPageCache::nativeSurfaceDestroyed),
        JNI::NativeMethod<void(jlong, jdouble)>("nativeSetZoomLevel", JNIPageCache::nativeSetZoomLevel),
        JNI::NativeMethod<void(jlong, jlong, jint, jfloat, jfloat)>(
            "nativeOnTouchEvent", JNIPageCache::nativeOnTouchEvent),
        JNI::NativeMethod<void(jlong, jint)>("nativeSetInputMethodContent", JNIPageCache::nativeSetInputMethodContent),
        JNI::NativeMethod<void(jlong, jint)>(
            "nativeDeleteInputMethodContent", JNIPageCache::nativeDeleteInputMethodContent),
        JNI::NativeMethod<void(jlong)>(
            "nativeRequestExitFullscreenMode", JNIPageCache::nativeRequestExitFullscreenMode),
        JNI::NativeMethod<void(jlong, jstring, JNIWKCallback)>(
            "nativeEvaluateJavascript", JNIPageCache::nativeEvaluateJavascript),
        JNI::NativeMethod<void(jlong)>("nativeScriptDialogClose", JNIPageCache::nativeScriptDialogClose),
        JNI::NativeMethod<void(jlong, jboolean, jstring)>(
            "nativeScriptDialogConfirm", JNIPageCache::nativeScriptDialogConfirm));
}

jlong JNIPageCache::nativeInit(
    JNIEnv* env, jobject obj, jlong wkWebContextPtr, jint width, jint height, jfloat deviceScale, jboolean headless)
{
    Logging::logDebug("Page::nativeInit(%p, %d, %d, [density %f] [tid %d]", obj, width, height, deviceScale, gettid());
    auto* wkWebContext = reinterpret_cast<WKWebContext*>(wkWebContextPtr); // NOLINT(performance-no-int-to-ptr)
    // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    Page* page = new Page(
        env, reinterpret_cast<JNIPage>(obj), wkWebContext, width, height, deviceScale, static_cast<bool>(headless));
    return reinterpret_cast<jlong>(page);
}

void JNIPageCache::nativeClose(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeClose() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if (page != nullptr)
        page->close();
}

void JNIPageCache::nativeDestroy(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeDestroy() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    delete page; // NOLINT(cppcoreguidelines-owning-memory)
}

void JNIPageCache::nativeLoadUrl(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jstring url) noexcept
{
    Logging::logDebug("Page::nativeLoadUrl('...') [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr))
        webkit_web_view_load_uri(page->m_webView, JNI::String(url).getContent().get());
}

void JNIPageCache::nativeLoadHtml(
    JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jstring content, jstring baseUri) noexcept
{
    Logging::logDebug("Page::nativeLoadHtml(content, baseUri) [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr)) {
        webkit_web_view_load_html(
            page->m_webView, JNI::String(content).getContent().get(), JNI::String(baseUri).getContent().get());
    }
}

void JNIPageCache::nativeGoBack(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeGoBack() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr))
        webkit_web_view_go_back(page->m_webView);
}

void JNIPageCache::nativeGoForward(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeGoForward() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr))
        webkit_web_view_go_forward(page->m_webView);
}

void JNIPageCache::nativeStopLoading(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeStopLoading() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr))
        webkit_web_view_stop_loading(page->m_webView);
}

void JNIPageCache::nativeReload(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeReload() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr))
        webkit_web_view_reload(page->m_webView);
}

void JNIPageCache::nativeSurfaceCreated(JNIEnv* env, jobject /*obj*/, jlong pagePtr, JNISurface surface) noexcept
{
    Logging::logDebug("Page::nativeSurfaceCreated(%p) [tid %d]", surface, gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && page->m_renderer)
        page->m_renderer->onSurfaceCreated(ANativeWindow_fromSurface(env, surface));
}

void JNIPageCache::nativeSurfaceChanged(
    JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jint format, jint width, jint height) noexcept
{
    Logging::logDebug("Page::nativeSurfaceChanged(%d, %d, %d) [tid %d]", format, width, height, gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_viewBackend != nullptr) && page->m_renderer) {
        const uint32_t physicalWidth = std::max(0, width);
        const uint32_t physicalHeight = std::max(0, height);
        const uint32_t logicalWidth = std::floor(static_cast<float>(physicalWidth) / page->deviceScale());
        const uint32_t logicalHeight = std::floor(static_cast<float>(physicalHeight) / page->deviceScale());

        wpe_view_backend_dispatch_set_size(
            WPEAndroidViewBackend_getWPEViewBackend(page->m_viewBackend), logicalWidth, logicalHeight);
        page->m_renderer->onSurfaceChanged(format, physicalWidth, physicalHeight);
    }
}

void JNIPageCache::nativeSurfaceRedrawNeeded(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeSurfaceRedrawNeeded() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && page->m_renderer)
        page->m_renderer->onSurfaceRedrawNeeded();
}

void JNIPageCache::nativeSurfaceDestroyed(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeSurfaceDestroyed() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && page->m_renderer)
        page->m_renderer->onSurfaceDestroyed();
}

void JNIPageCache::nativeSetZoomLevel(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jdouble zoomLevel) noexcept
{
    Logging::logDebug("Page::nativeSetZoomLevel(%f) [tid %d]", zoomLevel, gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_webView != nullptr))
        webkit_web_view_set_zoom_level(page->m_webView, zoomLevel);
}

void JNIPageCache::nativeOnTouchEvent(
    JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jlong time, jint type, jfloat xCoord, jfloat yCoord) noexcept
{
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_viewBackend != nullptr)) {
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

        const wpe_input_touch_event_raw touchEventRaw = {.type = touchEventType,
            .time = static_cast<uint32_t>(time),
            .id = 0,
            .x = static_cast<int32_t>(xCoord),
            .y = static_cast<int32_t>(yCoord)};
        wpe_input_touch_event touchEvent = {.touchpoints = &touchEventRaw,
            .touchpoints_length = 1,
            .type = touchEventType,
            .id = 0,
            .time = touchEventRaw.time,
            .modifiers = 0};

        wpe_view_backend_dispatch_touch_event(
            WPEAndroidViewBackend_getWPEViewBackend(page->m_viewBackend), &touchEvent);
    }
}

void JNIPageCache::nativeSetInputMethodContent(
    JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jint unicodeChar) noexcept
{
    Logging::logDebug("Page::nativeSetInputMethodContent(0x%08X) [tid %d]", unicodeChar, gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && page->m_renderer) {
        static constexpr size_t GUNICHAR_UTF8_BUFFER_SIZE = 8;
        char utf8Content[GUNICHAR_UTF8_BUFFER_SIZE] = {};
        g_unichar_to_utf8(static_cast<gunichar>(unicodeChar), utf8Content);
        page->m_inputMethodContext.setContent(utf8Content);
    }
}

void JNIPageCache::nativeDeleteInputMethodContent(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr, jint offset) noexcept
{
    Logging::logDebug("Page::nativeDeleteInputMethodContent(%d) [tid %d]", offset, gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && page->m_renderer)
        page->m_inputMethodContext.deleteContent(offset);
}

void JNIPageCache::nativeRequestExitFullscreenMode(JNIEnv* /*env*/, jobject /*obj*/, jlong pagePtr) noexcept
{
    Logging::logDebug("Page::nativeRequestExitFullscreenMode() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_viewBackend != nullptr)) {
        wpe_view_backend_dispatch_request_exit_fullscreen(WPEAndroidViewBackend_getWPEViewBackend(page->m_viewBackend));
    }
}

void JNIPageCache::nativeEvaluateJavascript(
    JNIEnv* env, jobject /*obj*/, jlong pagePtr, jstring script, JNIWKCallback callback) noexcept
{
    Logging::logDebug("Page::nativeEvaluateJavascript() [tid %d]", gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if (page != nullptr) {
        webkit_web_view_evaluate_javascript(page->webView(), JNI::String(script).getContent().get(), -1, nullptr,
            nullptr, nullptr,
            callback != nullptr ? reinterpret_cast<GAsyncReadyCallback>(onEvaluateJavascriptReady) : nullptr,
            env->NewGlobalRef(callback));
    }
}

void JNIPageCache::nativeScriptDialogClose(JNIEnv* /*env*/, jobject /*obj*/, jlong dialogPtr) noexcept
{
    Logging::logDebug("Page::nativeScriptDialogClose() [tid %d]", gettid());
    auto* dialog = reinterpret_cast<WebKitScriptDialog*>(dialogPtr); // NOLINT(performance-no-int-to-ptr)
    webkit_script_dialog_unref(dialog); // Will call webkit_script_dialog_close if ref count is zero
}

void JNIPageCache::nativeScriptDialogConfirm(
    JNIEnv* /*env*/, jobject /*obj*/, jlong dialogPtr, jboolean confirm, jstring text) noexcept
{
    Logging::logDebug("Page::nativeScriptDialogConfirm() [tid %d]", gettid());
    auto* dialog = reinterpret_cast<WebKitScriptDialog*>(dialogPtr); // NOLINT(performance-no-int-to-ptr)
    if (webkit_script_dialog_get_dialog_type(dialog) == WEBKIT_SCRIPT_DIALOG_PROMPT && text != nullptr)
        webkit_script_dialog_prompt_set_text(dialog, JNI::String(text).getContent().get());
    webkit_script_dialog_confirm_set_confirmed(dialog, static_cast<gboolean>(confirm));
}

/***********************************************************************************************************************
 * Native Page class implementation
 **********************************************************************************************************************/

void Page::configureJNIMappings() { getJNIPageCache(); }

Page::Page(
    JNIEnv* env, JNIPage jniPage, WKWebContext* wkWebContext, int width, int height, float deviceScale, bool headless)
    : m_pageJavaInstance(JNI::createTypedProtectedRef(env, jniPage, true))
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

    m_signalHandlers.push_back(g_signal_connect_swapped(m_webView, "close", G_CALLBACK(JNIPageCache::onClose), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "load-changed", G_CALLBACK(JNIPageCache::onLoadChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::estimated-load-progress", G_CALLBACK(JNIPageCache::onLoadProgress), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::uri", G_CALLBACK(JNIPageCache::onUriChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::title", G_CALLBACK(JNIPageCache::onTitleChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "script-dialog", G_CALLBACK(JNIPageCache::onScriptDialog), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "decide-policy", G_CALLBACK(JNIPageCache::onDecidePolicy), this));

    wpe_view_backend_set_fullscreen_handler(
        wpeBackend, reinterpret_cast<wpe_view_backend_fullscreen_handler>(JNIPageCache::onFullscreenRequest), this);

    WPEAndroidViewBackend_setCommitBufferHandler(m_viewBackend, this, handleCommitBuffer);

    wpe_view_backend_dispatch_set_device_scale_factor(
        WPEAndroidViewBackend_getWPEViewBackend(m_viewBackend), deviceScale);
}

void Page::close() noexcept
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

void Page::onInputMethodContextIn() noexcept { getJNIPageCache().onInputMethodContextIn(m_pageJavaInstance.get()); }

void Page::onInputMethodContextOut() noexcept { getJNIPageCache().onInputMethodContextOut(m_pageJavaInstance.get()); }

void Page::commitBuffer(WPEAndroidBuffer* buffer, int fenceFD) noexcept // NOLINT(bugprone-exception-escape)
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
