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
#include "WPEDisplayAndroid.h"
#include "WPEViewAndroid.h"

#include <android/native_window_jni.h>
#include <cmath>
#include <libsoup/soup.h>
#include <unistd.h>

namespace {

const int httpErrorsStart = 400;

inline float safeDeviceDensity(float density) noexcept { return (density > 0.0f) ? density : 1.0f; }

class SslErrorHandler final {
public:
    static SslErrorHandler* createHandler(
        WebKitWebView* webView, GTlsCertificate* certificate, const char* failingURI, gchar** certificatePEM) noexcept
    {
        if (!webView || !certificate || !failingURI || !*failingURI || !certificatePEM) {
            return nullptr;
        }

        gchar* host = nullptr;
        // NOLINTBEGIN(clang-analyzer-optin.core.EnumCastOutOfRange)
        GUri* uri
            = g_uri_parse(failingURI, static_cast<GUriFlags>(G_URI_FLAGS_PARSE_RELAXED | G_URI_FLAGS_ENCODED), nullptr);
        // NOLINTEND(clang-analyzer-optin.core.EnumCastOutOfRange)
        if (uri) {
            const char* str = g_uri_get_host(uri);
            if (str) {
                host = g_strdup(str);
            }
            g_uri_unref(uri);
        }
        if (!host) {
            return nullptr;
        }

        *certificatePEM = nullptr;
        g_object_get(certificate, "certificate-pem", certificatePEM, nullptr);
        if (!*certificatePEM) {
            g_free(host);
            return nullptr;
        }

        return new SslErrorHandler(webView, certificate, g_strdup(failingURI), host);
    }

    ~SslErrorHandler()
    {
        handlingFinished();
        g_weak_ref_clear(&m_webViewWeakRef);
    }

    SslErrorHandler(SslErrorHandler&&) = delete;
    SslErrorHandler& operator=(SslErrorHandler&&) = delete;
    SslErrorHandler(const SslErrorHandler&) = delete;
    SslErrorHandler& operator=(const SslErrorHandler&) = delete;

    void acceptCertificate() noexcept
    {
        auto* webView = static_cast<WebKitWebView*>(g_weak_ref_get(&m_webViewWeakRef));
        if (webView) {
            auto* networkSession = webkit_web_view_get_network_session(webView);
            if (networkSession) {
                webkit_network_session_allow_tls_certificate_for_host(networkSession, m_certificate, m_host);
                g_object_unref(networkSession);

                webkit_web_view_load_uri(webView, m_failingURI);
            }
            g_object_unref(webView);
        }
        handlingFinished();
    }

    void rejectCertificate() noexcept { handlingFinished(); }

private:
    GWeakRef m_webViewWeakRef = {};
    GTlsCertificate* m_certificate = nullptr;
    gchar* m_failingURI = nullptr;
    gchar* m_host = nullptr;

    SslErrorHandler(WebKitWebView* webView, GTlsCertificate* certificate, gchar* failingURI, gchar* host) noexcept
        : m_failingURI(failingURI)
        , m_host(host)
    {
        g_weak_ref_init(&m_webViewWeakRef, webView);
        m_certificate = g_object_ref(certificate);
    }

    void handlingFinished() noexcept
    {
        g_weak_ref_set(&m_webViewWeakRef, nullptr);
        g_clear_object(&m_certificate);
        g_clear_pointer(&m_failingURI, g_free);
        g_clear_pointer(&m_host, g_free);
    }
};

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

    static void onIsLoadingChanged(WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onIsLoadingChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onIsLoadingChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jboolean>(webkit_web_view_is_loading(webView)));
    }

    static gboolean onLoadFailed(WKWebView* wkWebView, WebKitLoadEvent loadEvent, const char* failingURI, GError* error,
        WebKitWebView* /*webView*/) noexcept
    {
        Logging::logDebug("WKWebView::onLoadFailed() [tid %d] uri=%s error=%s", gettid(), failingURI,
            error ? error->message : "null");

        auto jFailingUri = JNI::String(failingURI);
        auto jErrorDomain = JNI::String(error ? g_quark_to_string(error->domain) : "");
        auto jErrorMessage = JNI::String(error ? error->message : "");

        try {
            return static_cast<gboolean>(getJNIPageCache().m_onLoadFailed.invoke(wkWebView->m_webViewJavaInstance.get(),
                static_cast<jint>(loadEvent), static_cast<jstring>(jFailingUri),
                static_cast<jint>(error ? error->code : 0), static_cast<jstring>(jErrorDomain),
                static_cast<jstring>(jErrorMessage)));
        } catch (const std::exception& ex) {
            Logging::logError("Cannot call onLoadFailed (%s)", ex.what());
            return FALSE;
        }
    }

    static void onWebProcessTerminated(
        WKWebView* wkWebView, WebKitWebProcessTerminationReason reason, WebKitWebView* /*webView*/) noexcept
    {
        Logging::logDebug("WKWebView::onWebProcessTerminated(%d) [tid %d]", static_cast<int>(reason), gettid());
        callJavaMethod(getJNIPageCache().m_onWebProcessTerminated, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jint>(reason));
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

    static void onIsPlayingAudioChanged(WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onIsPlayingAudioChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onIsPlayingAudioChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jboolean>(webkit_web_view_is_playing_audio(webView)));
    }

    static void onIsMutedChanged(WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onIsMutedChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onIsMutedChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jboolean>(webkit_web_view_get_is_muted(webView)));
    }

    static void onCameraCaptureStateChanged(
        WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onCameraCaptureStateChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onCameraCaptureStateChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jint>(webkit_web_view_get_camera_capture_state(webView)));
    }

    static void onMicrophoneCaptureStateChanged(
        WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onMicrophoneCaptureStateChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onMicrophoneCaptureStateChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jint>(webkit_web_view_get_microphone_capture_state(webView)));
    }

    static void onDisplayCaptureStateChanged(
        WKWebView* wkWebView, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
        Logging::logDebug("WKWebView::onDisplayCaptureStateChanged() [tid %d]", gettid());
        callJavaMethod(getJNIPageCache().m_onDisplayCaptureStateChanged, wkWebView->m_webViewJavaInstance.get(),
            static_cast<jint>(webkit_web_view_get_display_capture_state(webView)));
    }

    static gboolean onScriptDialog(WKWebView* wkWebView, WebKitScriptDialog* dialog, WebKitWebView* webView) noexcept
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
        WebKitPolicyDecisionType decisionType, WebKitWebView* /*webView*/) noexcept
    {
        // Handle navigation policy decisions
        if (decisionType == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) {
            auto* navigationDecision = WEBKIT_NAVIGATION_POLICY_DECISION(decision);
            auto* navigationAction = webkit_navigation_policy_decision_get_navigation_action(navigationDecision);
            auto* uriRequest = webkit_navigation_action_get_request(navigationAction);
            const char* uri = webkit_uri_request_get_uri(uriRequest);

            if (uri != nullptr) {
                auto jUri = JNI::String(uri);
                auto isRedirect
                    = static_cast<jboolean>(webkit_navigation_action_is_redirect(navigationAction) ? TRUE : FALSE);
                auto isUserGesture
                    = static_cast<jboolean>(webkit_navigation_action_is_user_gesture(navigationAction) ? TRUE : FALSE);

                try {
                    if (getJNIPageCache().m_shouldOverrideUrlLoading.invoke(wkWebView->m_webViewJavaInstance.get(),
                            static_cast<jstring>(jUri), isRedirect, isUserGesture)) {
                        webkit_policy_decision_ignore(decision);
                        return TRUE;
                    }
                } catch (const std::exception& ex) {
                    Logging::logError("Cannot call shouldOverrideUrlLoading (%s)", ex.what());
                }
            }
            return FALSE;
        }

        // Handle response policy decisions (HTTP errors)
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

    static gboolean onReceivedSslError(WKWebView* wkWebView, char* failingURI, GTlsCertificate* certificate,
        GTlsCertificateFlags errorFlags, WebKitWebView* webView) noexcept
    {
        if (wkWebView->m_webView != webView) {
            return FALSE;
        }

        gchar* certificatePEM = nullptr;
        SslErrorHandler* handler = SslErrorHandler::createHandler(webView, certificate, failingURI, &certificatePEM);
        if (!handler) {
            return FALSE;
        }

        auto jFailingURI = JNI::String(failingURI);
        auto jCertificatePEM = JNI::String(certificatePEM);
        g_free(certificatePEM);

        // Android SslError values are:
        // (https://developer.android.com/reference/android/net/http/SslError#constants_1)
        //
        // SSL_NOTYETVALID = 0x00
        // SSL_EXPIRED = 0x01
        // SSL_IDMISMATCH = 0x02
        // SSL_UNTRUSTED = 0x03
        // SSL_DATE_INVALID = 0x04
        // SSL_INVALID = 0x05
        int nbErrors = 0;
        int errors[5] = {0};
        if (errorFlags & (G_TLS_CERTIFICATE_UNKNOWN_CA | G_TLS_CERTIFICATE_REVOKED)) {
            errors[nbErrors++] = 0x03; // SSL_UNTRUSTED
        }
        if (errorFlags & G_TLS_CERTIFICATE_EXPIRED) {
            errors[nbErrors++] = 0x01; // SSL_EXPIRED
        }
        if (errorFlags & G_TLS_CERTIFICATE_NOT_ACTIVATED) {
            errors[nbErrors++] = 0x00; // SSL_NOTYETVALID
        }
        if (errorFlags & G_TLS_CERTIFICATE_BAD_IDENTITY) {
            errors[nbErrors++] = 0x02; // SSL_IDMISMATCH
        }
        if ((errorFlags & (G_TLS_CERTIFICATE_INSECURE | G_TLS_CERTIFICATE_GENERIC_ERROR)) || (nbErrors == 0)) {
            errors[nbErrors++] = 0x05; // SSL_INVALID
        }

        auto jErrorsArray = JNI::ScalarArray<jint>(nbErrors);
        nbErrors = 0;
        for (auto& arrayValue : jErrorsArray.getContent()) {
            arrayValue = errors[nbErrors++];
        }

        try {
            if (!getJNIPageCache().m_onReceivedSslError.invoke(wkWebView->m_webViewJavaInstance.get(),
                    static_cast<jstring>(jFailingURI), static_cast<jstring>(jCertificatePEM),
                    static_cast<jintArray>(jErrorsArray), reinterpret_cast<jlong>(handler))) {
                delete handler;
                return FALSE;
            }

            return TRUE;
        } catch (const std::exception& ex) {
            Logging::logError("Cannot send the [received SSL error] event to Java runtime (%s)", ex.what());
            delete handler;
            return FALSE;
        }
    }

    static bool onFullscreenRequest(WKWebView* wkWebView, bool fullscreen) noexcept
    {
        if (wkWebView->wpeView() != nullptr) {
            wkWebView->m_isFullscreenRequested = fullscreen;
            if (fullscreen) {
                callJavaMethod(getJNIPageCache().m_onEnterFullscreenMode, wkWebView->m_webViewJavaInstance.get());
                wpe_view_android_set_toplevel_state(wkWebView->wpeView(), WPE_TOPLEVEL_STATE_FULLSCREEN);
            } else {
                callJavaMethod(getJNIPageCache().m_onExitFullscreenMode, wkWebView->m_webViewJavaInstance.get());
                wpe_view_android_set_toplevel_state(wkWebView->wpeView(), static_cast<WPEToplevelState>(0));
            }
        }

        return true;
    }

    void onInputMethodContextIn(jobject obj) const noexcept { callJavaMethod(m_onInputMethodContextIn, obj); }

    void onInputMethodContextOut(jobject obj) const noexcept { callJavaMethod(m_onInputMethodContextOut, obj); }

    static void onEvaluateJavascriptReady(WebKitWebView* webView, GAsyncResult* result, JNIWKCallback callback) noexcept
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
    const JNI::Method<jboolean(jstring, jboolean, jboolean)> m_shouldOverrideUrlLoading;
    const JNI::Method<void(jboolean)> m_onIsLoadingChanged;
    const JNI::Method<jboolean(jint, jstring, jint, jstring, jstring)> m_onLoadFailed;
    const JNI::Method<void(jint)> m_onWebProcessTerminated;
    const JNI::Method<void(jdouble)> m_onEstimatedLoadProgress;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onTitleChanged;
    const JNI::Method<void(jboolean)> m_onIsPlayingAudioChanged;
    const JNI::Method<void(jboolean)> m_onIsMutedChanged;
    const JNI::Method<void(jint)> m_onCameraCaptureStateChanged;
    const JNI::Method<void(jint)> m_onMicrophoneCaptureStateChanged;
    const JNI::Method<void(jint)> m_onDisplayCaptureStateChanged;
    const JNI::Method<jboolean(jlong, jint, jstring, jstring, jstring)> m_onScriptDialog;
    const JNI::Method<void()> m_onInputMethodContextIn;
    const JNI::Method<void()> m_onInputMethodContextOut;
    const JNI::Method<jboolean(jstring, jstring, jintArray, jlong)> m_onReceivedSslError;
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
    static jboolean nativeIsMuted(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeSetMuted(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jboolean muted) noexcept;
    static void nativeOnTouchEvent(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jlong time, jint type,
        jint pointerCount, jintArray ids, jfloatArray xs, jfloatArray ys) noexcept;
    static void nativeSetInputMethodContent(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint unicodeChar) noexcept;
    static void nativeDeleteInputMethodContent(
        JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint offset, jint count) noexcept;
    static void nativeRequestExitFullscreenMode(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeEvaluateJavascript(
        JNIEnv* env, jobject obj, jlong wkWebViewPtr, jstring script, JNIWKCallback callback) noexcept;
    static void nativeScriptDialogClose(JNIEnv* env, jobject obj, jlong dialogPtr) noexcept;
    static void nativeScriptDialogConfirm(
        JNIEnv* env, jobject obj, jlong dialogPtr, jboolean confirm, jstring text) noexcept;
    static void nativeSetTLSErrorsPolicy(JNIEnv* env, jobject obj, jlong wkWebViewPtr, jint policy) noexcept;

    static void nativeTriggerSslErrorHandler(
        JNIEnv* env, jclass klass, jlong handlerPtr, jboolean acceptCertificate) noexcept;

    static void nativeFocusIn(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
    static void nativeFocusOut(JNIEnv* env, jobject obj, jlong wkWebViewPtr) noexcept;
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
    , m_shouldOverrideUrlLoading(getMethod<jboolean(jstring, jboolean, jboolean)>("shouldOverrideUrlLoading"))
    , m_onIsLoadingChanged(getMethod<void(jboolean)>("onIsLoadingChanged"))
    , m_onLoadFailed(getMethod<jboolean(jint, jstring, jint, jstring, jstring)>("onLoadFailed"))
    , m_onWebProcessTerminated(getMethod<void(jint)>("onWebProcessTerminated"))
    , m_onEstimatedLoadProgress(getMethod<void(jdouble)>("onEstimatedLoadProgress"))
    , m_onUriChanged(getMethod<void(jstring)>("onUriChanged"))
    , m_onTitleChanged(getMethod<void(jstring, jboolean, jboolean)>("onTitleChanged"))
    , m_onIsPlayingAudioChanged(getMethod<void(jboolean)>("onIsPlayingAudioChanged"))
    , m_onIsMutedChanged(getMethod<void(jboolean)>("onIsMutedChanged"))
    , m_onCameraCaptureStateChanged(getMethod<void(jint)>("onCameraCaptureStateChanged"))
    , m_onMicrophoneCaptureStateChanged(getMethod<void(jint)>("onMicrophoneCaptureStateChanged"))
    , m_onDisplayCaptureStateChanged(getMethod<void(jint)>("onDisplayCaptureStateChanged"))
    , m_onScriptDialog(getMethod<jboolean(jlong, jint, jstring, jstring, jstring)>("onScriptDialog"))
    , m_onInputMethodContextIn(getMethod<void()>("onInputMethodContextIn"))
    , m_onInputMethodContextOut(getMethod<void()>("onInputMethodContextOut"))
    , m_onReceivedSslError(getMethod<jboolean(jstring, jstring, jintArray, jlong)>("onReceivedSslError"))
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
        JNI::NativeMethod<jboolean(jlong)>("nativeIsMuted", JNIWKWebViewCache::nativeIsMuted),
        JNI::NativeMethod<void(jlong, jboolean)>("nativeSetMuted", JNIWKWebViewCache::nativeSetMuted),
        JNI::NativeMethod<void(jlong, jlong, jint, jint, jintArray, jfloatArray, jfloatArray)>(
            "nativeOnTouchEvent", JNIWKWebViewCache::nativeOnTouchEvent),
        JNI::NativeMethod<void(jlong, jint)>(
            "nativeSetInputMethodContent", JNIWKWebViewCache::nativeSetInputMethodContent),
        JNI::NativeMethod<void(jlong, jint, jint)>(
            "nativeDeleteInputMethodContent", JNIWKWebViewCache::nativeDeleteInputMethodContent),
        JNI::NativeMethod<void(jlong)>(
            "nativeRequestExitFullscreenMode", JNIWKWebViewCache::nativeRequestExitFullscreenMode),
        JNI::NativeMethod<void(jlong, jstring, JNIWKCallback)>(
            "nativeEvaluateJavascript", JNIWKWebViewCache::nativeEvaluateJavascript),
        JNI::NativeMethod<void(jlong)>("nativeScriptDialogClose", JNIWKWebViewCache::nativeScriptDialogClose),
        JNI::NativeMethod<void(jlong, jboolean, jstring)>(
            "nativeScriptDialogConfirm", JNIWKWebViewCache::nativeScriptDialogConfirm),
        JNI::NativeMethod<void(jlong, jint)>("nativeSetTLSErrorsPolicy", JNIWKWebViewCache::nativeSetTLSErrorsPolicy),
        JNI::StaticNativeMethod<void(jlong, jboolean)>(
            "nativeTriggerSslErrorHandler", JNIWKWebViewCache::nativeTriggerSslErrorHandler),
        JNI::NativeMethod<void(jlong)>("nativeFocusIn", JNIWKWebViewCache::nativeFocusIn),
        JNI::NativeMethod<void(jlong)>("nativeFocusOut", JNIWKWebViewCache::nativeFocusOut));
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
    if ((wkWebView != nullptr) && wkWebView->wpeView()) {
        wpe_view_android_on_surface_created(wkWebView->wpeView(), ANativeWindow_fromSurface(env, surface));

        wpe_view_set_visible(WPE_VIEW(wkWebView->wpeView()), TRUE);
        wpe_view_map(WPE_VIEW(wkWebView->wpeView()));
        wpe_view_android_set_toplevel_state(wkWebView->wpeView(), WPE_TOPLEVEL_STATE_ACTIVE);
    }
}

void JNIWKWebViewCache::nativeSurfaceChanged(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint format, jint width, jint height) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceChanged(%d, %d, %d) [tid %d]", format, width, height, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->wpeView()) {
        const uint32_t physicalWidth = std::max(0, width);
        const uint32_t physicalHeight = std::max(0, height);
        wpe_view_android_on_surface_changed(wkWebView->wpeView(), format, physicalWidth, physicalHeight);

        const float scale = safeDeviceDensity(wkWebView->deviceScale());
        const uint32_t logicalWidth = static_cast<uint32_t>(std::round(physicalWidth / scale));
        const uint32_t logicalHeight = static_cast<uint32_t>(std::round(physicalHeight / scale));
        wpe_view_android_resize(wkWebView->wpeView(), logicalWidth, logicalHeight);
        wpe_view_android_set_scale(wkWebView->wpeView(), scale);
    }
}

void JNIWKWebViewCache::nativeSurfaceRedrawNeeded(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceRedrawNeeded() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->wpeView())
        wpe_view_android_on_surface_redraw_needed(wkWebView->wpeView());
}

void JNIWKWebViewCache::nativeSurfaceDestroyed(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeSurfaceDestroyed() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->wpeView()) {
        wpe_view_set_visible(WPE_VIEW(wkWebView->wpeView()), FALSE);
        wpe_view_unmap(WPE_VIEW(wkWebView->wpeView()));
        wpe_view_android_set_toplevel_state(wkWebView->wpeView(), static_cast<WPEToplevelState>(0));
        wpe_view_android_on_surface_destroyed(wkWebView->wpeView());
    }
}

void JNIWKWebViewCache::nativeSetZoomLevel(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jdouble zoomLevel) noexcept
{
    Logging::logDebug("WKWebView::nativeSetZoomLevel(%f) [tid %d]", zoomLevel, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_set_zoom_level(wkWebView->m_webView, zoomLevel);
}

jboolean JNIWKWebViewCache::nativeIsMuted(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        return webkit_web_view_get_is_muted(wkWebView->m_webView);
    return JNI_FALSE;
}

void JNIWKWebViewCache::nativeSetMuted(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jboolean muted) noexcept
{
    Logging::logDebug("WKWebView::nativeSetMuted(%d) [tid %d]", muted, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && (wkWebView->m_webView != nullptr))
        webkit_web_view_set_is_muted(wkWebView->m_webView, muted);
}

void JNIWKWebViewCache::nativeOnTouchEvent(JNIEnv* env, jobject /*obj*/, jlong wkWebViewPtr, jlong time, jint type,
    jint pointerCount, jintArray ids, jfloatArray xs, jfloatArray ys) noexcept
{
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr);
    if (!wkWebView || !wkWebView->wpeView())
        return;

    WPEEventType eventType;
    switch (type) {
    case 0:
        eventType = WPE_EVENT_TOUCH_DOWN;
        break;
    case 1:
        eventType = WPE_EVENT_TOUCH_MOVE;
        break;
    case 2:
        eventType = WPE_EVENT_TOUCH_UP;
        break;
    default:
        return;
    }

    std::vector<jint> idsVector(pointerCount);
    std::vector<jfloat> xsVector(pointerCount);
    std::vector<jfloat> ysVector(pointerCount);
    env->GetIntArrayRegion(ids, 0, pointerCount, idsVector.data());
    env->GetFloatArrayRegion(xs, 0, pointerCount, xsVector.data());
    env->GetFloatArrayRegion(ys, 0, pointerCount, ysVector.data());

    const float scale = safeDeviceDensity(wkWebView->deviceScale());

    for (int i = 0; i < pointerCount; ++i) {
        const double logicalX = static_cast<double>(xsVector[i]) / scale;
        const double logicalY = static_cast<double>(ysVector[i]) / scale;

        auto* event = wpe_event_touch_new(eventType, WPE_VIEW(wkWebView->wpeView()), WPE_INPUT_SOURCE_TOUCHSCREEN,
            static_cast<guint32>(time), static_cast<WPEModifiers>(0), static_cast<guint32>(idsVector[i]), logicalX,
            logicalY);
        wpe_view_android_dispatch_event(wkWebView->wpeView(), event);
        wpe_event_unref(event);
    }
}

void JNIWKWebViewCache::nativeSetInputMethodContent(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint unicodeChar) noexcept
{
    Logging::logDebug("WKWebView::nativeSetInputMethodContent(0x%08X) [tid %d]", unicodeChar, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr) {
        static constexpr size_t GUNICHAR_UTF8_BUFFER_SIZE = 8;
        char utf8Content[GUNICHAR_UTF8_BUFFER_SIZE] = {};
        g_unichar_to_utf8(static_cast<gunichar>(unicodeChar), utf8Content);
        wkWebView->m_inputMethodContext.setContent(utf8Content);
    }
}

void JNIWKWebViewCache::nativeDeleteInputMethodContent(
    JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr, jint offset, jint count) noexcept
{
    Logging::logDebug("WKWebView::nativeDeleteInputMethodContent(%d, %d) [tid %d]", offset, count, gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if (wkWebView != nullptr)
        wkWebView->m_inputMethodContext.deleteContent(offset, count);
}

void JNIWKWebViewCache::nativeRequestExitFullscreenMode(
    JNIEnv* /*env*/, jobject /*obj*/, jlong /*wkWebViewPtr*/) noexcept
{
    Logging::logDebug("WKWebView::nativeRequestExitFullscreenMode() [tid %d]", gettid());
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

void JNIWKWebViewCache::nativeTriggerSslErrorHandler(
    JNIEnv* /*env*/, jclass /*klass*/, jlong handlerPtr, jboolean acceptCertificate) noexcept
{
    Logging::logDebug(
        "WKWebView::nativeTriggerSslErrorHandler(%s) [tid %d]", acceptCertificate ? "true" : "false", gettid());
    auto* handler = reinterpret_cast<SslErrorHandler*>(handlerPtr); // NOLINT(performance-no-int-to-ptr)
    if (handler != nullptr) {
        if (acceptCertificate) {
            handler->acceptCertificate();
        } else {
            handler->rejectCertificate();
        }
        delete handler;
    }
}

void JNIWKWebViewCache::nativeFocusIn(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeFocusIn() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->wpeView())
        wpe_view_focus_in(WPE_VIEW(wkWebView->wpeView()));
}

void JNIWKWebViewCache::nativeFocusOut(JNIEnv* /*env*/, jobject /*obj*/, jlong wkWebViewPtr) noexcept
{
    Logging::logDebug("WKWebView::nativeFocusOut() [tid %d]", gettid());
    auto* wkWebView = reinterpret_cast<WKWebView*>(wkWebViewPtr); // NOLINT(performance-no-int-to-ptr)
    if ((wkWebView != nullptr) && wkWebView->wpeView())
        wpe_view_focus_out(WPE_VIEW(wkWebView->wpeView()));
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
    const uint32_t physicalWidth = std::max(0, width);
    const uint32_t physicalHeight = std::max(0, height);

    const float scale = safeDeviceDensity(deviceScale);
    const uint32_t logicalWidth = static_cast<uint32_t>(std::round(physicalWidth / scale));
    const uint32_t logicalHeight = static_cast<uint32_t>(std::round(physicalHeight / scale));

    Logging::logDebug("WKWebView: physical=%ux%u, logical=%ux%u, scale=%.2f", physicalWidth, physicalHeight,
        logicalWidth, logicalHeight, scale);

    // Create WPE Platform display for the UI process
    m_wpeDisplay = wpe_display_get_primary();
    if (!m_wpeDisplay) {
        m_wpeDisplay = wpe_display_android_new();
        wpe_display_set_primary(m_wpeDisplay);

        GError* error = nullptr;
        if (!wpe_display_connect(m_wpeDisplay, &error)) {
            Logging::logError("Failed to connect WPE display: %s", error ? error->message : "unknown error");
            g_clear_error(&error);
        }
    } else {
        // Take ownership of borrowed reference from wpe_display_get_primary()
        g_object_ref(m_wpeDisplay);
    }

    // Create renderer if not headless
    if (!m_isHeadless)
        m_renderer = std::make_shared<RendererSurfaceControl>(physicalWidth, physicalHeight);

    gboolean const automationMode = wkWebContext->automationMode() ? TRUE : FALSE;

    // Create WebKitWebView with WPE Platform
    m_webView = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "display", m_wpeDisplay, "web-context",
        wkWebContext->webContext(), "is-controlled-by-automation", automationMode, nullptr));

    // Get the WPEViewAndroid that WebKit created
    m_wpeView = WPE_VIEW_ANDROID(webkit_web_view_get_wpe_view(m_webView));
    if (m_wpeView == nullptr) {
        Logging::logError("Failed to get WPEViewAndroid from WebKitWebView!");
    }

    // Set up the renderer on the WPE view
    if (m_wpeView && m_renderer) {
        wpe_view_android_set_renderer(m_wpeView, m_renderer);
    }

    // Set initial size and scale
    if (m_wpeView) {
        wpe_view_android_resize(m_wpeView, logicalWidth, logicalHeight);
        wpe_view_android_set_scale(m_wpeView, scale);
    }

    webkit_web_view_set_input_method_context(m_webView, m_inputMethodContext.webKitInputMethodContext());

    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "close", G_CALLBACK(JNIWKWebViewCache::onClose), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "load-changed", G_CALLBACK(JNIWKWebViewCache::onLoadChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::is-loading", G_CALLBACK(JNIWKWebViewCache::onIsLoadingChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "load-failed", G_CALLBACK(JNIWKWebViewCache::onLoadFailed), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "web-process-terminated", G_CALLBACK(JNIWKWebViewCache::onWebProcessTerminated), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::estimated-load-progress", G_CALLBACK(JNIWKWebViewCache::onEstimatedLoadProgress), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::uri", G_CALLBACK(JNIWKWebViewCache::onUriChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::title", G_CALLBACK(JNIWKWebViewCache::onTitleChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::is-playing-audio", G_CALLBACK(JNIWKWebViewCache::onIsPlayingAudioChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::is-muted", G_CALLBACK(JNIWKWebViewCache::onIsMutedChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::camera-capture-state", G_CALLBACK(JNIWKWebViewCache::onCameraCaptureStateChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(m_webView, "notify::microphone-capture-state",
        G_CALLBACK(JNIWKWebViewCache::onMicrophoneCaptureStateChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::display-capture-state", G_CALLBACK(JNIWKWebViewCache::onDisplayCaptureStateChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "script-dialog", G_CALLBACK(JNIWKWebViewCache::onScriptDialog), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "decide-policy", G_CALLBACK(JNIWKWebViewCache::onDecidePolicy), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "load-failed-with-tls-errors", G_CALLBACK(JNIWKWebViewCache::onReceivedSslError), this));
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

        m_wpeView = nullptr; // WebKit owns the WPEView
        g_object_unref(m_webView);
        m_webView = nullptr;
    }

    if (m_wpeDisplay != nullptr) {
        g_object_unref(m_wpeDisplay);
        m_wpeDisplay = nullptr;
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
