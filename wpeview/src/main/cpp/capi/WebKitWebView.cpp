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
#include "WebKitDownloadBridge.h"

#include <algorithm>
#include <android/bitmap.h>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <pthread.h>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unordered_map>
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

// The bridge of a web view, which callbacks that call the app find it by again: the app may destroy the WebView
// meanwhile. Downloads find it by too: a download outlives its web view, which may outlive its bridge.
static GQuark webViewBridgeQuark()
{
    static GQuark quark = g_quark_from_static_string("wpe-android-web-view-bridge");
    return quark;
}

struct WebKitWebViewBridge {
    WebKitWebViewBridge(WebKitWebView* webView, JNIEnv* env, jobject javaWebView)
        : m_webView(webView)
        , m_javaRef(env, javaWebView)
    {
        g_object_set_qdata(G_OBJECT(m_webView), webViewBridgeQuark(), this);
    }

    ~WebKitWebViewBridge()
    {
        g_object_set_qdata(G_OBJECT(m_webView), webViewBridgeQuark(), nullptr);
        for (gulong id : m_signalIds)
            g_signal_handler_disconnect(m_webView, id);
        m_signalIds.clear();
        // The loads of the view end with it, and WebKit cancels the requests that are left.
        for (auto& [id, request] : m_authenticationRequests) {
            g_signal_handlers_disconnect_by_data(request, this);
            g_object_unref(request);
        }
        m_authenticationRequests.clear();
        if (m_backForwardListChangedId)
            g_signal_handler_disconnect(webkit_web_view_get_back_forward_list(m_webView), m_backForwardListChangedId);
        if (m_downloadStartedId)
            g_signal_handler_disconnect(webkit_web_view_get_network_session(m_webView), m_downloadStartedId);
        g_object_unref(m_webView);
    }

    WebKitWebView* m_webView;
    JNI::GlobalRef<JNIWebKitWebView> m_javaRef;
    std::vector<gulong> m_signalIds;
    gulong m_backForwardListChangedId {0};
    gulong m_downloadStartedId {0};
    // The address and the Back/Forward state last reported, none at first.
    std::string m_historyUri;
    bool m_historyCanGoBack {false};
    bool m_historyCanGoForward {false};
    // Whether a load started and has not committed yet, and whether the title changed meanwhile.
    bool m_loadStarting {false};
    bool m_titlePending {false};
    // The address and method of the last main frame navigation WebKit decided on. For a load the page starts itself,
    // WebKit changes its URI only after the start of the load is reported, so the start is reported with this address,
    // while the load has not started yet. Each navigation is decided on before its load starts, so one that never
    // starts is replaced by the next one. The method is for the failure of the load.
    std::string m_navigationUri;
    std::string m_navigationMethod;
    bool m_navigationPending {false};
    // The server redirects of the current main frame load.
    unsigned m_redirectCount {0};
    // The address of the current main frame load once it failed. WebKit keeps the web view's URI at the previous page,
    // but the end of the load is reported with the address that failed, as with android.webkit.WebView.
    std::string m_failedLoadUri;
    // Whether the web process of the page ended, until the next page commits. WebKit then empties the address and the
    // title, but the page stays in the Back/Forward list, where a reload finds it, so the view keeps both meanwhile.
    bool m_webProcessTerminated {false};
    // The address of the last load the app asked for, as WebKit keeps it, until WebKit decides on the next main frame
    // navigation, which is that load if it has this address. The app is not asked about its own loads.
    std::string m_appLoadUri;
    // The authentication requests that the app was asked about and has not answered, by the id it was given. WebKit
    // cancels a request when its load ends or another one starts, and a later answer for it is dropped.
    std::unordered_map<jlong, WebKitAuthenticationRequest*> m_authenticationRequests;
    jlong m_lastAuthenticationRequestId {0};
    // The navigations of the page and its frames since the page committed, with and without a user gesture, by the key
    // of their address (navigationKey). WebKit does not tell whether a user gesture started a download, so a download
    // takes it from the navigations to its address.
    struct NavigationCount {
        uint32_t withGesture {0};
        uint32_t withoutGesture {0};
    };
    std::unordered_map<size_t, NavigationCount> m_navigationCounts;
    // Whether the page went to more addresses than are counted, so that no download has a gesture until the next page.
    bool m_navigationCountsFull {false};
    // Whether a user gesture started the current main frame navigation, which its server redirects keep.
    bool m_mainFrameGesture {false};
    // The address of the page that the window of this view opens with, and whether a user gesture opened the window,
    // until WebKit decides on the first main frame navigation of the view. It does so without that gesture.
    std::string m_windowLoadUri;
    bool m_windowLoadGesture {false};
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
        , m_onTitleChanged(getMethod<void(jstring)>("onTitleChanged"))
        , m_onHistoryChanged(getMethod<void(jstring, jboolean, jboolean)>("onHistoryChanged"))
        , m_onReceivedHttpError(getMethod<void(jstring, jstring, jstring, jint, jboolean)>("onReceivedHttpError"))
        , m_onLoadFailed(getMethod<void(jstring, jstring, jint, jstring)>("onLoadFailed"))
        , m_onScriptDialog(getMethod<void(jlong, jint, jstring, jstring, jstring)>("onScriptDialog"))
        , m_onWebProcessTerminated(getMethod<void(jint)>("onWebProcessTerminated"))
        , m_onCreateWindow(getMethod<jlong(jboolean)>("onCreateWindow"))
        , m_shouldOverrideUrlLoading(
              getMethod<jboolean(jstring, jstring, jboolean, jboolean, jboolean)>("shouldOverrideUrlLoading"))
        , m_onDownloadStarted(getMethod<void(jlong, jstring, jstring, jstring, jlong, jboolean)>("onDownloadStarted"))
        , m_onRunFileChooser(getMethod<void(jlong, jstringArray, jboolean)>("onRunFileChooser"))
        , m_onRunColorChooser(getMethod<void(jlong, jint, jboolean)>("onRunColorChooser"))
        , m_onColorChooserFinished(getMethod<void(jlong)>("onColorChooserFinished"))
        , m_onAuthenticate(
              getMethod<void(jlong, jstring, jint, jstring, jstring, jboolean, jboolean)>("onAuthenticate"))
        , m_onAuthenticationCancelled(getMethod<void(jlong)>("onAuthenticationCancelled"))
        , m_onIsPlayingAudioChanged(getMethod<void(jboolean)>("onIsPlayingAudioChanged"))
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jlong, jlong, jlong, jlong, jlong)>("nativeInit", nativeInit),
            JNI::NativeMethod<jlong(jlong, jlong)>("nativeInitRelated", nativeInitRelated),
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
            JNI::NativeMethod<void(jlong, jstring, jstring, JNIWebKitWebViewEvalCallbackHolder)>(
                "nativeEvaluateJavascript", nativeEvaluateJavascript),
            JNI::NativeMethod<void(jlong, jint, jint, jint, JNIWebKitWebViewSnapshotCallbackHolder)>(
                "nativeCaptureSnapshot", nativeCaptureSnapshot),
            JNI::NativeMethod<void(jlong, jboolean, jstring)>("nativeScriptDialogConfirm", nativeScriptDialogConfirm),
            JNI::NativeMethod<void(jlong)>("nativeScriptDialogClose", nativeScriptDialogClose),
            JNI::NativeMethod<void(jlong)>("nativeTerminateWebProcess", nativeTerminateWebProcess),
            JNI::NativeMethod<jbyteArray(jlong)>("nativeSaveState", nativeSaveState),
            JNI::NativeMethod<jboolean(jlong, jbyteArray)>("nativeRestoreState", nativeRestoreState),
            JNI::NativeMethod<jboolean(jlong)>("nativeCanGoBack", nativeCanGoBack),
            JNI::NativeMethod<jboolean(jlong)>("nativeCanGoForward", nativeCanGoForward),
            JNI::NativeMethod<void(jlong, jstringArray)>("nativeFileChooserSelect", nativeFileChooserSelect),
            JNI::NativeMethod<void(jlong)>("nativeFileChooserCancel", nativeFileChooserCancel),
            JNI::NativeMethod<void(jlong, jfloat, jfloat, jfloat, jfloat)>(
                "nativeColorChooserSetColor", nativeColorChooserSetColor),
            JNI::NativeMethod<void(jlong)>("nativeColorChooserFinish", nativeColorChooserFinish),
            JNI::NativeMethod<void(jlong)>("nativeColorChooserCancel", nativeColorChooserCancel),
            JNI::NativeMethod<void(jlong, jlong, jstring, jstring)>("nativeAuthenticate", nativeAuthenticate),
            JNI::NativeMethod<void(jlong, jlong)>("nativeAuthenticationCancel", nativeAuthenticationCancel),
            JNI::NativeMethod<void(jlong, jboolean)>("nativeSetIsMuted", nativeSetIsMuted),
            JNI::NativeMethod<jboolean(jlong)>("nativeIsMuted", nativeIsMuted));
    }

    const JNI::Method<void()> m_onClose;
    const JNI::Method<void(jstring)> m_onLoadStarted;
    const JNI::Method<void(jstring)> m_onLoadFinished;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jdouble)> m_onEstimatedLoadProgress;
    const JNI::Method<void(jstring)> m_onTitleChanged;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onHistoryChanged;
    const JNI::Method<void(jstring, jstring, jstring, jint, jboolean)> m_onReceivedHttpError;
    const JNI::Method<void(jstring, jstring, jint, jstring)> m_onLoadFailed;
    const JNI::Method<void(jlong, jint, jstring, jstring, jstring)> m_onScriptDialog;
    const JNI::Method<void(jint)> m_onWebProcessTerminated;
    const JNI::Method<jlong(jboolean)> m_onCreateWindow;
    const JNI::Method<jboolean(jstring, jstring, jboolean, jboolean, jboolean)> m_shouldOverrideUrlLoading;
    const JNI::Method<void(jlong, jstring, jstring, jstring, jlong, jboolean)> m_onDownloadStarted;
    const JNI::Method<void(jlong, jstringArray, jboolean)> m_onRunFileChooser;
    const JNI::Method<void(jlong, jint, jboolean)> m_onRunColorChooser;
    const JNI::Method<void(jlong)> m_onColorChooserFinished;
    const JNI::Method<void(jlong, jstring, jint, jstring, jstring, jboolean, jboolean)> m_onAuthenticate;
    const JNI::Method<void(jlong)> m_onAuthenticationCancelled;
    const JNI::Method<void(jboolean)> m_onIsPlayingAudioChanged;

private:
    static void onClose(WebKitWebView*, WebKitWebViewBridge* bridge);
    static void onLoadChanged(WebKitWebView* webView, WebKitLoadEvent loadEvent, WebKitWebViewBridge* bridge);
    static void onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onTitleChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void reportTitle(WebKitWebViewBridge* bridge);
    static void onIsLoadingChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onBackForwardListChanged(
        WebKitBackForwardList*, WebKitBackForwardListItem*, gpointer, WebKitWebViewBridge* bridge);
    static void reportHistory(WebKitWebViewBridge* bridge, bool committed = false);
    static gboolean onDecidePolicy(WebKitWebView*, WebKitPolicyDecision* decision,
        WebKitPolicyDecisionType decisionType, WebKitWebViewBridge* bridge);
    static bool shouldOverrideUrlLoading(
        WebKitWebViewBridge* bridge, WebKitNavigationAction* action, bool isMainFrame, bool isAppLoad);
    static void countNavigation(
        WebKitWebViewBridge* bridge, WebKitNavigationAction* action, bool isMainFrame, bool isUserLoad);
    static bool takeNavigationGesture(WebKitWebViewBridge* bridge, const char* uri);
    static gboolean onLoadFailed(
        WebKitWebView*, WebKitLoadEvent, const gchar* failingUri, GError* error, WebKitWebViewBridge* bridge);
    static gboolean onScriptDialog(WebKitWebView* webView, WebKitScriptDialog* dialog, WebKitWebViewBridge* bridge);
    static void onWebProcessTerminated(
        WebKitWebView*, WebKitWebProcessTerminationReason reason, WebKitWebViewBridge* bridge);
    static WebKitWebView* onCreate(WebKitWebView*, WebKitNavigationAction* action, WebKitWebViewBridge* bridge);
    static void onDownloadStarted(WebKitNetworkSession*, WebKitDownload* download, WebKitWebViewBridge* bridge);
    static gboolean onDecideDownloadDestination(
        WebKitDownload* download, const char* suggestedFilename, gpointer hasGesture);
    static gboolean onRunFileChooser(WebKitWebView*, WebKitFileChooserRequest* request, WebKitWebViewBridge* bridge);
    static gboolean onRunColorChooser(WebKitWebView*, WebKitColorChooserRequest* request, WebKitWebViewBridge* bridge);
    static void onColorChooserFinished(WebKitColorChooserRequest* request, WebKitWebViewBridge* bridge);
    static gboolean onAuthenticate(WebKitWebView*, WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge);
    static void onAuthenticationCancelled(WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge);
    static void answerAuthentication(WebKitWebViewBridge* bridge, jlong requestId, WebKitCredential* credential);
    static void onIsPlayingAudioChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onEvalJavascriptReady(GObject* object, GAsyncResult* result, gpointer userData);
    static void onCaptureSnapshotReady(GObject* object, GAsyncResult* result, gpointer userData);

    static WebKitWebViewBridge* createBridge(JNIEnv* env, jobject jniWebView, WebKitWebView* webView);
    static jlong nativeInit(JNIEnv* env, jobject jniWebView, jlong displayPtr, jlong contextPtr, jlong toplevelPtr,
        jlong networkSessionPtr, jlong settingsPtr);
    static jlong nativeInitRelated(JNIEnv* env, jobject jniWebView, jlong relatedPtr, jlong settingsPtr);
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
    static void nativeEvaluateJavascript(JNIEnv* env, jobject, jlong nativePtr, jstring script, jstring worldName,
        JNIWebKitWebViewEvalCallbackHolder callbackHolder);
    static void nativeCaptureSnapshot(JNIEnv* env, jobject, jlong nativePtr, jint region, jint maxWidth, jint maxHeight,
        JNIWebKitWebViewSnapshotCallbackHolder callbackHolder);
    static void nativeScriptDialogConfirm(JNIEnv*, jobject, jlong dialogPtr, jboolean confirm, jstring text);
    static void nativeScriptDialogClose(JNIEnv*, jobject, jlong dialogPtr);
    static void nativeTerminateWebProcess(JNIEnv*, jobject, jlong nativePtr);
    static jbyteArray nativeSaveState(JNIEnv* env, jobject, jlong nativePtr);
    static jboolean nativeRestoreState(JNIEnv* env, jobject, jlong nativePtr, jbyteArray data);
    static jboolean nativeCanGoBack(JNIEnv*, jobject, jlong nativePtr);
    static jboolean nativeCanGoForward(JNIEnv*, jobject, jlong nativePtr);
    static void nativeFileChooserSelect(JNIEnv*, jobject, jlong requestPtr, jstringArray pathsArray);
    static void nativeFileChooserCancel(JNIEnv*, jobject, jlong requestPtr);
    static void nativeColorChooserSetColor(
        JNIEnv*, jobject, jlong requestPtr, jfloat red, jfloat green, jfloat blue, jfloat alpha);
    static void nativeColorChooserFinish(JNIEnv*, jobject, jlong requestPtr);
    static void nativeColorChooserCancel(JNIEnv*, jobject, jlong requestPtr);
    static void nativeAuthenticate(
        JNIEnv*, jobject, jlong nativePtr, jlong requestId, jstring username, jstring password);
    static void nativeAuthenticationCancel(JNIEnv*, jobject, jlong nativePtr, jlong requestId);
    static void nativeSetIsMuted(JNIEnv*, jobject, jlong nativePtr, jboolean muted);
    static jboolean nativeIsMuted(JNIEnv*, jobject, jlong nativePtr);
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
    // WEBKIT_LOAD_REDIRECTED and WEBKIT_LOAD_COMMITTED are intentionally not surfaced, but a page that commits is
    // reported as visited.
    if (loadEvent == WEBKIT_LOAD_STARTED) {
        // For a load the page starts itself, a link or a script, the web view's URI is still the old one here.
        auto jUri = JNI::String(
            bridge->m_navigationPending ? bridge->m_navigationUri.c_str() : webkit_web_view_get_uri(webView));
        bridge->m_navigationPending = false;
        bridge->m_redirectCount = 0;
        bridge->m_failedLoadUri.clear();
        // The load of the app was decided on by now.
        bridge->m_appLoadUri.clear();
        bridge->m_loadStarting = true;
        if (!getJNIWebKitWebViewCache().m_onLoadStarted.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
            Logging::logError("cannot deliver WebKitWebView onLoadStarted callback");
    } else if (loadEvent == WEBKIT_LOAD_REDIRECTED) {
        bridge->m_redirectCount++;
    } else if (loadEvent == WEBKIT_LOAD_COMMITTED) {
        bridge->m_loadStarting = false;
        // The new page ends the frames of the old one and their loads, so their navigations no longer count. The
        // downloads of the old page have taken their counts by then: WebKit tells of a download right after it is
        // decided on, not once the server answers.
        bridge->m_navigationCounts.clear();
        bridge->m_navigationCountsFull = false;
        // The first page after the end of the web process has its title reported, also when WebKit's stays empty.
        if (bridge->m_titlePending || bridge->m_webProcessTerminated)
            reportTitle(bridge);
        bridge->m_webProcessTerminated = false;
        reportHistory(bridge, true);
    } else if (loadEvent == WEBKIT_LOAD_FINISHED) {
        // Also the end of a load that failed or stopped before it committed. After the end of the web process, the view
        // keeps the title and the address of its page until a new one commits.
        bridge->m_loadStarting = false;
        if (bridge->m_titlePending && !bridge->m_webProcessTerminated)
            reportTitle(bridge);
        const char* uri = webkit_web_view_get_uri(webView);
        if (bridge->m_webProcessTerminated && (!uri || !*uri))
            uri = bridge->m_historyUri.c_str();
        auto jUri = JNI::String(bridge->m_failedLoadUri.empty() ? uri : bridge->m_failedLoadUri.c_str());
        bridge->m_failedLoadUri.clear();
        if (!getJNIWebKitWebViewCache().m_onLoadFinished.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
            Logging::logError("cannot deliver WebKitWebView onLoadFinished callback");
    }
}

void JNIWebKitWebViewCache::onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    // A navigation within the page is decided on, but it does not start a load. The address also changes back when a
    // load is stopped, maybe for a navigation that is decided on and has not started yet.
    const char* uri = webkit_web_view_get_uri(WEBKIT_WEB_VIEW(obj));
    // The Java side always has an address, and WebKit has none until its first one. It empties it when the web
    // process ends, and the view keeps the address of its page then.
    if (!uri || !*uri)
        return;
    if (!webkit_web_view_is_loading(WEBKIT_WEB_VIEW(obj)) && bridge->m_navigationUri == uri)
        bridge->m_navigationPending = false;

    auto jUri = JNI::String(uri);
    if (!getJNIWebKitWebViewCache().m_onUriChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
        Logging::logError("cannot deliver WebKitWebView onUriChanged callback");

    if (!webkit_web_view_is_loading(WEBKIT_WEB_VIEW(obj)))
        reportHistory(bridge);
}

void JNIWebKitWebViewCache::onIsLoadingChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    if (!webkit_web_view_is_loading(WEBKIT_WEB_VIEW(obj)))
        reportHistory(bridge);
}

// A navigation within the page that keeps the address, such as history.pushState to the same address, or a navigation
// in a frame, changes only the Back/Forward list. One that changes the address changes the list first, and is reported
// when the address changes.
void JNIWebKitWebViewCache::onBackForwardListChanged(
    WebKitBackForwardList* list, WebKitBackForwardListItem*, gpointer, WebKitWebViewBridge* bridge)
{
    if (webkit_web_view_is_loading(bridge->m_webView))
        return;
    auto* item = webkit_back_forward_list_get_current_item(list);
    if (item && !g_strcmp0(webkit_back_forward_list_item_get_uri(item), webkit_web_view_get_uri(bridge->m_webView)))
        reportHistory(bridge);
}

// Reports a navigation once it is done, with the address and the Back/Forward state it leaves, as Android does: a new
// page when it commits, also for a reload, as WebKit's error page for a load that fails does. A navigation within the
// page has no load events, so it is reported when the address or the Back/Forward list changes while nothing loads, or
// when a load WebKit was asked for ends without a new page, such as going back to an address within the page. A load
// stopped before it commits changes nothing.
void JNIWebKitWebViewCache::reportHistory(WebKitWebViewBridge* bridge, bool committed)
{
    const char* uri = webkit_web_view_get_uri(bridge->m_webView);
    bool canGoBack = webkit_web_view_can_go_back(bridge->m_webView);
    bool canGoForward = webkit_web_view_can_go_forward(bridge->m_webView);
    // A page that commits is always a navigation, also a reload or a load of the same address. Emptying the address
    // when the web process ends is none.
    if (!uri || !*uri
        || (!committed && bridge->m_historyUri == uri && bridge->m_historyCanGoBack == canGoBack
            && bridge->m_historyCanGoForward == canGoForward))
        return;

    bridge->m_historyUri = uri;
    bridge->m_historyCanGoBack = canGoBack;
    bridge->m_historyCanGoForward = canGoForward;
    auto jUri = JNI::String(uri);
    if (!getJNIWebKitWebViewCache().m_onHistoryChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri),
            static_cast<jboolean>(canGoBack), static_cast<jboolean>(canGoForward)))
        Logging::logError("cannot deliver WebKitWebView onHistoryChanged callback");
}

// Whether the page plays media with sound or Web Audio, also while the view or the media is muted. Media at volume 0,
// paused, or paused while the page is hidden does not count, and neither does a page whose web process ended.
void JNIWebKitWebViewCache::onIsPlayingAudioChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    bool isPlayingAudio = webkit_web_view_is_playing_audio(WEBKIT_WEB_VIEW(obj));
    if (!getJNIWebKitWebViewCache().m_onIsPlayingAudioChanged.invoke(
            bridge->m_javaRef.get(), static_cast<jboolean>(isPlayingAudio)))
        Logging::logError("cannot deliver WebKitWebView onIsPlayingAudioChanged callback");
}

void JNIWebKitWebViewCache::onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    double progress = webkit_web_view_get_estimated_load_progress(WEBKIT_WEB_VIEW(obj));
    if (!getJNIWebKitWebViewCache().m_onEstimatedLoadProgress.invoke(bridge->m_javaRef.get(), progress))
        Logging::logError("cannot deliver WebKitWebView onEstimatedLoadProgress callback");
}

// WebKit empties the title when a new page commits, which can be before it tells the address of that page. A page
// without a title has its address as its title, so a title that changes while a load starts waits for the commit. It
// also empties the title when the web process ends, and the view keeps the title of its page until the next commit.
void JNIWebKitWebViewCache::onTitleChanged(GObject*, GParamSpec*, WebKitWebViewBridge* bridge)
{
    if (bridge->m_loadStarting || bridge->m_webProcessTerminated)
        bridge->m_titlePending = true;
    else
        reportTitle(bridge);
}

void JNIWebKitWebViewCache::reportTitle(WebKitWebViewBridge* bridge)
{
    bridge->m_titlePending = false;
    // WebKit has no title until it knows the first one. A page without a title has an empty one.
    const char* title = webkit_web_view_get_title(bridge->m_webView);
    auto jTitle = JNI::String(title ? title : "");
    if (!getJNIWebKitWebViewCache().m_onTitleChanged.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jTitle)))
        Logging::logError("cannot deliver WebKitWebView onTitleChanged callback");
}

gboolean JNIWebKitWebViewCache::onDecidePolicy(WebKitWebView* webView, WebKitPolicyDecision* decision,
    WebKitPolicyDecisionType decisionType, WebKitWebViewBridge* bridge)
{
    if (!decision)
        return FALSE;

    if (decisionType == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION
        || decisionType == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
        auto* action
            = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(decision));
        if (!action)
            return FALSE;
        // A navigation that opens a new window has no target frame yet. It loads in the main frame of that window.
        bool isNewWindow = decisionType == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION;
        WebKitFrameInfo* frame = webkit_navigation_action_get_target_frame_info(action);
        bool isMainFrame = isNewWindow || (frame && webkit_frame_info_is_main_frame(frame));
        auto* request = webkit_navigation_action_get_request(action);
        const char* uri = webkit_uri_request_get_uri(request);
        // WebKit decides on the load the app asked for before any other navigation in the main frame, unless the page
        // started one meanwhile.
        bool isAppLoad = false;
        // The page of a window that a user gesture opened.
        bool isOpenedByUser = false;
        if (isMainFrame && !isNewWindow) {
            isAppLoad = !bridge->m_appLoadUri.empty() && uri && bridge->m_appLoadUri == uri;
            bridge->m_appLoadUri.clear();
            isOpenedByUser = bridge->m_windowLoadGesture && uri && bridge->m_windowLoadUri == uri;
            bridge->m_windowLoadUri.clear();
            bridge->m_windowLoadGesture = false;
        }
        // The app may destroy the WebView while it is asked, which deletes the bridge.
        g_autoptr(WebKitWebView) protectedWebView = WEBKIT_WEB_VIEW(g_object_ref(webView));
        if (shouldOverrideUrlLoading(bridge, action, isMainFrame, isAppLoad)) {
            webkit_policy_decision_ignore(decision);
            return TRUE;
        }
        bridge = static_cast<WebKitWebViewBridge*>(g_object_get_qdata(G_OBJECT(webView), webViewBridgeQuark()));
        // A new window loads its page in a web view of its own, which decides on it again (onCreate).
        if (!bridge || isNewWindow)
            return FALSE;
        if (isMainFrame) {
            const char* method = webkit_uri_request_get_http_method(request);
            bridge->m_navigationUri = uri ? uri : "";
            bridge->m_navigationMethod = method ? method : "GET";
            bridge->m_navigationPending = true;
        }
        countNavigation(bridge, action, isMainFrame, isAppLoad || isOpenedByUser);
        return FALSE;
    }

    if (decisionType != WEBKIT_POLICY_DECISION_TYPE_RESPONSE)
        return FALSE;

    auto* responseDecision = WEBKIT_RESPONSE_POLICY_DECISION(decision);
    auto* uriResponse = webkit_response_policy_decision_get_response(responseDecision);
    guint statusCode = webkit_uri_response_get_status_code(uriResponse);
    if (statusCode < 400) {
        // WebKit saves a response sent as an attachment, but does nothing with a page of a type it cannot show, such
        // as an archive, in the main frame or in a frame: save that too, unless it is an error, which WebKit does not
        // save, or No Content, which leaves the page as it is. Responses that are not HTTP, such as those of data:
        // addresses, have no status.
        if (!webkit_response_policy_decision_is_mime_type_supported(responseDecision) && statusCode != 204
            && statusCode != 205) {
            webkit_policy_decision_download(decision);
            return TRUE;
        }
        return FALSE;
    }

    auto* uriRequest = webkit_response_policy_decision_get_request(responseDecision);
    const char* method = webkit_uri_request_get_http_method(uriRequest);
    auto jUri = JNI::String(webkit_uri_request_get_uri(uriRequest));
    auto jMethod = JNI::String(method ? method : "GET");
    auto jMimeType = JNI::String(webkit_uri_response_get_mime_type(uriResponse));
    bool isMainFrame = webkit_response_policy_decision_is_main_frame_main_resource(responseDecision);
    if (!getJNIWebKitWebViewCache().m_onReceivedHttpError.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri),
            static_cast<jstring>(jMethod), static_cast<jstring>(jMimeType), static_cast<jint>(statusCode),
            static_cast<jboolean>(isMainFrame)))
        Logging::logError("cannot deliver WebKitWebView onReceivedHttpError callback");

    return FALSE;
}

// Asks the app about the navigations of the page, as android.webkit.WebView does: not about its own loads, nor about
// going back, forward or reloading, nor about POST requests.
bool JNIWebKitWebViewCache::shouldOverrideUrlLoading(
    WebKitWebViewBridge* bridge, WebKitNavigationAction* action, bool isMainFrame, bool isAppLoad)
{
    switch (webkit_navigation_action_get_navigation_type(action)) {
    case WEBKIT_NAVIGATION_TYPE_BACK_FORWARD:
    case WEBKIT_NAVIGATION_TYPE_RELOAD:
    case WEBKIT_NAVIGATION_TYPE_FORM_RESUBMITTED:
        return false;
    default:
        break;
    }
    auto* request = webkit_navigation_action_get_request(action);
    const char* uri = webkit_uri_request_get_uri(request);
    const char* method = webkit_uri_request_get_http_method(request);
    if (isAppLoad || !uri || (method && g_ascii_strcasecmp(method, "GET")))
        return false;

    auto jUri = JNI::String(uri);
    auto jMethod = JNI::String(method ? method : "GET");
    return getJNIWebKitWebViewCache().m_shouldOverrideUrlLoading.invoke(bridge->m_javaRef.get(),
        static_cast<jstring>(jUri), static_cast<jstring>(jMethod), static_cast<jboolean>(isMainFrame),
        static_cast<jboolean>(webkit_navigation_action_is_user_gesture(action)),
        static_cast<jboolean>(webkit_navigation_action_is_redirect(action)));
}

// The most addresses whose navigations a page counts. A page that goes to more has no download with a gesture.
static constexpr size_t maxNavigationAddresses = 1024;

// The key of the navigations to an address and of its downloads. A download has the address of its network request,
// which may have lost the fragment of the navigation's, or gone from http to https, so those do not count. Addresses
// with the same key share their counts, which only ever takes gestures away.
static size_t navigationKey(std::string_view uri)
{
    uri = uri.substr(0, uri.find('#'));
    for (std::string_view scheme : {"http://", "https://"}) {
        if (uri.substr(0, scheme.size()) == scheme) {
            uri.remove_prefix(scheme.size());
            break;
        }
    }
    return std::hash<std::string_view> {}(uri);
}

// Counts a navigation of the page or a frame, with or without a user gesture, for the download it may become. The loads
// that the user asked for count as gestures: those of the app, and the pages of the windows that a user gesture opened.
// So do the server redirects of a main frame navigation that has a gesture.
void JNIWebKitWebViewCache::countNavigation(
    WebKitWebViewBridge* bridge, WebKitNavigationAction* action, bool isMainFrame, bool isUserLoad)
{
    const char* uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(action));
    if (!uri)
        return;
    bool hasGesture = webkit_navigation_action_is_user_gesture(action) || isUserLoad;
    if (isMainFrame) {
        if (webkit_navigation_action_is_redirect(action))
            hasGesture = hasGesture || bridge->m_mainFrameGesture;
        else
            bridge->m_mainFrameGesture = hasGesture;
    }
    if (bridge->m_navigationCountsFull)
        return;
    auto& counts = bridge->m_navigationCounts;
    size_t key = navigationKey(uri);
    auto it = counts.find(key);
    if (it == counts.end()) {
        if (counts.size() == maxNavigationAddresses) {
            counts.clear();
            bridge->m_navigationCountsFull = true;
            return;
        }
        it = counts.emplace(key, WebKitWebViewBridge::NavigationCount {}).first;
    }
    if (hasGesture)
        it->second.withGesture++;
    else
        it->second.withoutGesture++;
}

// Whether a user gesture started the download of an address. It comes from one of the counted navigations to that
// address: a link with a download attribute, or the navigation that got the response, after the redirects of the
// server, and takes it off the counts. While a navigation without a gesture to the address is counted, the download
// may be the page's own, also when the user tapped a link to the same address in another frame, so it has no gesture.
// It then takes off a navigation with a gesture if there is one, as it may be that one's, so that a later download of
// the address, which may be the page's, still finds the page's navigation counted.
bool JNIWebKitWebViewCache::takeNavigationGesture(WebKitWebViewBridge* bridge, const char* uri)
{
    auto& counts = bridge->m_navigationCounts;
    auto it = uri ? counts.find(navigationKey(uri)) : counts.end();
    if (it == counts.end())
        return false;
    auto& count = it->second;
    bool hasGesture = !count.withoutGesture;
    if (count.withGesture)
        count.withGesture--;
    else
        count.withoutGesture--;
    if (!count.withGesture && !count.withoutGesture)
        counts.erase(it);
    return hasGesture;
}

// The error codes of android.webkit.WebViewClient, which WebViewClient's are.
enum class LoadErrorCode : jint {
    None = 0,
    Unknown = -1,
    HostLookup = -2,
    Connect = -6,
    Io = -7,
    Timeout = -8,
    RedirectLoop = -9,
    UnsupportedScheme = -10,
    FailedSslHandshake = -11,
    FileNotFound = -14,
};

// The WebViewClient error code for a load that failed with this error, or None if the load was cancelled, or handed
// to the media player, which apps are not told as an error, as with android.webkit.WebView.
static LoadErrorCode loadErrorCode(const GError* error)
{
    if (g_error_matches(error, WEBKIT_NETWORK_ERROR, WEBKIT_NETWORK_ERROR_CANCELLED)
        || g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED)
        || g_error_matches(error, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE)
        || g_error_matches(error, WEBKIT_MEDIA_ERROR, WEBKIT_MEDIA_ERROR_WILL_HANDLE_LOAD))
        return LoadErrorCode::None;

    if (error->domain == G_RESOLVER_ERROR)
        return LoadErrorCode::HostLookup;
    if (error->domain == G_TLS_ERROR)
        return LoadErrorCode::FailedSslHandshake;
    if (error->domain == WEBKIT_NETWORK_ERROR) {
        switch (error->code) {
        case WEBKIT_NETWORK_ERROR_UNKNOWN_PROTOCOL:
            return LoadErrorCode::UnsupportedScheme;
        case WEBKIT_NETWORK_ERROR_FILE_DOES_NOT_EXIST:
            return LoadErrorCode::FileNotFound;
        case WEBKIT_NETWORK_ERROR_HTTPS_UPGRADE_REDIRECT_LOOP:
            return LoadErrorCode::RedirectLoop;
        default:
            return LoadErrorCode::Unknown;
        }
    }
    if (error->domain == WEBKIT_POLICY_ERROR && error->code == WEBKIT_POLICY_ERROR_CANNOT_SHOW_URI)
        return LoadErrorCode::UnsupportedScheme;
    // WebKit follows redirects itself, and fails with libsoup's error after too many.
    if (g_error_matches(error, SOUP_SESSION_ERROR, SOUP_SESSION_ERROR_TOO_MANY_REDIRECTS))
        return LoadErrorCode::RedirectLoop;
    if (error->domain != G_IO_ERROR)
        return LoadErrorCode::Unknown;

    switch (error->code) {
    case G_IO_ERROR_HOST_NOT_FOUND:
        return LoadErrorCode::HostLookup;
    case G_IO_ERROR_CONNECTION_REFUSED:
    case G_IO_ERROR_HOST_UNREACHABLE:
    case G_IO_ERROR_NETWORK_UNREACHABLE:
        return LoadErrorCode::Connect;
    case G_IO_ERROR_TIMED_OUT:
        return LoadErrorCode::Timeout;
    case G_IO_ERROR_NOT_FOUND:
        return LoadErrorCode::FileNotFound;
    default:
        return LoadErrorCode::Io;
    }
}

gboolean JNIWebKitWebViewCache::onLoadFailed(
    WebKitWebView*, WebKitLoadEvent, const gchar* failingUri, GError* error, WebKitWebViewBridge* bridge)
{
    // A load that is cancelled leaves the navigation alone: when a page goes elsewhere while it is still loading, its
    // own load fails, as it is stopped, and the new one, maybe to the same address, must keep its address.
    LoadErrorCode code = error ? loadErrorCode(error) : LoadErrorCode::Unknown;
    if (code == LoadErrorCode::None)
        return FALSE;
    // The navigation that failed, if WebKit decided on it, maybe before its load started.
    bool isNavigation = failingUri && bridge->m_navigationUri == failingUri;
    if (isNavigation)
        bridge->m_navigationPending = false;
    // WebKit stops following redirects after 20, with an error that has neither a domain nor a code.
    static constexpr unsigned maximumRedirects = 20;
    if (code == LoadErrorCode::Unknown && bridge->m_redirectCount >= maximumRedirects)
        code = LoadErrorCode::RedirectLoop;
    bridge->m_failedLoadUri = failingUri ? failingUri : "";

    auto jUri = JNI::String(failingUri ? failingUri : "");
    auto jMethod = JNI::String(isNavigation ? bridge->m_navigationMethod.c_str() : "GET");
    auto jDescription = JNI::String(error && error->message ? error->message : "");
    if (!getJNIWebKitWebViewCache().m_onLoadFailed.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri),
            static_cast<jstring>(jMethod), static_cast<jint>(code), static_cast<jstring>(jDescription)))
        Logging::logError("cannot deliver WebKitWebView onLoadFailed callback");

    // WebKit shows its error page.
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

// The web process of the page ended: it crashed, Android ended it, as it does to free memory, WebKit ended it for using
// too much memory, or the app asked for it. A load in progress ends with it, and is not reported as finished.
void JNIWebKitWebViewCache::onWebProcessTerminated(
    WebKitWebView*, WebKitWebProcessTerminationReason reason, WebKitWebViewBridge* bridge)
{
    bridge->m_webProcessTerminated = true;
    bridge->m_loadStarting = false;
    bridge->m_navigationPending = false;
    bridge->m_failedLoadUri.clear();

    // WebKit ends every load of the view with it, also one in another web process that would have replaced the page,
    // but cancels a request for credentials only when a load starts or ends. So the requests are cancelled here, which
    // tells the app, as when the page goes elsewhere, to close its dialog.
    std::vector<WebKitAuthenticationRequest*> requests;
    requests.reserve(bridge->m_authenticationRequests.size());
    for (const auto& entry : bridge->m_authenticationRequests)
        requests.push_back(WEBKIT_AUTHENTICATION_REQUEST(g_object_ref(entry.second)));
    for (auto* request : requests) {
        webkit_authentication_request_cancel(request);
        g_object_unref(request);
    }

    if (!getJNIWebKitWebViewCache().m_onWebProcessTerminated.invoke(bridge->m_javaRef.get(), static_cast<jint>(reason)))
        Logging::logError("cannot deliver WebKitWebView onWebProcessTerminated callback");
}

// WebKit asks for the view of a new window while it makes the page of that window, which needs the view before this
// returns, so the app makes it right away. It must be related to this view: WebKit then puts its page in the same web
// process, with this page as its opener unless the page asked for none, as rel=noopener does. The bridge of the new
// view keeps the view, as WebKit takes no reference of its own.
WebKitWebView* JNIWebKitWebViewCache::onCreate(
    WebKitWebView* webView, WebKitNavigationAction* action, WebKitWebViewBridge* bridge)
{
    // The app may destroy this view while it is asked, which deletes the bridge and drops its reference on the view.
    // WebKit still uses the view once the signal returns, so keep a reference until the main loop runs again.
    g_idle_add_full(
        G_PRIORITY_DEFAULT, +[](gpointer) -> gboolean { return G_SOURCE_REMOVE; }, g_object_ref(webView),
        g_object_unref);

    bool isUserGesture = webkit_navigation_action_is_user_gesture(action);
    jlong webViewPtr = getJNIWebKitWebViewCache().m_onCreateWindow.invoke(
        bridge->m_javaRef.get(), static_cast<jboolean>(isUserGesture));
    auto* newWebView = JNI::from_jlong<WebKitWebView>(webViewPtr);
    // WebKit decides on the navigation of the window's page again, in the new view, but without the gesture of the link
    // or the script that opened the window. The new view keeps it for the download that the page may become.
    auto* newBridge = newWebView
        ? static_cast<WebKitWebViewBridge*>(g_object_get_qdata(G_OBJECT(newWebView), webViewBridgeQuark()))
        : nullptr;
    if (newBridge) {
        const char* uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(action));
        newBridge->m_windowLoadUri = uri ? uri : "";
        newBridge->m_windowLoadGesture = isUserGesture;
    }
    return newWebView;
}

// Every web view of the network session hears of every download, and follows those of its own pages.
void JNIWebKitWebViewCache::onDownloadStarted(
    WebKitNetworkSession*, WebKitDownload* download, WebKitWebViewBridge* bridge)
{
    if (webkit_download_get_web_view(download) != bridge->m_webView)
        return;
    auto* request = webkit_download_get_request(download);
    bool hasGesture = takeNavigationGesture(bridge, request ? webkit_uri_request_get_uri(request) : nullptr);
    g_signal_connect(
        download, "decide-destination", G_CALLBACK(onDecideDownloadDestination), GINT_TO_POINTER(hasGesture));
}

// Once the response is known, the app is asked where to save the download, which waits for the answer. A download
// whose web view went meanwhile, or that the app could not be told of, is cancelled.
gboolean JNIWebKitWebViewCache::onDecideDownloadDestination(
    WebKitDownload* download, const char* suggestedFilename, gpointer hasGesture)
{
    auto* webView = webkit_download_get_web_view(download);
    auto* bridge = webView
        ? static_cast<WebKitWebViewBridge*>(g_object_get_qdata(G_OBJECT(webView), webViewBridgeQuark()))
        : nullptr;
    if (!bridge) {
        webkit_download_cancel(download);
        return TRUE;
    }

    // The address after the redirects of the server.
    auto* response = webkit_download_get_response(download);
    const char* uri = response ? webkit_uri_response_get_uri(response) : nullptr;
    if (!uri)
        uri = webkit_uri_request_get_uri(webkit_download_get_request(download));
    const char* mimeType = response ? webkit_uri_response_get_mime_type(response) : nullptr;
    guint64 length = response ? webkit_uri_response_get_content_length(response) : 0;

    jlong downloadPtr = createWebKitDownloadBridge(download);
    auto jUri = JNI::String(uri ? uri : "");
    auto jSuggestedFilename = JNI::String(suggestedFilename ? suggestedFilename : "");
    auto jMimeType = JNI::String(mimeType ? mimeType : "");
    if (!getJNIWebKitWebViewCache().m_onDownloadStarted.invoke(bridge->m_javaRef.get(), downloadPtr,
            static_cast<jstring>(jUri), static_cast<jstring>(jSuggestedFilename), static_cast<jstring>(jMimeType),
            length ? static_cast<jlong>(length) : -1, static_cast<jboolean>(GPOINTER_TO_INT(hasGesture)))
        || !isWebKitDownloadBridgeAttached(downloadPtr)) {
        Logging::logError("cannot deliver WebKitWebView onDownloadStarted callback");
        webkit_download_cancel(download);
    }
    return TRUE;
}

// Marks a file chooser request that Java answered, which released the reference taken for it.
static constexpr const char* fileChooserAnsweredKey = "wpeview-file-chooser-answered";

static void releaseAnsweredFileChooser(WebKitFileChooserRequest* request)
{
    g_object_set_data(G_OBJECT(request), fileChooserAnsweredKey, GINT_TO_POINTER(TRUE));
    g_object_unref(request);
}

// A file field of the page asks for files. Java takes the request, and answers it with nativeFileChooserSelect or
// nativeFileChooserCancel, which release it, also when the app does not choose any file.
gboolean JNIWebKitWebViewCache::onRunFileChooser(
    WebKitWebView*, WebKitFileChooserRequest* request, WebKitWebViewBridge* bridge)
{
    // The MIME types of the field's accept attribute, none if it takes any file.
    const gchar* const* mimeTypes = webkit_file_chooser_request_get_mime_types(request);
    size_t mimeTypeCount = 0;
    while (mimeTypes && mimeTypes[mimeTypeCount])
        mimeTypeCount++;
    auto jMimeTypes = JNI::ObjectArray<jstring>(JNI::TypedClass<jstring>().createArray(mimeTypeCount));
    for (size_t i = 0; i < mimeTypeCount; i++)
        jMimeTypes.setValue(i, JNI::String(mimeTypes[i]));

    auto requestPtr = reinterpret_cast<jlong>(g_object_ref(request));
    if (!getJNIWebKitWebViewCache().m_onRunFileChooser.invoke(bridge->m_javaRef.get(), requestPtr,
            static_cast<jstringArray>(jMimeTypes),
            static_cast<jboolean>(webkit_file_chooser_request_get_select_multiple(request)))) {
        Logging::logError("cannot deliver WebKitWebView onRunFileChooser callback");
        // Java answers the request when anything fails after it took it. If the call failed before, nothing else would.
        // WebKit still holds the request while it asks, so it can be checked after Java released it.
        if (!g_object_get_data(G_OBJECT(request), fileChooserAnsweredKey)) {
            webkit_file_chooser_request_cancel(request);
            g_object_unref(request);
        }
    }
    return TRUE;
}

// A color field of the page asks for a color. Java takes the request, and ends it with nativeColorChooserFinish or
// nativeColorChooserCancel. The page may also end it, when the field goes away or the page is left. WebKit tells each
// ending, which releases the request.
gboolean JNIWebKitWebViewCache::onRunColorChooser(
    WebKitWebView*, WebKitColorChooserRequest* request, WebKitWebViewBridge* bridge)
{
    WebKitColor color;
    webkit_color_chooser_request_get_color(request, &color);
    auto channel = [](double value) { return static_cast<uint32_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255)); };
    const uint32_t argb
        = (channel(color.alpha) << 24) | (channel(color.red) << 16) | (channel(color.green) << 8) | channel(color.blue);

    g_object_ref(request);
    g_signal_connect(request, "finished", G_CALLBACK(onColorChooserFinished), bridge);
    if (!getJNIWebKitWebViewCache().m_onRunColorChooser.invoke(bridge->m_javaRef.get(),
            reinterpret_cast<jlong>(request), static_cast<jint>(argb),
            static_cast<jboolean>(webkit_color_chooser_request_get_supports_alpha(request)))) {
        Logging::logError("cannot deliver WebKitWebView onRunColorChooser callback");
        // Nothing else would end it. Once ended, it cannot be ended again.
        webkit_color_chooser_request_cancel(request);
    }
    return TRUE;
}

void JNIWebKitWebViewCache::onColorChooserFinished(WebKitColorChooserRequest* request, WebKitWebViewBridge* bridge)
{
    g_signal_handlers_disconnect_by_func(request, reinterpret_cast<gpointer>(onColorChooserFinished), bridge);
    if (!getJNIWebKitWebViewCache().m_onColorChooserFinished.invoke(
            bridge->m_javaRef.get(), reinterpret_cast<jlong>(request)))
        Logging::logError("cannot deliver WebKitWebView onColorChooserFinished callback");
    g_object_unref(request);
}

// A server or a proxy asks for a user name and password. The load waits until the app answers.
gboolean JNIWebKitWebViewCache::onAuthenticate(
    WebKitWebView*, WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge)
{
    switch (webkit_authentication_request_get_scheme(request)) {
    case WEBKIT_AUTHENTICATION_SCHEME_CLIENT_CERTIFICATE_REQUESTED:
    case WEBKIT_AUTHENTICATION_SCHEME_CLIENT_CERTIFICATE_PIN_REQUESTED:
    case WEBKIT_AUTHENTICATION_SCHEME_SERVER_TRUST_EVALUATION_REQUESTED:
        // Not a user name and password. WPEView has no client certificates to offer, so the connection goes on
        // without one, and the server decides whether that is enough. WebKit does not ask to evaluate the trust of a
        // server with libsoup, which reports a certificate it cannot trust as a load failure, but such a request would
        // not be one for a user name and password either.
        webkit_authentication_request_authenticate(request, nullptr);
        return TRUE;
    default:
        break;
    }

    jlong requestId = ++bridge->m_lastAuthenticationRequestId;
    bridge->m_authenticationRequests.emplace(requestId, WEBKIT_AUTHENTICATION_REQUEST(g_object_ref(request)));
    g_signal_connect(request, "cancelled", G_CALLBACK(onAuthenticationCancelled), bridge);

    // The scheme of the address that is loaded. WebKit gives it also when a proxy asks, as it does not tell how the
    // proxy is reached.
    WebKitSecurityOrigin* origin = webkit_authentication_request_get_security_origin(request);
    const char* scheme = origin ? webkit_security_origin_get_protocol(origin) : nullptr;
    auto jScheme = JNI::String(scheme ? scheme : "");
    if (origin)
        webkit_security_origin_unref(origin);
    const char* host = webkit_authentication_request_get_host(request);
    const char* realm = webkit_authentication_request_get_realm(request);
    auto jHost = JNI::String(host ? host : "");
    auto jRealm = JNI::String(realm ? realm : "");
    if (!getJNIWebKitWebViewCache().m_onAuthenticate.invoke(bridge->m_javaRef.get(), requestId,
            static_cast<jstring>(jHost), static_cast<jint>(webkit_authentication_request_get_port(request)),
            static_cast<jstring>(jScheme), static_cast<jstring>(jRealm),
            static_cast<jboolean>(webkit_authentication_request_is_for_proxy(request)),
            static_cast<jboolean>(webkit_authentication_request_is_retry(request)))) {
        Logging::logError("cannot deliver WebKitWebView onAuthenticate callback");
        answerAuthentication(bridge, requestId, nullptr);
    }

    return TRUE;
}

// WebKit cancels a request when its load ends or another one starts, so that the app can close its dialog. It keeps
// its own reference to the request meanwhile.
void JNIWebKitWebViewCache::onAuthenticationCancelled(WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge)
{
    auto& requests = bridge->m_authenticationRequests;
    auto it = std::find_if(
        requests.begin(), requests.end(), [request](const auto& entry) { return entry.second == request; });
    if (it == requests.end())
        return;
    jlong requestId = it->first;
    requests.erase(it);
    g_signal_handlers_disconnect_by_data(request, bridge);
    g_object_unref(request);
    if (!getJNIWebKitWebViewCache().m_onAuthenticationCancelled.invoke(bridge->m_javaRef.get(), requestId))
        Logging::logError("cannot deliver WebKitWebView onAuthenticationCancelled callback");
}

// Answers a request the app was asked about, unless WebKit cancelled it meanwhile. Without a credential the load goes
// on without one, and shows what the server sent with its request for one, such as its 401 page.
void JNIWebKitWebViewCache::answerAuthentication(
    WebKitWebViewBridge* bridge, jlong requestId, WebKitCredential* credential)
{
    auto it = bridge->m_authenticationRequests.find(requestId);
    if (it == bridge->m_authenticationRequests.end())
        return;
    WebKitAuthenticationRequest* request = it->second;
    bridge->m_authenticationRequests.erase(it);
    g_signal_handlers_disconnect_by_data(request, bridge);
    webkit_authentication_request_authenticate(request, credential);
    g_object_unref(request);
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

    return reinterpret_cast<jlong>(createBridge(env, jniWebView, webView));
}

// The view of a new window, made while the related view emits create. It takes the display, the web context, the
// network session and whether it is controlled by automation from the related view, which must not be given again.
jlong JNIWebKitWebViewCache::nativeInitRelated(JNIEnv* env, jobject jniWebView, jlong relatedPtr, jlong settingsPtr)
{
    auto* related = JNI::from_jlong<WebKitWebViewBridge>(relatedPtr);
    auto* settings = JNI::from_jlong<WebKitSettings>(settingsPtr);

    g_assert(related);
    if (!related)
        return 0;

    auto* webView = WEBKIT_WEB_VIEW(
        g_object_new(WEBKIT_TYPE_WEB_VIEW, "related-view", related->m_webView, "settings", settings, nullptr));
    return reinterpret_cast<jlong>(createBridge(env, jniWebView, webView));
}

WebKitWebViewBridge* JNIWebKitWebViewCache::createBridge(JNIEnv* env, jobject jniWebView, WebKitWebView* webView)
{
    auto* bridge = new WebKitWebViewBridge(webView, env, jniWebView);
    bridge->m_signalIds = {
        g_signal_connect(webView, "close", G_CALLBACK(onClose), bridge),
        g_signal_connect(webView, "create", G_CALLBACK(onCreate), bridge),
        g_signal_connect(webView, "decide-policy", G_CALLBACK(onDecidePolicy), bridge),
        g_signal_connect(webView, "load-changed", G_CALLBACK(onLoadChanged), bridge),
        g_signal_connect(webView, "load-failed", G_CALLBACK(onLoadFailed), bridge),
        g_signal_connect(webView, "notify::estimated-load-progress", G_CALLBACK(onEstimatedLoadProgress), bridge),
        g_signal_connect(webView, "notify::title", G_CALLBACK(onTitleChanged), bridge),
        g_signal_connect(webView, "notify::uri", G_CALLBACK(onUriChanged), bridge),
        g_signal_connect(webView, "notify::is-loading", G_CALLBACK(onIsLoadingChanged), bridge),
        g_signal_connect(webView, "script-dialog", G_CALLBACK(onScriptDialog), bridge),
        g_signal_connect(webView, "web-process-terminated", G_CALLBACK(onWebProcessTerminated), bridge),
        g_signal_connect(webView, "run-file-chooser", G_CALLBACK(onRunFileChooser), bridge),
        g_signal_connect(webView, "run-color-chooser", G_CALLBACK(onRunColorChooser), bridge),
        g_signal_connect(webView, "authenticate", G_CALLBACK(onAuthenticate), bridge),
        g_signal_connect(webView, "notify::is-playing-audio", G_CALLBACK(onIsPlayingAudioChanged), bridge),
    };
    bridge->m_backForwardListChangedId = g_signal_connect(
        webkit_web_view_get_back_forward_list(webView), "changed", G_CALLBACK(onBackForwardListChanged), bridge);
    bridge->m_downloadStartedId = g_signal_connect(
        webkit_web_view_get_network_session(webView), "download-started", G_CALLBACK(onDownloadStarted), bridge);

    return bridge;
}

void JNIWebKitWebViewCache::nativeDestroy(JNIEnv*, jobject, jlong nativePtr)
{
    delete JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
}

// WebKit takes the address of a load the app asks for as the web view's URI at once, as it will be in the request.
// A javascript: address runs in the page, and is no navigation.
static void rememberAppLoad(WebKitWebViewBridge* bridge)
{
    const char* uri = webkit_web_view_get_uri(bridge->m_webView);
    bridge->m_appLoadUri = uri && g_ascii_strncasecmp(uri, "javascript:", 11) ? uri : "";
}

void JNIWebKitWebViewCache::nativeLoadUrl(JNIEnv*, jobject, jlong nativePtr, jstring url)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    webkit_web_view_load_uri(bridge->m_webView, JNI::String(url).getContent().get());
    rememberAppLoad(bridge);
}

void JNIWebKitWebViewCache::nativeLoadHtml(JNIEnv*, jobject, jlong nativePtr, jstring content, jstring baseUri)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    webkit_web_view_load_html(
        bridge->m_webView, JNI::String(content).getContent().get(), JNI::String(baseUri).getContent().get());
    rememberAppLoad(bridge);
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

// A script with a world name runs in that isolated world, apart from the scripts of the page.
void JNIWebKitWebViewCache::nativeEvaluateJavascript(JNIEnv* env, jobject, jlong nativePtr, jstring script,
    jstring worldName, JNIWebKitWebViewEvalCallbackHolder callbackHolder)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    auto* holder
        = callbackHolder ? new JNI::GlobalRef<JNIWebKitWebViewEvalCallbackHolder>(env, callbackHolder) : nullptr;
    // Each content is released while its string still holds the Java reference.
    JNI::String worldString(worldName);
    auto world = worldString.getContent();
    webkit_web_view_evaluate_javascript(bridge->m_webView, JNI::String(script).getContent().get(), -1, world.get(),
        nullptr, nullptr, holder ? onEvalJavascriptReady : nullptr, holder);
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
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    // A prompt without text gives null, as when it is cancelled. An alert has no answer.
    if (type == WEBKIT_SCRIPT_DIALOG_PROMPT && text != nullptr)
        webkit_script_dialog_prompt_set_text(dialog, JNI::String(text).getContent().get());
    if (type == WEBKIT_SCRIPT_DIALOG_CONFIRM || type == WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM)
        webkit_script_dialog_confirm_set_confirmed(dialog, static_cast<gboolean>(confirm));
}

void JNIWebKitWebViewCache::nativeScriptDialogClose(JNIEnv*, jobject, jlong dialogPtr)
{
    // Releases the reference taken in onScriptDialog; the dialog is closed once its refcount reaches zero.
    webkit_script_dialog_unref(reinterpret_cast<WebKitScriptDialog*>(dialogPtr)); // NOLINT(performance-no-int-to-ptr)
}

void JNIWebKitWebViewCache::nativeTerminateWebProcess(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_terminate_web_process(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

// WebKit's session state of the view, which holds its Back/Forward list, serialized. Returns null, with Java's
// OutOfMemoryError pending, if the array cannot be allocated.
jbyteArray JNIWebKitWebViewCache::nativeSaveState(JNIEnv* env, jobject, jlong nativePtr)
{
    g_autoptr(WebKitWebViewSessionState) state
        = webkit_web_view_get_session_state(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
    g_autoptr(GBytes) bytes = webkit_web_view_session_state_serialize(state);
    gsize size = 0;
    const auto* data = static_cast<const jbyte*>(g_bytes_get_data(bytes, &size));
    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (array)
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), data);
    return array;
}

// Restores a state from nativeSaveState, which replaces the Back/Forward list, and loads its current page: WebKit
// restores the list without loading anything. WebKit restores nothing from a state it cannot read, or that has no
// page.
jboolean JNIWebKitWebViewCache::nativeRestoreState(JNIEnv* env, jobject, jlong nativePtr, jbyteArray data)
{
    auto* webView = JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView;
    const jsize size = env->GetArrayLength(data);
    auto* copy = static_cast<jbyte*>(g_malloc(size));
    env->GetByteArrayRegion(data, 0, size, copy);
    g_autoptr(GBytes) bytes = g_bytes_new_take(copy, size);
    g_autoptr(WebKitWebViewSessionState) state = webkit_web_view_session_state_new(bytes);
    if (!state)
        return JNI_FALSE;

    // WebKit ignores a state without pages, and keeps the list of a view that has one. The current item is held, so
    // that a restored item cannot take its place in memory.
    auto* list = webkit_web_view_get_back_forward_list(webView);
    auto* previousItem = webkit_back_forward_list_get_current_item(list);
    if (previousItem)
        g_object_ref(previousItem);
    webkit_web_view_restore_session_state(webView, state);
    auto* item = webkit_back_forward_list_get_current_item(list);
    const bool restored = item && item != previousItem;
    if (previousItem)
        g_object_unref(previousItem);
    if (!restored)
        return JNI_FALSE;
    webkit_web_view_go_to_back_forward_list_item(webView, item);
    return JNI_TRUE;
}

jboolean JNIWebKitWebViewCache::nativeCanGoBack(JNIEnv*, jobject, jlong nativePtr)
{
    return webkit_web_view_can_go_back(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView) ? JNI_TRUE
                                                                                                   : JNI_FALSE;
}

jboolean JNIWebKitWebViewCache::nativeCanGoForward(JNIEnv*, jobject, jlong nativePtr)
{
    return webkit_web_view_can_go_forward(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView) ? JNI_TRUE
                                                                                                      : JNI_FALSE;
}

// The page keeps playing while it is muted, but the user hears nothing. WebKit keeps the state for the view, also for
// the pages it loads later and in a new web process.
void JNIWebKitWebViewCache::nativeSetIsMuted(JNIEnv*, jobject, jlong nativePtr, jboolean muted)
{
    webkit_web_view_set_is_muted(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView, muted);
}

jboolean JNIWebKitWebViewCache::nativeIsMuted(JNIEnv*, jobject, jlong nativePtr)
{
    return webkit_web_view_get_is_muted(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView) ? JNI_TRUE
                                                                                                    : JNI_FALSE;
}

void JNIWebKitWebViewCache::nativeFileChooserSelect(JNIEnv*, jobject, jlong requestPtr, jstringArray pathsArray)
{
    auto* request = JNI::from_jlong<WebKitFileChooserRequest>(requestPtr);
    g_autoptr(GStrvBuilder) builder = g_strv_builder_new();
    for (auto path : JNI::ObjectArray<jstring>(pathsArray).getReadOnlyContent()) {
        // The JNI::String must outlive its content, whose release uses the Java string.
        auto javaPath = JNI::String(path);
        auto pathContent = javaPath.getContent();
        if (!pathContent)
            continue;
        // WebKit decodes the escape sequences of the paths it is given, so a percent sign in a file name is escaped.
        std::string escapedPath;
        for (char c : std::string_view(pathContent.get())) {
            if (c == '%')
                escapedPath += "%25";
            else
                escapedPath += c;
        }
        g_strv_builder_add(builder, escapedPath.c_str());
    }
    g_auto(GStrv) paths = g_strv_builder_end(builder);
    webkit_file_chooser_request_select_files(request, paths);
    releaseAnsweredFileChooser(request);
}

void JNIWebKitWebViewCache::nativeFileChooserCancel(JNIEnv*, jobject, jlong requestPtr)
{
    auto* request = JNI::from_jlong<WebKitFileChooserRequest>(requestPtr);
    webkit_file_chooser_request_cancel(request);
    releaseAnsweredFileChooser(request);
}

void JNIWebKitWebViewCache::nativeColorChooserSetColor(
    JNIEnv*, jobject, jlong requestPtr, jfloat red, jfloat green, jfloat blue, jfloat alpha)
{
    // The field takes the color, and the page gets an input and a change event.
    const WebKitColor color = {red, green, blue, alpha};
    webkit_color_chooser_request_set_color(JNI::from_jlong<WebKitColorChooserRequest>(requestPtr), &color);
}

void JNIWebKitWebViewCache::nativeColorChooserFinish(JNIEnv*, jobject, jlong requestPtr)
{
    // Keeps the color set last.
    webkit_color_chooser_request_finish(JNI::from_jlong<WebKitColorChooserRequest>(requestPtr));
}

void JNIWebKitWebViewCache::nativeColorChooserCancel(JNIEnv*, jobject, jlong requestPtr)
{
    // Gives the field back the color it had when it asked.
    webkit_color_chooser_request_cancel(JNI::from_jlong<WebKitColorChooserRequest>(requestPtr));
}

// The credentials are kept for the session, in memory only, as WebKit sends them again for the other pages of the
// same realm.
void JNIWebKitWebViewCache::nativeAuthenticate(
    JNIEnv*, jobject, jlong nativePtr, jlong requestId, jstring username, jstring password)
{
    g_autoptr(WebKitCredential) credential = webkit_credential_new(JNI::String(username).getContent().get(),
        JNI::String(password).getContent().get(), WEBKIT_CREDENTIAL_PERSISTENCE_FOR_SESSION);
    answerAuthentication(JNI::from_jlong<WebKitWebViewBridge>(nativePtr), requestId, credential);
}

void JNIWebKitWebViewCache::nativeAuthenticationCancel(JNIEnv*, jobject, jlong nativePtr, jlong requestId)
{
    answerAuthentication(JNI::from_jlong<WebKitWebViewBridge>(nativePtr), requestId, nullptr);
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
