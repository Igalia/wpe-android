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
#include <android/bitmap.h>
#include <cstdint>
#include <limits>
#include <memory>
#include <pthread.h>
#include <sys/resource.h>
#include <vector>
#include <wpe/webkit.h>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebViewEvalCallbackHolder, "org/wpewebkit/wpe/WebKitWebView$EvalCallbackHolder");
DECLARE_JNI_CLASS_SIGNATURE(
    JNIWebKitWebViewSnapshotCallbackHolder, "org/wpewebkit/wpe/WebKitWebView$SnapshotCallbackHolder");
DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebView, "org/wpewebkit/wpe/WebKitWebView");
DECLARE_JNI_CLASS_SIGNATURE(JNIBitmap, "android/graphics/Bitmap");

namespace {

struct SnapshotSize {
    int width;
    int height;
};

// What the snapshot callback needs, the size limits, and once WebKit delivers the snapshot, its size and pixels. The
// pixels stay null if the snapshot failed.
struct SnapshotRequest {
    SnapshotRequest(JNIEnv* env, JNIWebKitWebViewSnapshotCallbackHolder callbackHolder, int maxWidth, int maxHeight)
        : holder(env, callbackHolder)
        , maxWidth(maxWidth)
        , maxHeight(maxHeight)
    {
    }

    ~SnapshotRequest()
    {
        if (bytes)
            g_bytes_unref(bytes);
    }

    SnapshotRequest(const SnapshotRequest&) = delete;
    SnapshotRequest& operator=(const SnapshotRequest&) = delete;
    SnapshotRequest(SnapshotRequest&&) = delete;
    SnapshotRequest& operator=(SnapshotRequest&&) = delete;

    JNI::GlobalRef<JNIWebKitWebViewSnapshotCallbackHolder> holder;
    int maxWidth;
    int maxHeight;
    int width {0};
    int height {0};
    size_t stride {0};
    GBytes* bytes {nullptr};
};

} // namespace

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

        if (!m_commitResult.invoke(holder, result))
            Logging::logError("cannot call WebKitWebView eval callback");
    }

private:
    const JNI::Method<void(jstring)> m_commitResult;
};

static const JNIWebKitWebViewEvalCallbackHolderCache& getJNIWebKitWebViewEvalCallbackHolderCache()
{
    static const JNIWebKitWebViewEvalCallbackHolderCache s_singleton;
    return s_singleton;
}

class JNIWebKitWebViewSnapshotCallbackHolderCache final
    : public JNI::TypedClass<JNIWebKitWebViewSnapshotCallbackHolder> {
public:
    JNIWebKitWebViewSnapshotCallbackHolderCache()
        : JNI::TypedClass<JNIWebKitWebViewSnapshotCallbackHolder>(true)
        , m_createBitmap(getMethod<JNIBitmap(jint, jint)>("createBitmap"))
        , m_commitResult(getMethod<void(JNIBitmap)>("commitResult"))
    {
    }

    // Returns null if Java could not allocate the bitmap.
    JNI::ProtectedType<JNIBitmap> createBitmap(
        JNIWebKitWebViewSnapshotCallbackHolder holder, jint width, jint height) const
    {
        return m_createBitmap.invoke(holder, width, height);
    }

    void onResult(JNIWebKitWebViewSnapshotCallbackHolder holder, JNIBitmap bitmap) const
    {
        if (!holder)
            return;
        if (!m_commitResult.invoke(holder, bitmap))
            Logging::logError("cannot call WebKitWebView snapshot callback");
    }

private:
    const JNI::Method<JNIBitmap(jint, jint)> m_createBitmap;
    const JNI::Method<void(JNIBitmap)> m_commitResult;
};

static const JNIWebKitWebViewSnapshotCallbackHolderCache& getJNIWebKitWebViewSnapshotCallbackHolderCache()
{
    static const JNIWebKitWebViewSnapshotCallbackHolderCache s_singleton;
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
        , m_onScriptDialog(getMethod<void(jlong, jint, jstring, jstring, jstring)>("onScriptDialog"))
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
            JNI::NativeMethod<void(jlong, jfloat, jfloat, jfloat, jfloat)>(
                "nativeSetBackgroundColor", nativeSetBackgroundColor),
            JNI::NativeMethod<void(jlong, jstring, JNIWebKitWebViewEvalCallbackHolder)>(
                "nativeEvaluateJavascript", nativeEvaluateJavascript),
            JNI::NativeMethod<void(jlong, jint, jint, jint, JNIWebKitWebViewSnapshotCallbackHolder)>(
                "nativeCaptureSnapshot", nativeCaptureSnapshot),
            JNI::NativeMethod<void(jlong, jboolean, jstring)>("nativeScriptDialogConfirm", nativeScriptDialogConfirm),
            JNI::NativeMethod<void(jlong)>("nativeScriptDialogClose", nativeScriptDialogClose));
    }

    const JNI::Method<void()> m_onClose;
    const JNI::Method<void(jstring)> m_onLoadStarted;
    const JNI::Method<void(jstring)> m_onLoadFinished;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jdouble)> m_onEstimatedLoadProgress;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onTitleChanged;
    const JNI::Method<void(jstring, jstring, jstring, jint)> m_onReceivedHttpError;
    const JNI::Method<void(jlong, jint, jstring, jstring, jstring)> m_onScriptDialog;

private:
    static void onClose(WebKitWebView*, WebKitWebViewBridge* bridge);
    static void onLoadChanged(WebKitWebView* webView, WebKitLoadEvent loadEvent, WebKitWebViewBridge* bridge);
    static void onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onTitleChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static gboolean onDecidePolicy(WebKitWebView*, WebKitPolicyDecision* decision,
        WebKitPolicyDecisionType decisionType, WebKitWebViewBridge* bridge);
    static gboolean onScriptDialog(WebKitWebView* webView, WebKitScriptDialog* dialog, WebKitWebViewBridge* bridge);
    static void onEvalJavascriptReady(GObject* object, GAsyncResult* result, gpointer userData);
    static void onCaptureSnapshotReady(GObject* object, GAsyncResult* result, gpointer userData);

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
    static void nativeSetBackgroundColor(
        JNIEnv*, jobject, jlong nativePtr, jfloat red, jfloat green, jfloat blue, jfloat alpha);
    static void nativeEvaluateJavascript(
        JNIEnv* env, jobject, jlong nativePtr, jstring script, JNIWebKitWebViewEvalCallbackHolder callbackHolder);
    static void nativeCaptureSnapshot(JNIEnv* env, jobject, jlong nativePtr, jint region, jint maxWidth, jint maxHeight,
        JNIWebKitWebViewSnapshotCallbackHolder callbackHolder);
    static void nativeScriptDialogConfirm(JNIEnv*, jobject, jlong dialogPtr, jboolean confirm, jstring text);
    static void nativeScriptDialogClose(JNIEnv*, jobject, jlong dialogPtr);
};

static const JNIWebKitWebViewCache& getJNIWebKitWebViewCache()
{
    static const JNIWebKitWebViewCache s_singleton;
    return s_singleton;
}

void JNIWebKitWebViewCache::onClose(WebKitWebView*, WebKitWebViewBridge* bridge)
{
    if (!getJNIWebKitWebViewCache().m_onClose.invoke(bridge->m_javaRef.get()))
        Logging::logError("cannot deliver WebKitWebView onClose callback");
}

void JNIWebKitWebViewCache::onLoadChanged(
    WebKitWebView* webView, WebKitLoadEvent loadEvent, WebKitWebViewBridge* bridge)
{
    // WEBKIT_LOAD_REDIRECTED and WEBKIT_LOAD_COMMITTED are intentionally not surfaced.
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        auto jUri = JNI::String(webkit_web_view_get_uri(webView));
        if (!getJNIWebKitWebViewCache().m_onLoadStarted.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
            Logging::logError("cannot deliver WebKitWebView onLoadStarted callback");
    } else if (loadEvent == WEBKIT_LOAD_FINISHED) {
        auto jUri = JNI::String(webkit_web_view_get_uri(webView));
        if (!getJNIWebKitWebViewCache().m_onLoadFinished.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
            Logging::logError("cannot deliver WebKitWebView onLoadFinished callback");
    }
}

void JNIWebKitWebViewCache::onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    auto jUri = JNI::String(webkit_web_view_get_uri(WEBKIT_WEB_VIEW(obj)));
    if (!getJNIWebKitWebViewCache().m_onUriChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
        Logging::logError("cannot deliver WebKitWebView onUriChanged callback");
}

void JNIWebKitWebViewCache::onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    double progress = webkit_web_view_get_estimated_load_progress(WEBKIT_WEB_VIEW(obj));
    if (!getJNIWebKitWebViewCache().m_onEstimatedLoadProgress.invoke(bridge->m_javaRef.get(), progress))
        Logging::logError("cannot deliver WebKitWebView onEstimatedLoadProgress callback");
}

void JNIWebKitWebViewCache::onTitleChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    auto* webView = WEBKIT_WEB_VIEW(obj);
    auto jTitle = JNI::String(webkit_web_view_get_title(webView));
    if (!getJNIWebKitWebViewCache().m_onTitleChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jTitle),
            static_cast<jboolean>(webkit_web_view_can_go_back(webView)),
            static_cast<jboolean>(webkit_web_view_can_go_forward(webView))))
        Logging::logError("cannot deliver WebKitWebView onTitleChanged callback");
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
    if (!getJNIWebKitWebViewCache().m_onReceivedHttpError.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri),
            static_cast<jstring>(jMethod), static_cast<jstring>(jMimeType), static_cast<jint>(statusCode)))
        Logging::logError("cannot deliver WebKitWebView onReceivedHttpError callback");

    return FALSE;
}

gboolean JNIWebKitWebViewCache::onScriptDialog(
    WebKitWebView* webView, WebKitScriptDialog* dialog, WebKitWebViewBridge* bridge)
{
    // Keep the dialog alive across the asynchronous round-trip to Java; it is released by
    // nativeScriptDialogClose once the embedder (or the default dialog) has answered.
    auto dialogPtr = reinterpret_cast<jlong>(webkit_script_dialog_ref(dialog));
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    // The active URI is null before the first commit and the message may be null; pass them through
    // as null (JNI::String(nullptr) yields a null jstring) — the Java callbacks treat null as "no value".
    auto jUrl = JNI::String(webkit_web_view_get_uri(webView));
    auto jMessage = JNI::String(webkit_script_dialog_get_message(dialog));
    JNI::String jDefaultText = type == WEBKIT_SCRIPT_DIALOG_PROMPT
        ? JNI::String(webkit_script_dialog_prompt_get_default_text(dialog))
        : JNI::String("");
    if (!getJNIWebKitWebViewCache().m_onScriptDialog.invoke(bridge->m_javaRef.get(), dialogPtr, static_cast<jint>(type),
            static_cast<jstring>(jUrl), static_cast<jstring>(jMessage), static_cast<jstring>(jDefaultText))) {
        Logging::logError("cannot deliver WebKitWebView onScriptDialog callback");
        webkit_script_dialog_unref(dialog);
        return FALSE;
    }

    return TRUE;
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
        g_signal_connect(webView, "script-dialog", G_CALLBACK(onScriptDialog), bridge),
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

void JNIWebKitWebViewCache::nativeSetBackgroundColor(
    JNIEnv*, jobject, jlong nativePtr, jfloat red, jfloat green, jfloat blue, jfloat alpha)
{
    WebKitColor backgroundColor = {red, green, blue, alpha};
    webkit_web_view_set_background_color(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView, &backgroundColor);
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

// Check that WebKit's buffer holds height rows of width BGRA pixels at the given stride.
static bool isValidSnapshotLayout(int width, int height, size_t stride, size_t sourceSize)
{
    if (width <= 0 || height <= 0 || static_cast<size_t>(width) > std::numeric_limits<size_t>::max() / 4)
        return false;

    const size_t rowBytes = static_cast<size_t>(width) * 4;
    if (stride < rowBytes || sourceSize < rowBytes)
        return false;

    // Check the last row without overflowing stride * height. Its trailing padding is optional.
    return static_cast<size_t>(height - 1) <= (sourceSize - rowBytes) / stride;
}

// WebKit provides BGRA; Android RGBA_8888 bitmaps store RGBA bytes. Both are premultiplied, so only the channel
// order changes.
static void storeAsRGBA(uint8_t* destination, uint8_t blue, uint8_t green, uint8_t red, uint8_t alpha)
{
    destination[0] = red;
    destination[1] = green;
    destination[2] = blue;
    destination[3] = alpha;
}

// Call only after validating the source layout with isValidSnapshotLayout().
static void copySnapshotPixels(
    const uint8_t* source, int width, int height, size_t sourceStride, uint8_t* destination, size_t destinationStride)
{
    const size_t rowBytes = static_cast<size_t>(width) * 4;
    for (int y = 0; y < height; ++y) {
        const auto* src = source + (static_cast<size_t>(y) * sourceStride);
        auto* dst = destination + (static_cast<size_t>(y) * destinationStride);
        for (size_t x = 0; x < rowBytes; x += 4)
            storeAsRGBA(dst + x, src[x], src[x + 1], src[x + 2], src[x + 3]);
    }
}

// The size of a width by height snapshot scaled down to fit in maxWidth by maxHeight, keeping its aspect ratio. A
// snapshot that already fits keeps its size, and a limit of 0 means no limit on that side.
static SnapshotSize scaledSnapshotSize(int width, int height, int maxWidth, int maxHeight)
{
    const int64_t limitWidth = maxWidth > 0 ? maxWidth : width;
    const int64_t limitHeight = maxHeight > 0 ? maxHeight : height;
    if (width <= limitWidth && height <= limitHeight)
        return {width, height};

    // The tighter limit sets the scale. The other side is rounded to the nearest pixel, and is at least one.
    if (width * limitHeight >= height * limitWidth)
        return {static_cast<int>(limitWidth), std::max(1, static_cast<int>((height * limitWidth + width / 2) / width))};
    return {std::max(1, static_cast<int>((width * limitHeight + height / 2) / height)), static_cast<int>(limitHeight)};
}

// Scales the snapshot down by averaging the source pixels that fall in each destination pixel. The pixels are
// premultiplied, so a plain average of each channel is correct. Call only after validating the source layout with
// isValidSnapshotLayout(), and with a size from scaledSnapshotSize().
static void copyScaledSnapshotPixels(const uint8_t* source, int width, int height, size_t sourceStride,
    uint8_t* destination, SnapshotSize scaled, size_t destinationStride)
{
    // The source columns of destination column x are columns[x] up to, but not including, columns[x + 1].
    std::vector<int> columns(static_cast<size_t>(scaled.width) + 1);
    for (int x = 0; x <= scaled.width; ++x)
        columns[x] = static_cast<int>(static_cast<int64_t>(x) * width / scaled.width);

    std::vector<uint64_t> sums(static_cast<size_t>(scaled.width) * 4);
    for (int y = 0; y < scaled.height; ++y) {
        const int firstRow = static_cast<int>(static_cast<int64_t>(y) * height / scaled.height);
        const int endRow = static_cast<int>(static_cast<int64_t>(y + 1) * height / scaled.height);
        std::fill(sums.begin(), sums.end(), 0);
        for (int row = firstRow; row < endRow; ++row) {
            const auto* src = source + (static_cast<size_t>(row) * sourceStride);
            for (int x = 0; x < scaled.width; ++x) {
                for (int column = columns[x]; column < columns[x + 1]; ++column) {
                    const auto* pixel = src + (static_cast<size_t>(column) * 4);
                    for (int channel = 0; channel < 4; ++channel)
                        sums[(static_cast<size_t>(x) * 4) + channel] += pixel[channel];
                }
            }
        }

        auto* dst = destination + (static_cast<size_t>(y) * destinationStride);
        for (int x = 0; x < scaled.width; ++x) {
            const uint64_t count = static_cast<uint64_t>(endRow - firstRow) * (columns[x + 1] - columns[x]);
            const auto* sum = &sums[static_cast<size_t>(x) * 4];
            auto average = [count](uint64_t total) { return static_cast<uint8_t>((total + count / 2) / count); };
            storeAsRGBA(
                dst + (static_cast<size_t>(x) * 4), average(sum[0]), average(sum[1]), average(sum[2]), average(sum[3]));
        }
    }
}

// Converts the request's pixels into a Java-allocated bitmap, scaled down to fit the request's limits. Returns null
// on failure. Call only after validating the request's layout with isValidSnapshotLayout().
static JNI::ProtectedType<JNIBitmap> createSnapshotBitmap(const SnapshotRequest& request)
{
    const auto* data = static_cast<const uint8_t*>(g_bytes_get_data(request.bytes, nullptr));
    const auto scaled = scaledSnapshotSize(request.width, request.height, request.maxWidth, request.maxHeight);
    auto bitmap = getJNIWebKitWebViewSnapshotCallbackHolderCache().createBitmap(
        request.holder.get(), scaled.width, scaled.height);
    if (!bitmap)
        return {};

    auto* env = JNI::getCurrentThreadJNIEnv();
    AndroidBitmapInfo info {};
    if (AndroidBitmap_getInfo(env, bitmap.get(), &info) != ANDROID_BITMAP_RESULT_SUCCESS
        || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != static_cast<uint32_t>(scaled.width)
        || info.height != static_cast<uint32_t>(scaled.height) || info.stride < static_cast<size_t>(scaled.width) * 4) {
        Logging::logError("unexpected snapshot bitmap layout");
        return {};
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap.get(), &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        Logging::logError("cannot lock snapshot bitmap pixels");
        return {};
    }
    auto* destination = static_cast<uint8_t*>(pixels);
    // Without scaling, the plain copy gives the same pixels as copyScaledSnapshotPixels(), only faster.
    if (scaled.width == request.width && scaled.height == request.height)
        copySnapshotPixels(data, request.width, request.height, request.stride, destination, info.stride);
    else
        copyScaledSnapshotPixels(data, request.width, request.height, request.stride, destination, scaled, info.stride);
    AndroidBitmap_unlockPixels(env, bitmap.get());
    return bitmap;
}

// Converts the request's pixels, if the snapshot did not fail, and hands the bitmap, or null, to the callback. Takes
// ownership of the request.
static void deliverSnapshot(SnapshotRequest* request)
{
    std::unique_ptr<SnapshotRequest> ownedRequest(request);
    auto bitmap = ownedRequest->bytes ? createSnapshotBitmap(*ownedRequest) : JNI::ProtectedType<JNIBitmap> {};
    getJNIWebKitWebViewSnapshotCallbackHolderCache().onResult(ownedRequest->holder.get(), bitmap.get());
}

static void* snapshotThreadMain(void* queue)
{
    pthread_setname_np(pthread_self(), "WPESnapshot");
    // Background priority, like Java's THREAD_PRIORITY_BACKGROUND, so that the copy does not compete with the UI. On
    // Linux this only changes the calling thread.
    setpriority(PRIO_PROCESS, 0, 10);
    while (true)
        deliverSnapshot(static_cast<SnapshotRequest*>(g_async_queue_pop(static_cast<GAsyncQueue*>(queue))));
    return nullptr;
}

// Copying the pixels into the bitmap, and scaling them, takes milliseconds for a screen and longer for a long page,
// so one worker thread does it, for one snapshot at a time and in the order WebKit delivers them. The thread starts
// on first use. Returns null if it cannot be started.
static GAsyncQueue* snapshotQueue()
{
    static GAsyncQueue* queue = []() -> GAsyncQueue* {
        auto* newQueue = g_async_queue_new();
        pthread_t id {};
        if (int error = pthread_create(&id, nullptr, snapshotThreadMain, newQueue)) {
            Logging::logError(
                "cannot create the snapshot thread (error %d), snapshots will be converted on the main thread", error);
            g_async_queue_unref(newQueue);
            return nullptr;
        }
        pthread_detach(id);
        return newQueue;
    }();
    return queue;
}

void JNIWebKitWebViewCache::onCaptureSnapshotReady(GObject* object, GAsyncResult* result, gpointer userData)
{
    std::unique_ptr<SnapshotRequest> request(static_cast<SnapshotRequest*>(userData));
    if (!request || !request->holder)
        return;

    g_autoptr(GError) error = nullptr;
    g_autoptr(WebKitImage) image = webkit_web_view_get_snapshot_finish(WEBKIT_WEB_VIEW(object), result, &error);
    if (image) {
        // Read the image here, on the main thread, and keep only a reference to its pixels, which stay valid after
        // the image is released. A GBytes can be released on any thread.
        request->width = webkit_image_get_width(image);
        request->height = webkit_image_get_height(image);
        request->stride = webkit_image_get_stride(image);
        GBytes* bytes = webkit_image_as_bytes(image);
        gsize dataSize = 0;
        const void* data = bytes ? g_bytes_get_data(bytes, &dataSize) : nullptr;
        if (data && isValidSnapshotLayout(request->width, request->height, request->stride, dataSize))
            request->bytes = g_bytes_ref(bytes);
        else
            Logging::logError("invalid WebKitWebView snapshot buffer");
    } else if (error)
        Logging::logError("cannot capture WebKitWebView snapshot: %s", error->message);

    // A failed snapshot goes through the queue too, without pixels, so that its null result does not overtake the
    // snapshots WebKit delivered before it. The callback runs on the main thread, as commitResult posts it there.
    if (auto* queue = snapshotQueue()) {
        g_async_queue_push(queue, request.release());
        return;
    }
    deliverSnapshot(request.release());
}

void JNIWebKitWebViewCache::nativeCaptureSnapshot(JNIEnv* env, jobject, jlong nativePtr, jint region, jint maxWidth,
    jint maxHeight, JNIWebKitWebViewSnapshotCallbackHolder callbackHolder)
{
    if (!callbackHolder)
        return;

    // WebView checks the region. 1 is SNAPSHOT_REGION_FULL_DOCUMENT; anything else is taken as VISIBLE.
    const WebKitSnapshotRegion snapshotRegion
        = region == 1 ? WEBKIT_SNAPSHOT_REGION_FULL_DOCUMENT : WEBKIT_SNAPSHOT_REGION_VISIBLE;

    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    if (!bridge) {
        getJNIWebKitWebViewSnapshotCallbackHolderCache().onResult(callbackHolder, nullptr);
        return;
    }

    auto request = std::make_unique<SnapshotRequest>(env, callbackHolder, maxWidth, maxHeight);
    if (!request->holder) {
        JNI::clearJavaException(env);
        getJNIWebKitWebViewSnapshotCallbackHolderCache().onResult(callbackHolder, nullptr);
        return;
    }
    webkit_web_view_get_snapshot(bridge->m_webView, snapshotRegion, WEBKIT_SNAPSHOT_OPTIONS_NONE, nullptr,
        onCaptureSnapshotReady, request.release());
}

void JNIWebKitWebViewCache::nativeScriptDialogConfirm(JNIEnv*, jobject, jlong dialogPtr, jboolean confirm, jstring text)
{
    auto* dialog = reinterpret_cast<WebKitScriptDialog*>(dialogPtr); // NOLINT(performance-no-int-to-ptr)
    if (webkit_script_dialog_get_dialog_type(dialog) == WEBKIT_SCRIPT_DIALOG_PROMPT && text != nullptr)
        webkit_script_dialog_prompt_set_text(dialog, JNI::String(text).getContent().get());
    webkit_script_dialog_confirm_set_confirmed(dialog, static_cast<gboolean>(confirm));
}

void JNIWebKitWebViewCache::nativeScriptDialogClose(JNIEnv*, jobject, jlong dialogPtr)
{
    // Releases the reference taken in onScriptDialog; the dialog is closed once its refcount reaches zero.
    webkit_script_dialog_unref(reinterpret_cast<WebKitScriptDialog*>(dialogPtr)); // NOLINT(performance-no-int-to-ptr)
}

void configureWebKitWebViewJNIMappings()
{
    getJNIWebKitWebViewCache();
}
void configureWebKitWebViewEvalCallbackHolderJNIMappings()
{
    getJNIWebKitWebViewEvalCallbackHolderCache();
}
void configureWebKitWebViewSnapshotCallbackHolderJNIMappings()
{
    getJNIWebKitWebViewSnapshotCallbackHolderCache();
}
} // namespace WebKit
