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
#include "WPEViewAndroid.h"
#include "WebKitDownloadBridge.h"
#include "WebKitImageBitmap.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <limits>
#include <memory>
#include <pthread.h>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <unordered_map>
#include <utility>
#include <vector>
#include <wpe/webkit.h>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebViewEvalCallbackHolder, "org/wpewebkit/wpe/WebKitWebView$EvalCallbackHolder");
DECLARE_JNI_CLASS_SIGNATURE(
    JNIWebKitWebViewSnapshotCallbackHolder, "org/wpewebkit/wpe/WebKitWebView$SnapshotCallbackHolder");
DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitWebView, "org/wpewebkit/wpe/WebKitWebView");

namespace {

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
        // The dialogs that Java did not answer get the answer of a dismissed one, while the view is still alive.
        for (auto& [id, dialog] : m_scriptDialogs)
            webkit_script_dialog_unref(dialog);
        m_scriptDialogs.clear();
        // The permission requests that Java did not answer are denied, and the queries get WebKit's own answer.
        for (auto& [id, request] : m_permissionRequests) {
            webkit_permission_request_deny(request);
            g_object_unref(request);
        }
        m_permissionRequests.clear();
        for (auto& [id, query] : m_permissionStateQueries)
            webkit_permission_state_query_unref(query);
        m_permissionStateQueries.clear();
        // WebKit closes the notifications of the page when the view goes, and no one would hear of it.
        for (auto& [id, notification] : m_notifications) {
            g_signal_handlers_disconnect_by_data(notification, this);
            g_object_unref(notification);
        }
        m_notifications.clear();
        if (m_backForwardListChangedId)
            g_signal_handler_disconnect(webkit_web_view_get_back_forward_list(m_webView), m_backForwardListChangedId);
        if (m_downloadStartedId)
            g_signal_handler_disconnect(webkit_web_view_get_network_session(m_webView), m_downloadStartedId);
        // The app was not told of these downloads yet, and no one would be.
        for (auto* download : m_appDownloads) {
            webkit_download_cancel(download);
            g_object_unref(download);
        }
        m_appDownloads.clear();
        // The find controller belongs to the web view, which may outlive the bridge.
        auto* findController = webkit_web_view_get_find_controller(m_webView);
        for (gulong id : m_findSignalIds)
            g_signal_handler_disconnect(findController, id);
        if (m_storedPageIconsSearch) {
            g_cancellable_cancel(m_storedPageIconsSearch);
            g_object_unref(m_storedPageIconsSearch);
        }
        if (m_pageIcons)
            webkit_image_list_unref(m_pageIcons);
        for (auto& [uri, icons] : m_recentPageIcons)
            webkit_image_list_unref(icons);
        // The app may destroy the WebView in one of its callbacks, while WebKit emits a signal of the web view or of a
        // request of its page, or while a call into WebKit has not returned, and WebKit then still uses the web view.
        // So it goes in the next iteration of the main loop, ahead of the sources of default priority. The main loop is
        // woken up for it, as the WebView may be destroyed outside of it, and nothing else may wake it up soon.
        g_idle_add_full(
            G_PRIORITY_HIGH, +[](gpointer) -> gboolean { return G_SOURCE_REMOVE; }, m_webView, g_object_unref);
        g_main_context_wakeup(nullptr);
    }

    WebKitWebView* m_webView;
    JNI::GlobalRef<JNIWebKitWebView> m_javaRef;
    std::vector<gulong> m_signalIds;
    gulong m_backForwardListChangedId {0};
    gulong m_downloadStartedId {0};
    // The downloads that the app asked for (nativeDownloadUri) until it is told of them, once their response is known
    // or they failed before it.
    std::vector<WebKitDownload*> m_appDownloads;
    std::vector<gulong> m_findSignalIds;
    // The address and the Back/Forward state last reported, none at first.
    std::string m_historyUri;
    bool m_historyCanGoBack {false};
    bool m_historyCanGoForward {false};
    // Whether a load started and has not committed yet, and whether a title waits for that load.
    bool m_loadStarting {false};
    bool m_titlePending {false};
    // Whether a page has committed in the view. Until then, its frames are not shown while it loads.
    bool m_pageCommitted {false};
    // The address and method of the last main frame navigation WebKit decided on. For a load the page starts itself,
    // WebKit changes its URI only after the start of the load is reported, so the start is reported with this address,
    // while the load has not started yet. Each navigation is decided on before its load starts, so one that never
    // starts is replaced by the next one. The method is for the failure of the load.
    std::string m_navigationUri;
    std::string m_navigationMethod;
    // The address of the content that the app asked to show in place of a page (nativeLoadAlternateHtml), until the
    // next load starts. WebKit decides on no navigation for it, but may still decide on one the app asked for just
    // before, which the content replaces and whose load never starts.
    std::string m_alternateLoadUri;
    bool m_navigationPending {false};
    // The server redirects of the current main frame load.
    unsigned m_redirectCount {0};
    // The address of the current main frame load once it failed. WebKit keeps the web view's URI at the previous page,
    // but the end of the load is reported with the address that failed, as with android.webkit.WebView.
    std::string m_failedLoadUri;
    // Whether the web process of the page ended, until the next page commits. WebKit then empties the address and the
    // title, but the page stays in the Back/Forward list, where a reload finds it, so the view keeps both meanwhile.
    bool m_webProcessTerminated {false};
    // The addresses of the loads the app asked for, as WebKit keeps them, in the order it asked, until WebKit decides
    // on them. The app may ask for a load before WebKit decided on the one before, as for the first page of a new
    // view, and WebKit then decides on each in turn. The app is not asked about its own loads.
    std::deque<std::string> m_appLoadUris;
    // The authentication requests that the app was asked about and has not answered, by the id it was given. WebKit
    // cancels a request when its load ends or another one starts, and a later answer for it is dropped.
    std::unordered_map<jlong, WebKitAuthenticationRequest*> m_authenticationRequests;
    jlong m_lastAuthenticationRequestId {0};
    // The JavaScript dialogs that Java was told of and has not answered, by the id it was given. The script of each
    // waits for its answer, and a later answer for it is dropped.
    std::unordered_map<jlong, WebKitScriptDialog*> m_scriptDialogs;
    jlong m_lastScriptDialogId {0};
    // The origin of the main document that committed last, which its permission requests are for, or empty if its
    // address is not http or https. WebKit gives no origin with most requests, and the web view's URI already names the
    // next page while it loads, when the document shown can still ask.
    std::string m_documentOrigin;
    // The permission requests and queries that Java was told of and has not answered, by the id it was given. They end
    // with the document that asked, which WebKit does not tell (endDocumentPermissions).
    std::unordered_map<jlong, WebKitPermissionRequest*> m_permissionRequests;
    jlong m_lastPermissionRequestId {0};
    std::unordered_map<jlong, WebKitPermissionStateQuery*> m_permissionStateQueries;
    jlong m_lastPermissionStateQueryId {0};
    // The notifications that Java was told of and that are not closed, by the id it was given. WebKit keeps one of a
    // document that went away without the back forward cache, and tells nothing of it, so the bridge closes those of
    // the old document itself (endDocumentNotifications).
    std::unordered_map<jlong, WebKitNotification*> m_notifications;
    jlong m_lastNotificationId {0};
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
    // The icons of the page shown, or null while it has none. WebKit keeps the icons of a page until the next page that
    // loads icons has its own, so the bridge keeps those of the page shown itself (clearPageIcons, startPageIcons).
    WebKitImageList* m_pageIcons {nullptr};
    // The search of the icon database for the icons of the page that committed last, while it runs.
    GCancellable* m_storedPageIconsSearch {nullptr};
    // The icons of the pages that the view showed last, by their address, the newest first, at most
    // maxRecentPageIcons, so that a page that the view goes back to has its icon at once. WebKit loads none for a page
    // that comes back from the back/forward cache, and an ephemeral session keeps its icons for a short while only.
    std::deque<std::pair<std::string, WebKitImageList*>> m_recentPageIcons;
};

static constexpr size_t maxRecentPageIcons = 16;

// The bridge of the web view, or null once the app destroyed the WebView. The web view itself stays until the main
// loop runs again. A call into Java may call the app, which may destroy the WebView, so a callback that called Java
// finds the bridge again with this before it uses it again.
static WebKitWebViewBridge* webViewBridge(WebKitWebView* webView)
{
    return static_cast<WebKitWebViewBridge*>(g_object_get_qdata(G_OBJECT(webView), webViewBridgeQuark()));
}

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
        , m_onDownloadStarted(
              getMethod<void(jlong, jstring, jstring, jstring, jlong, jboolean, jstring)>("onDownloadStarted"))
        , m_onDownloadFailedBeforeResponse(getMethod<void(jstring, jint, jstring)>("onDownloadFailedBeforeResponse"))
        , m_onRunFileChooser(getMethod<void(jlong, jstringArray, jboolean)>("onRunFileChooser"))
        , m_onRunColorChooser(getMethod<void(jlong, jint, jboolean)>("onRunColorChooser"))
        , m_onColorChooserFinished(getMethod<void(jlong)>("onColorChooserFinished"))
        , m_onAuthenticate(
              getMethod<void(jlong, jstring, jint, jstring, jstring, jboolean, jboolean)>("onAuthenticate"))
        , m_onAuthenticationCancelled(getMethod<void(jlong)>("onAuthenticationCancelled"))
        , m_onIsPlayingAudioChanged(getMethod<void(jboolean)>("onIsPlayingAudioChanged"))
        , m_onFindResult(getMethod<void(jint)>("onFindResult"))
        , m_onPermissionRequest(getMethod<void(jlong, jint, jstring)>("onPermissionRequest"))
        , m_onPermissionRequestCancelled(getMethod<void(jlong)>("onPermissionRequestCancelled"))
        , m_onQueryPermissionState(getMethod<jboolean(jlong, jint, jstring)>("onQueryPermissionState"))
        , m_onShowNotification(getMethod<void(jlong, jstring, jstring, jstring, jstring)>("onShowNotification"))
        , m_onNotificationClosed(getMethod<void(jlong)>("onNotificationClosed"))
        , m_onPageIconsChanged(getMethod<void()>("onPageIconsChanged"))
    {
        registerNativeMethods(JNI::NativeMethod<jlong(jlong, jlong, jlong, jlong, jlong)>("nativeInit", nativeInit),
            JNI::NativeMethod<jlong(jlong, jlong)>("nativeInitRelated", nativeInitRelated),
            JNI::NativeMethod<void(jlong)>("nativeDestroy", nativeDestroy),
            JNI::NativeMethod<void(jlong, jstring)>("nativeLoadUrl", nativeLoadUrl),
            JNI::NativeMethod<void(jlong, jstring)>("nativeDownloadUri", nativeDownloadUri),
            JNI::NativeMethod<void(jlong, jstring, jstring)>("nativeLoadHtml", nativeLoadHtml),
            JNI::NativeMethod<void(jlong, jstring, jstring, jstring)>(
                "nativeLoadAlternateHtml", nativeLoadAlternateHtml),
            JNI::NativeMethod<void(jlong)>("nativeStopLoading", nativeStopLoading),
            JNI::NativeMethod<void(jlong)>("nativeReload", nativeReload),
            JNI::NativeMethod<void(jlong)>("nativeReloadBypassCache", nativeReloadBypassCache),
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
            JNI::NativeMethod<void(jlong, jlong, jboolean, jstring)>(
                "nativeScriptDialogAnswer", nativeScriptDialogAnswer),
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
            JNI::NativeMethod<jboolean(jlong)>("nativeIsMuted", nativeIsMuted),
            JNI::NativeMethod<void(jlong, jstring, jint, jint)>("nativeFindSearch", nativeFindSearch),
            JNI::NativeMethod<void(jlong)>("nativeFindFinish", nativeFindFinish),
            JNI::NativeMethod<void(jlong, jlong, jboolean)>(
                "nativePermissionRequestAnswer", nativePermissionRequestAnswer),
            JNI::NativeMethod<void(jlong, jlong, jint)>(
                "nativePermissionStateQueryAnswer", nativePermissionStateQueryAnswer),
            JNI::NativeMethod<void(jlong, jlong)>("nativeNotificationClick", nativeNotificationClick),
            JNI::NativeMethod<void(jlong, jlong)>("nativeNotificationClose", nativeNotificationClose),
            JNI::NativeMethod<JNIBitmap(jlong, jint)>("nativeGetPageIcon", nativeGetPageIcon));
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
    const JNI::Method<void(jlong, jstring, jstring, jstring, jlong, jboolean, jstring)> m_onDownloadStarted;
    const JNI::Method<void(jstring, jint, jstring)> m_onDownloadFailedBeforeResponse;
    const JNI::Method<void(jlong, jstringArray, jboolean)> m_onRunFileChooser;
    const JNI::Method<void(jlong, jint, jboolean)> m_onRunColorChooser;
    const JNI::Method<void(jlong)> m_onColorChooserFinished;
    const JNI::Method<void(jlong, jstring, jint, jstring, jstring, jboolean, jboolean)> m_onAuthenticate;
    const JNI::Method<void(jlong)> m_onAuthenticationCancelled;
    const JNI::Method<void(jboolean)> m_onIsPlayingAudioChanged;
    const JNI::Method<void(jint)> m_onFindResult;
    const JNI::Method<void(jlong, jint, jstring)> m_onPermissionRequest;
    const JNI::Method<void(jlong)> m_onPermissionRequestCancelled;
    const JNI::Method<jboolean(jlong, jint, jstring)> m_onQueryPermissionState;
    const JNI::Method<void(jlong, jstring, jstring, jstring, jstring)> m_onShowNotification;
    const JNI::Method<void(jlong)> m_onNotificationClosed;
    const JNI::Method<void()> m_onPageIconsChanged;

private:
    static void onClose(WebKitWebView*, WebKitWebViewBridge* bridge);
    static void onLoadChanged(WebKitWebView* webView, WebKitLoadEvent loadEvent, WebKitWebViewBridge* bridge);
    static void onLoadCommitted(WebKitWebView* webView, WebKitWebViewBridge* bridge);
    static const char* startedLoadUri(WebKitWebViewBridge* bridge);
    static void onUriChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onTitleChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void reportTitle(WebKitWebViewBridge* bridge);
    static void onIsLoadingChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void updateAwaitsFirstPage(WebKitWebViewBridge* bridge);
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
    static bool takeAppLoad(WebKitWebViewBridge* bridge, const char* uri);
    static gboolean onLoadFailed(
        WebKitWebView*, WebKitLoadEvent, const gchar* failingUri, GError* error, WebKitWebViewBridge* bridge);
    static gboolean onScriptDialog(WebKitWebView* webView, WebKitScriptDialog* dialog, WebKitWebViewBridge* bridge);
    static void answerScriptDialog(WebKitWebViewBridge* bridge, jlong dialogId, gboolean confirmed, const char* text);
    static void onWebProcessTerminated(
        WebKitWebView*, WebKitWebProcessTerminationReason reason, WebKitWebViewBridge* bridge);
    static WebKitWebView* onCreate(WebKitWebView*, WebKitNavigationAction* action, WebKitWebViewBridge* bridge);
    static void onDownloadStarted(WebKitNetworkSession*, WebKitDownload* download, WebKitWebViewBridge* bridge);
    static gboolean onDecideDownloadDestination(
        WebKitDownload* download, const char* suggestedFilename, gpointer hasGesture);
    static void onAppDownloadFailed(WebKitDownload* download, GError* error, gpointer);
    static gboolean onRunFileChooser(WebKitWebView*, WebKitFileChooserRequest* request, WebKitWebViewBridge* bridge);
    static gboolean onRunColorChooser(WebKitWebView*, WebKitColorChooserRequest* request, WebKitWebViewBridge* bridge);
    static void onColorChooserFinished(WebKitColorChooserRequest* request, WebKitWebViewBridge* bridge);
    static gboolean onAuthenticate(WebKitWebView*, WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge);
    static void onAuthenticationCancelled(WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge);
    static void answerAuthentication(WebKitWebViewBridge* bridge, jlong requestId, WebKitCredential* credential);
    static void onIsPlayingAudioChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static void onFoundText(WebKitFindController*, guint matchCount, WebKitWebViewBridge* bridge);
    static void onFailedToFindText(WebKitFindController*, WebKitWebViewBridge* bridge);
    static void reportFindResult(WebKitWebViewBridge* bridge, jint matchCount);
    static gboolean onPermissionRequest(
        WebKitWebView* webView, WebKitPermissionRequest* request, WebKitWebViewBridge* bridge);
    static gboolean onQueryPermissionState(
        WebKitWebView* webView, WebKitPermissionStateQuery* query, WebKitWebViewBridge* bridge);
    static void answerPermissionRequest(WebKitWebViewBridge* bridge, jlong requestId, bool allow);
    static void endDocumentPermissions(WebKitWebView* webView, WebKitWebViewBridge* bridge);
    static gboolean onShowNotification(
        WebKitWebView* webView, WebKitNotification* notification, WebKitWebViewBridge* bridge);
    static void onNotificationClosed(WebKitNotification* notification, WebKitWebViewBridge* bridge);
    static void endDocumentNotifications(WebKitWebViewBridge* bridge);
    static void dropNotifications(WebKitWebViewBridge* bridge);
    static void onEvalJavascriptReady(GObject* object, GAsyncResult* result, gpointer userData);
    static void onCaptureSnapshotReady(GObject* object, GAsyncResult* result, gpointer userData);
    static void rememberPageIcons(WebKitWebViewBridge* bridge, const char* uri, WebKitImageList* icons);
    static bool clearPageIcons(WebKitWebViewBridge* bridge);
    static bool startPageIcons(WebKitWebViewBridge* bridge);
    static void onStoredPageIconsReady(GObject* object, GAsyncResult* result, gpointer userData);
    static void onPageIconsChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge);
    static bool setPageIcons(WebKitWebViewBridge* bridge, WebKitImageList* icons);

    static WebKitWebViewBridge* createBridge(JNIEnv* env, jobject jniWebView, WebKitWebView* webView);
    static jlong nativeInit(JNIEnv* env, jobject jniWebView, jlong displayPtr, jlong contextPtr, jlong toplevelPtr,
        jlong networkSessionPtr, jlong settingsPtr);
    static jlong nativeInitRelated(JNIEnv* env, jobject jniWebView, jlong relatedPtr, jlong settingsPtr);
    static void nativeDestroy(JNIEnv*, jobject, jlong nativePtr);
    static void nativeLoadUrl(JNIEnv*, jobject, jlong nativePtr, jstring url);
    static void nativeDownloadUri(JNIEnv*, jobject, jlong nativePtr, jstring uri);
    static void nativeLoadHtml(JNIEnv*, jobject, jlong nativePtr, jstring content, jstring baseUri);
    static void nativeLoadAlternateHtml(
        JNIEnv*, jobject, jlong nativePtr, jstring content, jstring contentUri, jstring baseUri);
    static void nativeStopLoading(JNIEnv*, jobject, jlong nativePtr);
    static void nativeReload(JNIEnv*, jobject, jlong nativePtr);
    static void nativeReloadBypassCache(JNIEnv*, jobject, jlong nativePtr);
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
    static void nativeScriptDialogAnswer(
        JNIEnv*, jobject, jlong nativePtr, jlong dialogId, jboolean confirm, jstring text);
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
    static void nativeFindSearch(JNIEnv*, jobject, jlong nativePtr, jstring text, jint options, jint maxMatchCount);
    static void nativeFindFinish(JNIEnv*, jobject, jlong nativePtr);
    static void nativePermissionRequestAnswer(JNIEnv*, jobject, jlong nativePtr, jlong requestId, jboolean allow);
    static void nativePermissionStateQueryAnswer(JNIEnv*, jobject, jlong nativePtr, jlong queryId, jint state);
    static void nativeNotificationClick(JNIEnv*, jobject, jlong nativePtr, jlong notificationId);
    static void nativeNotificationClose(JNIEnv*, jobject, jlong nativePtr, jlong notificationId);
    static JNIBitmap nativeGetPageIcon(JNIEnv* env, jobject, jlong nativePtr, jint size);
};

static const JNIWebKitWebViewCache& getJNIWebKitWebViewCache()
{
    static const JNIWebKitWebViewCache s_singleton;
    return s_singleton;
}

// The PERMISSION_* flags of WebKitWebView.java, for the kinds of permission that WPEView passes to the app.
static constexpr jint permissionLocation = 1;
static constexpr jint permissionNotifications = 1 << 1;

// What a permission request asks for, or 0 for the kinds that WPEView does not pass to the app yet.
static jint permissionResources(WebKitPermissionRequest* request)
{
    if (WEBKIT_IS_GEOLOCATION_PERMISSION_REQUEST(request))
        return permissionLocation;
    if (WEBKIT_IS_NOTIFICATION_PERMISSION_REQUEST(request))
        return permissionNotifications;
    return 0;
}

// What a Permissions API query asks about, by the name the page gives, or 0 for the kinds that WebKit answers itself.
// WebKit also asks about "notifications" for a query of "push".
static jint permissionResourceForQuery(const char* name)
{
    if (!g_strcmp0(name, "geolocation"))
        return permissionLocation;
    if (!g_strcmp0(name, "notifications"))
        return permissionNotifications;
    return 0;
}

// The most characters of the title, the body or the tag of a notification that Java is given: Android shows no more of
// the first two.
static constexpr int maxNotificationTextLength = 1024;

// The text cut to maxNotificationTextLength characters. WebKit gives valid UTF-8.
static std::string notificationText(const char* text)
{
    if (!text)
        return {};
    const char* end = text;
    for (int i = 0; i < maxNotificationTextLength && *end; ++i)
        end = g_utf8_next_char(end);
    return {text, end};
}

// The origin of a document at this address, for its permissions, or empty if the address is not http or https, as
// about:blank or a data: address, whose origin the app could not show the user.
static std::string permissionOrigin(const char* uri)
{
    if (!uri || !*uri)
        return {};
    g_autoptr(WebKitSecurityOrigin) origin = webkit_security_origin_new_for_uri(uri);
    if (!origin)
        return {};
    const char* protocol = webkit_security_origin_get_protocol(origin);
    if (g_strcmp0(protocol, "http") && g_strcmp0(protocol, "https"))
        return {};
    g_autofree char* string = webkit_security_origin_to_string(origin);
    return string ? string : "";
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
        auto jUri = JNI::String(startedLoadUri(bridge));
        // Each navigation is decided on before its load starts. Should a load start without, the loads of the app still
        // to be decided on are no longer taken as the app's, as after a navigation of the page.
        if (!bridge->m_navigationPending)
            bridge->m_appLoadUris.clear();
        bridge->m_navigationPending = false;
        bridge->m_redirectCount = 0;
        bridge->m_failedLoadUri.clear();
        bridge->m_loadStarting = true;
        if (!getJNIWebKitWebViewCache().m_onLoadStarted.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
            Logging::logError("cannot deliver WebKitWebView onLoadStarted callback");
    } else if (loadEvent == WEBKIT_LOAD_REDIRECTED) {
        bridge->m_redirectCount++;
    } else if (loadEvent == WEBKIT_LOAD_COMMITTED) {
        onLoadCommitted(webView, bridge);
    } else if (loadEvent == WEBKIT_LOAD_FINISHED) {
        // Also the end of a load that failed or stopped before it committed. After the end of the web process, the view
        // keeps the title and the address of its page until a new one commits.
        bridge->m_loadStarting = false;
        if (bridge->m_titlePending && !bridge->m_webProcessTerminated) {
            reportTitle(bridge);
            bridge = webViewBridge(webView);
            if (!bridge)
                return;
        }
        const char* uri = webkit_web_view_get_uri(webView);
        if (bridge->m_webProcessTerminated && (!uri || !*uri))
            uri = bridge->m_historyUri.c_str();
        auto jUri = JNI::String(bridge->m_failedLoadUri.empty() ? uri : bridge->m_failedLoadUri.c_str());
        bridge->m_failedLoadUri.clear();
        if (!getJNIWebKitWebViewCache().m_onLoadFinished.invoke(bridge->m_javaRef.get(), static_cast<jstring>(jUri)))
            Logging::logError("cannot deliver WebKitWebView onLoadFinished callback");
    }
}

void JNIWebKitWebViewCache::onLoadCommitted(WebKitWebView* webView, WebKitWebViewBridge* bridge)
{
    // The origin of the new document, before Java hears of anything. WebKit gives the view the address of a load
    // that the app asked for as soon as it asks, so while one waits to be decided on, the address may not be the
    // one that committed: the document then asks nothing of the app until the next one commits.
    bridge->m_documentOrigin
        = bridge->m_appLoadUris.empty() ? permissionOrigin(webkit_web_view_get_uri(webView)) : std::string();
    bridge->m_loadStarting = false;
    bridge->m_pageCommitted = true;
    updateAwaitsFirstPage(bridge);
    // The new page ends the frames of the old one and their loads, so their navigations no longer count. The
    // downloads of the old page have taken their counts by then: WebKit tells of a download right after it is
    // decided on, not once the server answers.
    bridge->m_navigationCounts.clear();
    bridge->m_navigationCountsFull = false;
    // The first page after the end of the web process has its title reported, also when WebKit's stays empty.
    bool titlePending = bridge->m_titlePending || bridge->m_webProcessTerminated;
    bridge->m_webProcessTerminated = false;
    // The icons of the old page go before the app hears of the new one, and those that the new page has at once come
    // after, so that the app knows which page they belong to.
    if (clearPageIcons(bridge)) {
        bridge = webViewBridge(webView);
        if (!bridge)
            return;
    }
    if (titlePending) {
        reportTitle(bridge);
        // The app may have destroyed the WebView as it heard of the title.
        bridge = webViewBridge(webView);
        if (!bridge)
            return;
    }
    reportHistory(bridge, true);
    bridge = webViewBridge(webView);
    if (bridge && startPageIcons(bridge))
        bridge = webViewBridge(webView);
    // Last, since the app may destroy the WebView as it hears that the requests of the old document ended.
    if (bridge)
        endDocumentPermissions(webView, bridge);
    bridge = webViewBridge(webView);
    if (bridge)
        endDocumentNotifications(bridge);
}

// The address of the load that starts. For a load the page starts itself, a link or a script, the web view's URI is
// still the old one, and the navigation that was decided on last has the address. For content shown in place of a page,
// the URI is already the address of that page, which WebKit gave the view when the app asked, while the navigation
// decided on last may be one that the content replaced, so it no longer counts.
const char* JNIWebKitWebViewCache::startedLoadUri(WebKitWebViewBridge* bridge)
{
    const char* viewUri = webkit_web_view_get_uri(bridge->m_webView);
    if (!bridge->m_alternateLoadUri.empty() && !g_strcmp0(viewUri, bridge->m_alternateLoadUri.c_str()))
        bridge->m_navigationPending = false;
    bridge->m_alternateLoadUri.clear();
    return bridge->m_navigationPending ? bridge->m_navigationUri.c_str() : viewUri;
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

    bridge = webViewBridge(WEBKIT_WEB_VIEW(obj));
    if (bridge && !webkit_web_view_is_loading(WEBKIT_WEB_VIEW(obj)))
        reportHistory(bridge);
}

void JNIWebKitWebViewCache::onIsLoadingChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    updateAwaitsFirstPage(bridge);
    if (!webkit_web_view_is_loading(WEBKIT_WEB_VIEW(obj)))
        reportHistory(bridge);
}

// The view waits for its first page while the page loads and has not committed. A view that loads nothing, as one
// that a script writes, shows what it draws, and so does one whose first load ends without a page.
void JNIWebKitWebViewCache::updateAwaitsFirstPage(WebKitWebViewBridge* bridge)
{
    auto* view = webkit_web_view_get_wpe_view(bridge->m_webView);
    if (WPE_IS_VIEW_ANDROID(view)) {
        wpe_view_android_set_awaits_first_page(
            view, !bridge->m_pageCommitted && webkit_web_view_is_loading(bridge->m_webView));
    }
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
    // A navigation within the page keeps its icons, for its new address too.
    if (!committed && bridge->m_pageIcons)
        rememberPageIcons(bridge, uri, bridge->m_pageIcons);
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

// Sets the icons of the page shown, or none, and tells Java if they changed. Returns whether it told Java, which may
// have destroyed the WebView meanwhile. A list without images is no icon.
bool JNIWebKitWebViewCache::setPageIcons(WebKitWebViewBridge* bridge, WebKitImageList* icons)
{
    if (icons && !webkit_image_list_get_length(icons))
        icons = nullptr;
    if (icons == bridge->m_pageIcons)
        return false;
    if (icons)
        webkit_image_list_ref(icons);
    if (bridge->m_pageIcons)
        webkit_image_list_unref(bridge->m_pageIcons);
    bridge->m_pageIcons = icons;
    // Under the address of the page shown, as the view's address is already that of a load that the app asked for.
    if (icons)
        rememberPageIcons(bridge, bridge->m_historyUri.c_str(), icons);
    if (!getJNIWebKitWebViewCache().m_onPageIconsChanged.invoke(bridge->m_javaRef.get()))
        Logging::logError("cannot deliver WebKitWebView onPageIconsChanged callback");
    return true;
}

// Keeps the icons of the page at the address, which the view showed, the newest first.
void JNIWebKitWebViewCache::rememberPageIcons(WebKitWebViewBridge* bridge, const char* uri, WebKitImageList* icons)
{
    if (!uri || !*uri)
        return;
    auto& recent = bridge->m_recentPageIcons;
    auto same = std::find_if(recent.begin(), recent.end(), [uri](const auto& entry) { return entry.first == uri; });
    if (same != recent.end()) {
        webkit_image_list_unref(same->second);
        recent.erase(same);
    }
    recent.emplace_front(uri, webkit_image_list_ref(icons));
    if (recent.size() > maxRecentPageIcons) {
        webkit_image_list_unref(recent.back().second);
        recent.pop_back();
    }
}

// A page that commits has none of the icons of the page before. Returns whether it told Java that the icons changed.
bool JNIWebKitWebViewCache::clearPageIcons(WebKitWebViewBridge* bridge)
{
    if (bridge->m_storedPageIconsSearch) {
        g_cancellable_cancel(bridge->m_storedPageIconsSearch);
        g_clear_object(&bridge->m_storedPageIconsSearch);
    }
    return setPageIcons(bridge, nullptr);
}

// WebKit looks for the icons of a page that committed only once it has loaded, so meanwhile the page gets those that
// the view showed for its address last, as when the user goes back to it, or else those that the icon database keeps
// for it, from an earlier visit, if any. Called once the page is reported. Returns whether it told Java that the icons
// changed.
bool JNIWebKitWebViewCache::startPageIcons(WebKitWebViewBridge* bridge)
{
    const char* uri = webkit_web_view_get_uri(bridge->m_webView);
    // While a load that the app asked for waits to be decided on, the address is the one of that load, not of the page.
    if (!uri || !*uri || bridge->m_historyUri != uri || !bridge->m_appLoadUris.empty())
        return false;
    const auto& recent = bridge->m_recentPageIcons;
    auto shown = std::find_if(recent.begin(), recent.end(), [uri](const auto& entry) { return entry.first == uri; });
    if (shown != recent.end())
        return setPageIcons(bridge, shown->second);
    auto* manager
        = webkit_network_session_get_website_data_manager(webkit_web_view_get_network_session(bridge->m_webView));
    auto* database = webkit_website_data_manager_get_favicon_database(manager);
    // WebKit keeps no icons for about: addresses.
    if (database && g_ascii_strncasecmp(uri, "about:", 6)) {
        bridge->m_storedPageIconsSearch = g_cancellable_new();
        webkit_favicon_database_get_page_icons(
            database, uri, bridge->m_storedPageIconsSearch, onStoredPageIconsReady, g_object_ref(bridge->m_webView));
    }
    return false;
}

void JNIWebKitWebViewCache::onStoredPageIconsReady(GObject* object, GAsyncResult* result, gpointer userData)
{
    g_autoptr(WebKitWebView) webView = WEBKIT_WEB_VIEW(userData);
    g_autoptr(GError) error = nullptr;
    g_autoptr(WebKitImageList) icons
        = webkit_favicon_database_get_page_icons_finish(WEBKIT_FAVICON_DATABASE(object), result, &error);
    // A search that was cancelled, or that is not the last one, was for a page that is gone, or whose own icons came
    // first.
    auto* bridge = webViewBridge(webView);
    if (!bridge || g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED)
        || g_task_get_cancellable(G_TASK(result)) != bridge->m_storedPageIconsSearch)
        return;
    g_clear_object(&bridge->m_storedPageIconsSearch);
    setPageIcons(bridge, icons);
}

// WebKit sets the icons of a page once it loaded them, or found them in the icon database, after the page has loaded,
// also when they are the same as before, as for a reload. A page whose icons are unknown or cannot be loaded gets a
// list without images. WebKit sets none for a page that declares no icon and is not http or https, as about:blank, nor
// for navigations within a page, which keeps its icons. It files them under the address of the load that is starting,
// if any, so those that come then are not the icons of the page shown, which keeps its own.
void JNIWebKitWebViewCache::onPageIconsChanged(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    // FIXME: WebKit looks the icons up for the address that is active when it starts, and sets them whenever the lookup
    // ends, without checking that the page is still the same one, so a page that leaves at once can give its icons to
    // the next one, which keeps them if it loads none of its own, as about:blank. WebKit should check the page.
    if (bridge->m_loadStarting)
        return;
    // The page's own icons take the place of those stored for its address.
    if (bridge->m_storedPageIconsSearch) {
        g_cancellable_cancel(bridge->m_storedPageIconsSearch);
        g_clear_object(&bridge->m_storedPageIconsSearch);
    }
    setPageIcons(bridge, webkit_web_view_get_page_icons(WEBKIT_WEB_VIEW(obj)));
}

// WebKit answers each search once, in the order they were asked for, as a page without site isolation sends them all to
// its one web process, also when that ends meanwhile, as its replies then come with nothing found. An answer says how
// many matches the page has, but not which one is the current match. A search that found more matches than it was
// allowed to count reports G_MAXUINT.
void JNIWebKitWebViewCache::onFoundText(WebKitFindController*, guint matchCount, WebKitWebViewBridge* bridge)
{
    reportFindResult(
        bridge, matchCount > static_cast<guint>(std::numeric_limits<jint>::max()) ? -1 : static_cast<jint>(matchCount));
}

// No match, or the page could not be searched. WebKit then takes the highlights of the matches away.
void JNIWebKitWebViewCache::onFailedToFindText(WebKitFindController*, WebKitWebViewBridge* bridge)
{
    reportFindResult(bridge, 0);
}

void JNIWebKitWebViewCache::reportFindResult(WebKitWebViewBridge* bridge, jint matchCount)
{
    if (!getJNIWebKitWebViewCache().m_onFindResult.invoke(bridge->m_javaRef.get(), matchCount))
        Logging::logError("cannot deliver WebKitWebView onFindResult callback");
}

void JNIWebKitWebViewCache::onEstimatedLoadProgress(GObject* obj, GParamSpec*, WebKitWebViewBridge* bridge)
{
    double progress = webkit_web_view_get_estimated_load_progress(WEBKIT_WEB_VIEW(obj));
    if (!getJNIWebKitWebViewCache().m_onEstimatedLoadProgress.invoke(bridge->m_javaRef.get(), progress))
        Logging::logError("cannot deliver WebKitWebView onEstimatedLoadProgress callback");
}

// WebKit empties the title when a new page commits, which can be before it tells the address of that page. A page
// without a title has its address as its title, so a title emptied while a load starts waits for the commit, or for the
// end of a load that does not commit. Until then, any other title is the one of the page that is still shown, which can
// set it or send it after its load finished, and it is reported at once. WebKit also empties the title when the web
// process ends, and the view keeps the title of its page until the next commit.
void JNIWebKitWebViewCache::onTitleChanged(GObject*, GParamSpec*, WebKitWebViewBridge* bridge)
{
    const char* title = webkit_web_view_get_title(bridge->m_webView);
    if (bridge->m_webProcessTerminated || (bridge->m_loadStarting && (!title || !*title)))
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
        bool isAppLoad = false;
        // The page of a window that a user gesture opened.
        bool isOpenedByUser = false;
        if (isMainFrame && !isNewWindow) {
            // A server redirect goes on with the navigation it follows: it is no load of the app, nor a navigation of
            // the page that ends the loads of the app still to come.
            isAppLoad = !webkit_navigation_action_is_redirect(action) && takeAppLoad(bridge, uri);
            isOpenedByUser = bridge->m_windowLoadGesture && uri && bridge->m_windowLoadUri == uri;
            bridge->m_windowLoadUri.clear();
            bridge->m_windowLoadGesture = false;
        }
        if (shouldOverrideUrlLoading(bridge, action, isMainFrame, isAppLoad)) {
            webkit_policy_decision_ignore(decision);
            return TRUE;
        }
        // The app may have destroyed the WebView while it was asked.
        bridge = webViewBridge(webView);
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

// Whether a main frame navigation is a load that the app asked for. WebKit decides on the loads of the app in the order
// they were asked for, before any later navigation in the main frame, unless the page started one meanwhile. A load
// that a later one replaced may never be decided on, and goes with the later one. A navigation of the page empties the
// list, so that no load of the app outlives it and is taken for a later navigation of the page to the same address.
// The loads of the app still to come are then taken as the page's: the app is asked about them, and they get no
// gesture, which is the cautious side.
bool JNIWebKitWebViewCache::takeAppLoad(WebKitWebViewBridge* bridge, const char* uri)
{
    auto& loads = bridge->m_appLoadUris;
    auto it = uri ? std::find(loads.begin(), loads.end(), uri) : loads.end();
    if (it == loads.end()) {
        loads.clear();
        return false;
    }
    loads.erase(loads.begin(), it + 1);
    return true;
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

// A script of the page opens a dialog, and waits for its answer. Java is told of it with an id, and answers it once
// with nativeScriptDialogAnswer. The bridge keeps the dialog meanwhile, so that an answer that comes later, or for a
// view that was destroyed, is dropped.
gboolean JNIWebKitWebViewCache::onScriptDialog(
    WebKitWebView* webView, WebKitScriptDialog* dialog, WebKitWebViewBridge* bridge)
{
    // WebDriver answers the dialogs of the pages it controls, as its session's user prompt handler says. WebKit keeps
    // a dialog for it only when no handler takes the dialog, so neither the app nor the built-in dialog gets it.
    if (webkit_web_view_is_controlled_by_automation(webView))
        return FALSE;

    jlong dialogId = ++bridge->m_lastScriptDialogId;
    bridge->m_scriptDialogs.emplace(dialogId, webkit_script_dialog_ref(dialog));
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    // The active URI is null before the first commit and the message may be null; pass them through
    // as null (JNI::String(nullptr) yields a null jstring) — the Java callbacks treat null as "no value".
    auto jUrl = JNI::String(webkit_web_view_get_uri(webView));
    auto jMessage = JNI::String(webkit_script_dialog_get_message(dialog));
    JNI::String jDefaultText = type == WEBKIT_SCRIPT_DIALOG_PROMPT
        ? JNI::String(webkit_script_dialog_prompt_get_default_text(dialog))
        : JNI::String("");
    if (!getJNIWebKitWebViewCache().m_onScriptDialog.invoke(bridge->m_javaRef.get(), dialogId, static_cast<jint>(type),
            static_cast<jstring>(jUrl), static_cast<jstring>(jMessage), static_cast<jstring>(jDefaultText))) {
        Logging::logError("cannot deliver WebKitWebView onScriptDialog callback");
        // Java answers the dialog when anything fails after it took it. If the call failed before, nothing else would.
        // The app may have destroyed the WebView before the call failed, which let go of the dialog.
        bridge = webViewBridge(webView);
        if (bridge)
            answerScriptDialog(bridge, dialogId, FALSE, nullptr);
    }

    return TRUE;
}

// Answers a dialog that Java was told of, unless it was answered already. The script goes on once WebKit is done with
// the dialog too.
void JNIWebKitWebViewCache::answerScriptDialog(
    WebKitWebViewBridge* bridge, jlong dialogId, gboolean confirmed, const char* text)
{
    auto it = bridge->m_scriptDialogs.find(dialogId);
    if (it == bridge->m_scriptDialogs.end())
        return;
    WebKitScriptDialog* dialog = it->second;
    bridge->m_scriptDialogs.erase(it);
    WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
    // A prompt without text gives null, as when it is cancelled. An alert has no answer.
    if (type == WEBKIT_SCRIPT_DIALOG_PROMPT && text != nullptr)
        webkit_script_dialog_prompt_set_text(dialog, text);
    if (type == WEBKIT_SCRIPT_DIALOG_CONFIRM || type == WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM)
        webkit_script_dialog_confirm_set_confirmed(dialog, confirmed);
    webkit_script_dialog_unref(dialog);
}

// The web process of the page ended: it crashed, Android ended it, as it does to free memory, WebKit ended it for using
// too much memory, or the app asked for it. A load in progress ends with it, and is not reported as finished.
void JNIWebKitWebViewCache::onWebProcessTerminated(
    WebKitWebView* webView, WebKitWebProcessTerminationReason reason, WebKitWebViewBridge* bridge)
{
    bridge->m_webProcessTerminated = true;
    bridge->m_loadStarting = false;
    bridge->m_navigationPending = false;
    bridge->m_failedLoadUri.clear();
    bridge->m_appLoadUris.clear();
    bridge->m_alternateLoadUri.clear();

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

    // The app may have destroyed the WebView as it heard that a request was cancelled.
    bridge = webViewBridge(webView);
    if (!bridge)
        return;
    // The document that asked for permissions went with the process, and so did the page of its notifications.
    dropNotifications(bridge);
    endDocumentPermissions(webView, bridge);
    bridge = webViewBridge(webView);
    if (!bridge)
        return;

    if (!getJNIWebKitWebViewCache().m_onWebProcessTerminated.invoke(bridge->m_javaRef.get(), static_cast<jint>(reason)))
        Logging::logError("cannot deliver WebKitWebView onWebProcessTerminated callback");
}

// WebKit asks for the view of a new window while it makes the page of that window, which needs the view before this
// returns, so the app makes it right away. It must be related to this view: WebKit then puts its page in the same web
// process, with this page as its opener unless the page asked for none, as rel=noopener does. The bridge of the new
// view keeps the view, as WebKit takes no reference of its own. The app may destroy this view meanwhile.
WebKitWebView* JNIWebKitWebViewCache::onCreate(
    WebKitWebView*, WebKitNavigationAction* action, WebKitWebViewBridge* bridge)
{
    bool isUserGesture = webkit_navigation_action_is_user_gesture(action);
    jlong webViewPtr = getJNIWebKitWebViewCache().m_onCreateWindow.invoke(
        bridge->m_javaRef.get(), static_cast<jboolean>(isUserGesture));
    auto* newWebView = JNI::from_jlong<WebKitWebView>(webViewPtr);
    // WebKit decides on the navigation of the window's page again, in the new view, but without the gesture of the link
    // or the script that opened the window. The new view keeps it for the download that the page may become.
    auto* newBridge = newWebView ? webViewBridge(newWebView) : nullptr;
    if (newBridge) {
        const char* uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(action));
        newBridge->m_windowLoadUri = uri ? uri : "";
        newBridge->m_windowLoadGesture = isUserGesture;
    }
    return newWebView;
}

// The address that the app asked to download (nativeDownloadUri), as it gave it, which marks the downloads it asked
// for: those are followed from the start.
static constexpr const char* appDownloadKey = "wpeview-app-download";

// The app is told of a download that it asked for, which the view then no longer cancels when it ends.
static void forgetAppDownload(WebKitWebViewBridge* bridge, WebKitDownload* download)
{
    auto& downloads = bridge->m_appDownloads;
    auto found = std::find(downloads.begin(), downloads.end(), download);
    if (found == downloads.end())
        return;
    downloads.erase(found);
    g_object_unref(download);
}

// Every web view of the network session hears of every download, and follows those of its own pages. Those that the
// app asked for are followed already, and take no gesture from the navigations of the page.
void JNIWebKitWebViewCache::onDownloadStarted(
    WebKitNetworkSession*, WebKitDownload* download, WebKitWebViewBridge* bridge)
{
    if (webkit_download_get_web_view(download) != bridge->m_webView
        || g_object_get_data(G_OBJECT(download), appDownloadKey))
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
    // From now on, the download reports its failure to the app itself.
    auto* requestedUri = static_cast<const char*>(g_object_get_data(G_OBJECT(download), appDownloadKey));
    if (requestedUri)
        g_signal_handlers_disconnect_by_func(download, reinterpret_cast<gpointer>(onAppDownloadFailed), nullptr);

    auto* webView = webkit_download_get_web_view(download);
    auto* bridge = webView ? webViewBridge(webView) : nullptr;
    if (!bridge) {
        webkit_download_cancel(download);
        return TRUE;
    }
    if (requestedUri)
        forgetAppDownload(bridge, download);

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
    // Null for a download of the page.
    auto jRequestedUri = JNI::String(requestedUri);
    if (!getJNIWebKitWebViewCache().m_onDownloadStarted.invoke(bridge->m_javaRef.get(), downloadPtr,
            static_cast<jstring>(jUri), static_cast<jstring>(jSuggestedFilename), static_cast<jstring>(jMimeType),
            length ? static_cast<jlong>(length) : -1, static_cast<jboolean>(GPOINTER_TO_INT(hasGesture)),
            static_cast<jstring>(jRequestedUri))
        || !isWebKitDownloadBridgeAttached(downloadPtr)) {
        Logging::logError("cannot deliver WebKitWebView onDownloadStarted callback");
        webkit_download_cancel(download);
    }
    return TRUE;
}

// A download that the app asked for failed before its response, as when the server cannot be reached, so before the
// app was told of it. The app is told of it then, as of a download that failed, with the address it asked for, unless
// its web view went meanwhile, which cancelled it.
void JNIWebKitWebViewCache::onAppDownloadFailed(WebKitDownload* download, GError* error, gpointer)
{
    auto* webView = webkit_download_get_web_view(download);
    auto* bridge = webView ? webViewBridge(webView) : nullptr;
    if (!bridge)
        return;
    auto jUri = JNI::String(static_cast<const char*>(g_object_get_data(G_OBJECT(download), appDownloadKey)));
    forgetAppDownload(bridge, download);

    jint code = error && error->domain == WEBKIT_DOWNLOAD_ERROR ? error->code : 0;
    auto jDescription = JNI::String(error && error->message ? error->message : "");
    if (!getJNIWebKitWebViewCache().m_onDownloadFailedBeforeResponse.invoke(
            bridge->m_javaRef.get(), static_cast<jstring>(jUri), code, static_cast<jstring>(jDescription)))
        Logging::logError("cannot deliver WebKitWebView onDownloadFailedBeforeResponse callback");
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
    WebKitWebView* webView, WebKitAuthenticationRequest* request, WebKitWebViewBridge* bridge)
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
        // The app may have destroyed the WebView before the call failed, which let go of the request.
        bridge = webViewBridge(webView);
        if (bridge)
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

// A page asks to use something that needs the user's consent. Java is told of it with an id and the origin of the
// document that asks, and answers it once with nativePermissionRequestAnswer. The bridge keeps the request meanwhile.
// WebDriver controls the pages of automation, and the kinds that WPEView does not pass on yet keep WebKit's answer,
// which denies them.
gboolean JNIWebKitWebViewCache::onPermissionRequest(
    WebKitWebView* webView, WebKitPermissionRequest* request, WebKitWebViewBridge* bridge)
{
    if (webkit_web_view_is_controlled_by_automation(webView))
        return FALSE;
    jint resources = permissionResources(request);
    if (!resources)
        return FALSE;
    if (bridge->m_documentOrigin.empty()) {
        webkit_permission_request_deny(request);
        return TRUE;
    }

    jlong requestId = ++bridge->m_lastPermissionRequestId;
    bridge->m_permissionRequests.emplace(requestId, WEBKIT_PERMISSION_REQUEST(g_object_ref(request)));
    auto jOrigin = JNI::String(bridge->m_documentOrigin.c_str());
    if (!getJNIWebKitWebViewCache().m_onPermissionRequest.invoke(
            bridge->m_javaRef.get(), requestId, resources, static_cast<jstring>(jOrigin))) {
        Logging::logError("cannot deliver WebKitWebView onPermissionRequest callback");
        bridge = webViewBridge(webView);
        if (bridge)
            answerPermissionRequest(bridge, requestId, false);
    }
    return TRUE;
}

// Answers a request that Java was told of, unless it was answered already or ended with its document.
void JNIWebKitWebViewCache::answerPermissionRequest(WebKitWebViewBridge* bridge, jlong requestId, bool allow)
{
    auto it = bridge->m_permissionRequests.find(requestId);
    if (it == bridge->m_permissionRequests.end())
        return;
    WebKitPermissionRequest* request = it->second;
    bridge->m_permissionRequests.erase(it);
    if (allow)
        webkit_permission_request_allow(request);
    else
        webkit_permission_request_deny(request);
    g_object_unref(request);
}

// A page asks with navigator.permissions.query() what a request would get. WebKit gives the origin of the top
// document. Java takes the query, to answer it later with nativePermissionStateQueryAnswer, or leaves it to WebKit,
// which answers prompt.
gboolean JNIWebKitWebViewCache::onQueryPermissionState(
    WebKitWebView* webView, WebKitPermissionStateQuery* query, WebKitWebViewBridge* bridge)
{
    if (webkit_web_view_is_controlled_by_automation(webView))
        return FALSE;
    jint resource = permissionResourceForQuery(webkit_permission_state_query_get_name(query));
    if (!resource)
        return FALSE;
    WebKitSecurityOrigin* securityOrigin = webkit_permission_state_query_get_security_origin(query);
    g_autofree char* originUri = securityOrigin ? webkit_security_origin_to_string(securityOrigin) : nullptr;
    std::string origin = permissionOrigin(originUri);
    if (origin.empty())
        return FALSE;

    jlong queryId = ++bridge->m_lastPermissionStateQueryId;
    bridge->m_permissionStateQueries.emplace(queryId, webkit_permission_state_query_ref(query));
    auto jOrigin = JNI::String(origin.c_str());
    bool taken = getJNIWebKitWebViewCache().m_onQueryPermissionState.invoke(
        bridge->m_javaRef.get(), queryId, resource, static_cast<jstring>(jOrigin));
    // A WebView that the app destroyed meanwhile gave the query WebKit's answer, and so did an answer given already.
    bridge = webViewBridge(webView);
    if (!bridge)
        return TRUE;
    auto it = bridge->m_permissionStateQueries.find(queryId);
    if (it == bridge->m_permissionStateQueries.end() || taken)
        return TRUE;
    webkit_permission_state_query_unref(it->second);
    bridge->m_permissionStateQueries.erase(it);
    return FALSE;
}

// The document that asked went away, with a new main document or with its web process. WebKit drops its requests
// without a word, and ignores answers to them, so they are denied here, and Java hears that they ended, to take down
// what it shows for them. The queries get WebKit's own answer. The app may destroy the WebView as it hears of a
// request, so the bridge is found again before each.
void JNIWebKitWebViewCache::endDocumentPermissions(WebKitWebView* webView, WebKitWebViewBridge* bridge)
{
    for (auto& [id, query] : std::exchange(bridge->m_permissionStateQueries, {}))
        webkit_permission_state_query_unref(query);
    std::vector<jlong> requestIds;
    for (auto& [id, request] : std::exchange(bridge->m_permissionRequests, {})) {
        webkit_permission_request_deny(request);
        g_object_unref(request);
        requestIds.push_back(id);
    }
    // In the order the page asked.
    std::sort(requestIds.begin(), requestIds.end());
    for (jlong requestId : requestIds) {
        bridge = webViewBridge(webView);
        if (!bridge)
            return;
        if (!getJNIWebKitWebViewCache().m_onPermissionRequestCancelled.invoke(bridge->m_javaRef.get(), requestId))
            Logging::logError("cannot deliver WebKitWebView onPermissionRequestCancelled callback");
    }
}

// A page that the app allowed shows a notification. Every one is taken here: WebKit sends one that is not to a
// notification service of desktop systems, which Android does not have, and the page would wait for it in vain. Java
// is told of it with an id, and clicks or closes it with nativeNotificationClick and nativeNotificationClose. WebDriver
// has no use for them, and a page whose origin the app could not show gets none: both are closed at once, so the page
// hears that they closed and never that they showed.
gboolean JNIWebKitWebViewCache::onShowNotification(
    WebKitWebView* webView, WebKitNotification* notification, WebKitWebViewBridge* bridge)
{
    if (webkit_web_view_is_controlled_by_automation(webView) || bridge->m_documentOrigin.empty()) {
        webkit_notification_close(notification);
        return TRUE;
    }

    jlong notificationId = ++bridge->m_lastNotificationId;
    bridge->m_notifications.emplace(notificationId, WEBKIT_NOTIFICATION(g_object_ref(notification)));
    g_signal_connect(notification, "closed", G_CALLBACK(onNotificationClosed), bridge);
    auto jOrigin = JNI::String(bridge->m_documentOrigin.c_str());
    auto jTitle = JNI::String(notificationText(webkit_notification_get_title(notification)).c_str());
    auto jBody = JNI::String(notificationText(webkit_notification_get_body(notification)).c_str());
    // Null when the page gave none.
    const char* tag = webkit_notification_get_tag(notification);
    auto jTag = JNI::String(tag ? notificationText(tag).c_str() : nullptr);
    // The app may destroy the WebView as it is told, which deletes the bridge, so it is not used after.
    if (!getJNIWebKitWebViewCache().m_onShowNotification.invoke(bridge->m_javaRef.get(), notificationId,
            static_cast<jstring>(jOrigin), static_cast<jstring>(jTitle), static_cast<jstring>(jBody),
            static_cast<jstring>(jTag))) {
        Logging::logError("cannot deliver WebKitWebView onShowNotification callback");
        webkit_notification_close(notification);
    }
    return TRUE;
}

// WebKit closed a notification that Java was told of: the page closed it, showed another one with its tag, went into
// the back forward cache or away (endDocumentNotifications), or the view closes. Java tells the app later, after WebKit
// returns: WebKit closes a notification whose tag a new one takes while it shows the new one, and goes on with its
// page then.
void JNIWebKitWebViewCache::onNotificationClosed(WebKitNotification* notification, WebKitWebViewBridge* bridge)
{
    auto it = std::find_if(bridge->m_notifications.begin(), bridge->m_notifications.end(),
        [notification](const auto& entry) { return entry.second == notification; });
    if (it == bridge->m_notifications.end())
        return;
    jlong notificationId = it->first;
    bridge->m_notifications.erase(it);
    // The signal holds the notification while it runs.
    g_signal_handlers_disconnect_by_data(notification, bridge);
    g_object_unref(notification);
    // Java tells the app only after WebKit returns, so the app cannot destroy the WebView here.
    if (!getJNIWebKitWebViewCache().m_onNotificationClosed.invoke(bridge->m_javaRef.get(), notificationId))
        Logging::logError("cannot deliver WebKitWebView onNotificationClosed callback");
}

// The page committed a new main document, so the notifications of the old one have no page to hear of them any more.
// WebKit closed those that went into the back forward cache, but keeps the others, so they are closed here, and Java
// hears of them as of any notification that WebKit closes (onNotificationClosed), which the bridge outlives.
void JNIWebKitWebViewCache::endDocumentNotifications(WebKitWebViewBridge* bridge)
{
    std::vector<WebKitNotification*> notifications;
    notifications.reserve(bridge->m_notifications.size());
    for (const auto& [id, notification] : bridge->m_notifications)
        notifications.push_back(WEBKIT_NOTIFICATION(g_object_ref(notification)));
    for (auto* notification : notifications) {
        webkit_notification_close(notification);
        g_object_unref(notification);
    }
}

// The web process of the page ended, and its notifications have no page to hear of them. Java keeps them, which the
// app may show as a way back to the page, but clicks and closes do nothing more. WebKit still lists them, so they are
// closed there.
void JNIWebKitWebViewCache::dropNotifications(WebKitWebViewBridge* bridge)
{
    for (auto& [id, notification] : std::exchange(bridge->m_notifications, {})) {
        g_signal_handlers_disconnect_by_data(notification, bridge);
        webkit_notification_close(notification);
        g_object_unref(notification);
    }
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
        g_signal_connect(webView, "permission-request", G_CALLBACK(onPermissionRequest), bridge),
        g_signal_connect(webView, "query-permission-state", G_CALLBACK(onQueryPermissionState), bridge),
        g_signal_connect(webView, "show-notification", G_CALLBACK(onShowNotification), bridge),
        g_signal_connect(webView, "notify::page-icons", G_CALLBACK(onPageIconsChanged), bridge),
    };
    bridge->m_backForwardListChangedId = g_signal_connect(
        webkit_web_view_get_back_forward_list(webView), "changed", G_CALLBACK(onBackForwardListChanged), bridge);
    bridge->m_downloadStartedId = g_signal_connect(
        webkit_web_view_get_network_session(webView), "download-started", G_CALLBACK(onDownloadStarted), bridge);
    auto* findController = webkit_web_view_get_find_controller(webView);
    bridge->m_findSignalIds = {
        g_signal_connect(findController, "found-text", G_CALLBACK(onFoundText), bridge),
        g_signal_connect(findController, "failed-to-find-text", G_CALLBACK(onFailedToFindText), bridge),
    };

    return bridge;
}

void JNIWebKitWebViewCache::nativeDestroy(JNIEnv*, jobject, jlong nativePtr)
{
    delete JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
}

// WebKit takes the address of a load the app asks for as the web view's URI at once, as it will be in the request.
// A javascript: address runs in the page, and is no navigation. WebKit ends the load in progress first, which the app
// hears of, and it may destroy the WebView then.
static void rememberAppLoad(WebKitWebView* webView)
{
    auto* bridge = webViewBridge(webView);
    if (!bridge)
        return;
    const char* uri = webkit_web_view_get_uri(webView);
    if (uri && g_ascii_strncasecmp(uri, "javascript:", 11))
        bridge->m_appLoadUris.emplace_back(uri);
}

void JNIWebKitWebViewCache::nativeLoadUrl(JNIEnv*, jobject, jlong nativePtr, jstring url)
{
    auto* webView = JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView;
    webkit_web_view_load_uri(webView, JNI::String(url).getContent().get());
    rememberAppLoad(webView);
}

// Downloads the address in the network session of the view, with its cookies and its user agent. WebKit tells of the
// download only once the network process started it, so it is followed from now on, as the app's: it has the app's
// gesture, and the app is told of it once its response is known, or of its failure if it fails before, with the
// address as the app gave it. Until then, the view keeps it, and cancels it if it ends first.
void JNIWebKitWebViewCache::nativeDownloadUri(JNIEnv*, jobject, jlong nativePtr, jstring uri)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    // The characters are released with the string, so it lives as long.
    JNI::String jUri(uri);
    auto content = jUri.getContent();
    g_autoptr(WebKitDownload) download = webkit_web_view_download_uri(bridge->m_webView, content.get());
    if (!download)
        return;
    bridge->m_appDownloads.push_back(WEBKIT_DOWNLOAD(g_object_ref(download)));
    g_object_set_data_full(G_OBJECT(download), appDownloadKey, g_strdup(content.get()), g_free);
    g_signal_connect(download, "decide-destination", G_CALLBACK(onDecideDownloadDestination), GINT_TO_POINTER(TRUE));
    g_signal_connect(download, "failed", G_CALLBACK(onAppDownloadFailed), nullptr);
}

void JNIWebKitWebViewCache::nativeLoadHtml(JNIEnv*, jobject, jlong nativePtr, jstring content, jstring baseUri)
{
    auto* webView = JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView;
    webkit_web_view_load_html(
        webView, JNI::String(content).getContent().get(), JNI::String(baseUri).getContent().get());
    rememberAppLoad(webView);
}

// Shows the content in place of the page at contentUri, which stays the address of the view. WebKit loads it without
// deciding on a navigation, so it is not remembered as a load of the app, and it starts with contentUri as the view's
// URI. While WebKit reports that a load failed, the content takes the entry of that load in the Back/Forward list, and
// WebKit then does not load its own error page; at other times it takes a new entry. A reload loads contentUri. A null
// base is about:blank.
void JNIWebKitWebViewCache::nativeLoadAlternateHtml(
    JNIEnv*, jobject, jlong nativePtr, jstring content, jstring contentUri, jstring baseUri)
{
    // The JNI::Strings must outlive their content, whose release uses the Java strings. A null base has no content.
    auto javaContent = JNI::String(content);
    auto javaContentUri = JNI::String(contentUri);
    auto javaBaseUri = JNI::String(baseUri);
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    // The content replaces a navigation that was decided on and whose load has not started, or that WebKit decides on
    // later, so its start is reported with contentUri rather than with the address of that navigation (onLoadChanged).
    bridge->m_navigationPending = false;
    bridge->m_alternateLoadUri = javaContentUri.getContent().get();
    webkit_web_view_load_alternate_html(bridge->m_webView, javaContent.getContent().get(),
        javaContentUri.getContent().get(), javaBaseUri.getContent().get());
}

void JNIWebKitWebViewCache::nativeStopLoading(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_stop_loading(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

void JNIWebKitWebViewCache::nativeReload(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_reload(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

void JNIWebKitWebViewCache::nativeReloadBypassCache(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_reload_bypass_cache(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
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

// Converts the request's pixels into a Java-allocated bitmap, scaled down to fit the request's limits. Returns null
// on failure. Call only after validating the request's layout with isValidImageLayout().
static JNI::ProtectedType<JNIBitmap> createSnapshotBitmap(const SnapshotRequest& request)
{
    const auto* data = static_cast<const uint8_t*>(g_bytes_get_data(request.bytes, nullptr));
    const auto scaled = scaledImageSize(request.width, request.height, request.maxWidth, request.maxHeight);
    auto bitmap = getJNIWebKitWebViewSnapshotCallbackHolderCache().createBitmap(
        request.holder.get(), scaled.width, scaled.height);
    if (!bitmap)
        return {};

    if (!fillBitmap(
            JNI::getCurrentThreadJNIEnv(), bitmap.get(), data, request.width, request.height, request.stride, scaled))
        return {};
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
        if (data && isValidImageLayout(request->width, request->height, request->stride, dataSize))
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

void JNIWebKitWebViewCache::nativeScriptDialogAnswer(
    JNIEnv*, jobject, jlong nativePtr, jlong dialogId, jboolean confirm, jstring text)
{
    // The JNI::String must outlive its content, whose release uses the Java string. A null text has no content.
    auto javaText = JNI::String(text);
    answerScriptDialog(JNI::from_jlong<WebKitWebViewBridge>(nativePtr), dialogId, static_cast<gboolean>(confirm),
        javaText.getContent().get());
}

void JNIWebKitWebViewCache::nativeTerminateWebProcess(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_web_view_terminate_web_process(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView);
}

// The icon of the page shown that best fits a size by size square, scaled down to fit it, or null if it has none.
JNIBitmap JNIWebKitWebViewCache::nativeGetPageIcon(JNIEnv* env, jobject, jlong nativePtr, jint size)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    auto bitmap = createIconBitmap(imageForSize(bridge->m_pageIcons, size), size);
    // A reference of its own for Java, as the protected one goes when this returns.
    return bitmap ? reinterpret_cast<JNIBitmap>(env->NewLocalRef(bitmap.get())) : nullptr;
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

// Highlights the matches of the text and selects the first one from the start of the selection of the page, or of the
// page, but not the selected text itself, and scrolls to it, or with WEBKIT_FIND_OPTIONS_BACKWARDS the last one before.
// The options are those of WebKitFindOptions, which the Java side checks.
void JNIWebKitWebViewCache::nativeFindSearch(
    JNIEnv*, jobject, jlong nativePtr, jstring text, jint options, jint maxMatchCount)
{
    webkit_find_controller_search(
        webkit_web_view_get_find_controller(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView),
        JNI::String(text).getContent().get(), static_cast<guint32>(options), static_cast<guint>(maxMatchCount));
}

// Takes the highlights of the matches away. The selection of the current match stays.
void JNIWebKitWebViewCache::nativeFindFinish(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_find_controller_search_finish(
        webkit_web_view_get_find_controller(JNI::from_jlong<WebKitWebViewBridge>(nativePtr)->m_webView));
}

void JNIWebKitWebViewCache::nativePermissionRequestAnswer(
    JNIEnv*, jobject, jlong nativePtr, jlong requestId, jboolean allow)
{
    answerPermissionRequest(JNI::from_jlong<WebKitWebViewBridge>(nativePtr), requestId, allow);
}

// Answers a query that Java took, unless it was answered already or ended with its document.
void JNIWebKitWebViewCache::nativePermissionStateQueryAnswer(
    JNIEnv*, jobject, jlong nativePtr, jlong queryId, jint state)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    auto it = bridge->m_permissionStateQueries.find(queryId);
    if (it == bridge->m_permissionStateQueries.end())
        return;
    WebKitPermissionStateQuery* query = it->second;
    bridge->m_permissionStateQueries.erase(it);
    webkit_permission_state_query_finish(query, static_cast<WebKitPermissionState>(state));
    webkit_permission_state_query_unref(query);
}

// The user tapped a notification that is not closed: the page hears of it with a click event.
void JNIWebKitWebViewCache::nativeNotificationClick(JNIEnv*, jobject, jlong nativePtr, jlong notificationId)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    auto it = bridge->m_notifications.find(notificationId);
    if (it != bridge->m_notifications.end())
        webkit_notification_clicked(it->second);
}

// The app closes a notification that is not closed yet, as the user dismissed it or it does not show it: the page hears
// of it with a close event, and Java is not told again.
void JNIWebKitWebViewCache::nativeNotificationClose(JNIEnv*, jobject, jlong nativePtr, jlong notificationId)
{
    auto* bridge = JNI::from_jlong<WebKitWebViewBridge>(nativePtr);
    auto it = bridge->m_notifications.find(notificationId);
    if (it == bridge->m_notifications.end())
        return;
    WebKitNotification* notification = it->second;
    bridge->m_notifications.erase(it);
    g_signal_handlers_disconnect_by_data(notification, bridge);
    webkit_notification_close(notification);
    g_object_unref(notification);
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
