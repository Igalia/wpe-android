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

#include "WebKitDownloadBridge.h"

#include "JNI/JNI.h"
#include "JNIMappings.h"
#include "Logging.h"

#include <memory>
#include <vector>

DECLARE_JNI_CLASS_SIGNATURE(JNIWebKitDownload, "org/wpewebkit/wpe/WebKitDownload");

namespace WebKit {

// A download whose destination is to be decided, and the Java object that reports it. It lives until WebKit ends the
// download, which it always does, also when it fails or is cancelled.
struct WebKitDownloadBridge {
    explicit WebKitDownloadBridge(WebKitDownload* download)
        : m_download(WEBKIT_DOWNLOAD(g_object_ref(download)))
    {
    }

    ~WebKitDownloadBridge()
    {
        for (gulong id : m_signalIds)
            g_signal_handler_disconnect(m_download, id);
        g_object_unref(m_download);
    }

    WebKitDownloadBridge(const WebKitDownloadBridge&) = delete;
    WebKitDownloadBridge& operator=(const WebKitDownloadBridge&) = delete;

    WebKitDownload* m_download;
    JNI::GlobalRef<JNIWebKitDownload> m_javaRef;
    std::vector<gulong> m_signalIds;
    // Whether the failure was reported, before the end of the download.
    bool m_failed {false};
};

class JNIWebKitDownloadCache final : public JNI::TypedClass<JNIWebKitDownload> {
public:
    JNIWebKitDownloadCache()
        : JNI::TypedClass<JNIWebKitDownload>(true)
        , m_onProgress(getMethod<void(jlong, jlong)>("onProgress"))
        , m_onFinished(getMethod<void()>("onFinished"))
        , m_onFailed(getMethod<void(jint, jstring)>("onFailed"))
    {
        registerNativeMethods(JNI::NativeMethod<void(jlong)>("nativeAttach", nativeAttach),
            JNI::NativeMethod<void(jlong, jstring)>("nativeSetDestination", nativeSetDestination),
            JNI::NativeMethod<void(jlong)>("nativeCancel", nativeCancel));
    }

    const JNI::Method<void(jlong, jlong)> m_onProgress;
    const JNI::Method<void()> m_onFinished;
    const JNI::Method<void(jint, jstring)> m_onFailed;

    static void onProgress(GObject*, GParamSpec*, WebKitDownloadBridge* bridge);
    static void onFailed(WebKitDownload*, GError* error, WebKitDownloadBridge* bridge);
    static void onFinished(WebKitDownload*, WebKitDownloadBridge* bridge);

private:
    static void nativeAttach(JNIEnv* env, jobject javaDownload, jlong nativePtr);
    static void nativeSetDestination(JNIEnv*, jobject, jlong nativePtr, jstring path);
    static void nativeCancel(JNIEnv*, jobject, jlong nativePtr);
};

static const JNIWebKitDownloadCache& getJNIWebKitDownloadCache()
{
    static const JNIWebKitDownloadCache s_singleton;
    return s_singleton;
}

// The size the server announced, or -1 if it did not.
static jlong contentLength(WebKitDownload* download)
{
    auto* response = webkit_download_get_response(download);
    guint64 length = response ? webkit_uri_response_get_content_length(response) : 0;
    return length ? static_cast<jlong>(length) : -1;
}

// WebKit tells of the estimated progress at most 60 times a second, unless it moved by a hundredth or more.
void JNIWebKitDownloadCache::onProgress(GObject*, GParamSpec*, WebKitDownloadBridge* bridge)
{
    if (!bridge->m_javaRef)
        return;
    auto received = static_cast<jlong>(webkit_download_get_received_data_length(bridge->m_download));
    if (!getJNIWebKitDownloadCache().m_onProgress.invoke(
            bridge->m_javaRef.get(), received, contentLength(bridge->m_download)))
        Logging::logError("cannot deliver WebKitDownload onProgress callback");
}

void JNIWebKitDownloadCache::onFailed(WebKitDownload*, GError* error, WebKitDownloadBridge* bridge)
{
    bridge->m_failed = true;
    if (!bridge->m_javaRef)
        return;
    jint code = error && error->domain == WEBKIT_DOWNLOAD_ERROR ? error->code : 0;
    auto jDescription = JNI::String(error && error->message ? error->message : "");
    if (!getJNIWebKitDownloadCache().m_onFailed.invoke(
            bridge->m_javaRef.get(), code, static_cast<jstring>(jDescription)))
        Logging::logError("cannot deliver WebKitDownload onFailed callback");
}

// Also the end of a download that failed, after its failure.
void JNIWebKitDownloadCache::onFinished(WebKitDownload*, WebKitDownloadBridge* bridge)
{
    std::unique_ptr<WebKitDownloadBridge> ownedBridge(bridge);
    if (bridge->m_failed || !bridge->m_javaRef)
        return;
    if (!getJNIWebKitDownloadCache().m_onFinished.invoke(bridge->m_javaRef.get()))
        Logging::logError("cannot deliver WebKitDownload onFinished callback");
}

void JNIWebKitDownloadCache::nativeAttach(JNIEnv* env, jobject javaDownload, jlong nativePtr)
{
    JNI::from_jlong<WebKitDownloadBridge>(nativePtr)->m_javaRef = JNI::GlobalRef<JNIWebKitDownload>(env, javaDownload);
}

void JNIWebKitDownloadCache::nativeSetDestination(JNIEnv*, jobject, jlong nativePtr, jstring path)
{
    auto* download = JNI::from_jlong<WebKitDownloadBridge>(nativePtr)->m_download;
    webkit_download_set_allow_overwrite(download, TRUE);
    webkit_download_set_destination(download, JNI::String(path).getContent().get());
}

void JNIWebKitDownloadCache::nativeCancel(JNIEnv*, jobject, jlong nativePtr)
{
    webkit_download_cancel(JNI::from_jlong<WebKitDownloadBridge>(nativePtr)->m_download);
}

jlong createWebKitDownloadBridge(WebKitDownload* download)
{
    auto* bridge = new WebKitDownloadBridge(download);
    bridge->m_signalIds = {
        g_signal_connect(
            download, "notify::estimated-progress", G_CALLBACK(JNIWebKitDownloadCache::onProgress), bridge),
        g_signal_connect(download, "failed", G_CALLBACK(JNIWebKitDownloadCache::onFailed), bridge),
        g_signal_connect(download, "finished", G_CALLBACK(JNIWebKitDownloadCache::onFinished), bridge),
    };
    return reinterpret_cast<jlong>(bridge);
}

bool isWebKitDownloadBridgeAttached(jlong bridgePtr)
{
    return static_cast<bool>(JNI::from_jlong<WebKitDownloadBridge>(bridgePtr)->m_javaRef);
}

void configureWebKitDownloadJNIMappings()
{
    getJNIWebKitDownloadCache();
}

} // namespace WebKit
