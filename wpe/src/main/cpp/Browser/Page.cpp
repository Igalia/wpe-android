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
#include "RendererASurfaceTransaction.h"

#include <android/native_window_jni.h>
#include <unistd.h>

/***********************************************************************************************************************
 * JNI mapping with Java Page class
 **********************************************************************************************************************/

DECLARE_JNI_CLASS_SIGNATURE(JNISurface, "android/view/Surface");

class JNIPageCache;
const JNIPageCache& getJNIPageCache();

class JNIPageCache final : public JNI::TypedClass<JNIPage> {
public:
    JNIPageCache();

    static void onLoadChanged(Page* page, WebKitLoadEvent loadEvent, WebKitWebView* /*webView*/) noexcept
    {
        callJavaMethod(getJNIPageCache().m_onLoadChanged, page->m_pageJavaInstance.get(), static_cast<int>(loadEvent));
    }

    static void onLoadProgress(Page* page, GParamSpec* /*pspec*/, WebKitWebView* webView) noexcept
    {
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

    static bool onFullscreenRequest(Page* page, bool fullscreen) noexcept
    {
        if (page->m_viewBackendExportable != nullptr) {
            page->m_isFullscreenRequested = fullscreen;
            if (fullscreen) {
                callJavaMethod(getJNIPageCache().m_onEnterFullscreenMode, page->m_pageJavaInstance.get());
            } else {
                callJavaMethod(getJNIPageCache().m_onExitFullscreenMode, page->m_pageJavaInstance.get());
                wpe_view_backend_dispatch_did_exit_fullscreen(
                    wpe_android_view_backend_exportable_get_view_backend(page->m_viewBackendExportable));
            }
        }

        return true;
    }

    void onInputMethodContextIn(jobject obj) const noexcept { callJavaMethod(m_onInputMethodContextIn, obj); }

    void onInputMethodContextOut(jobject obj) const noexcept { callJavaMethod(m_onInputMethodContextOut, obj); }

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

    const JNI::Method<void(jint)> m_onLoadChanged;
    const JNI::Method<void(jdouble)> m_onLoadProgress;
    const JNI::Method<void(jstring)> m_onUriChanged;
    const JNI::Method<void(jstring, jboolean, jboolean)> m_onTitleChanged;
    const JNI::Method<void()> m_onInputMethodContextIn;
    const JNI::Method<void()> m_onInputMethodContextOut;
    const JNI::Method<void()> m_onEnterFullscreenMode;
    const JNI::Method<void()> m_onExitFullscreenMode;

    static jlong nativeInit(JNIEnv* env, jobject obj, jint width, jint height);
    static void nativeClose(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeDestroy(JNIEnv* env, jobject obj, jlong pagePtr) noexcept;
    static void nativeLoadUrl(JNIEnv* env, jobject obj, jlong pagePtr, jstring url) noexcept;
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
};

const JNIPageCache& getJNIPageCache()
{
    static const JNIPageCache s_singleton;
    return s_singleton;
}

JNIPageCache::JNIPageCache()
    : JNI::TypedClass<JNIPage>(true)
    , m_onLoadChanged(getMethod<void(jint)>("onLoadChanged"))
    , m_onLoadProgress(getMethod<void(jdouble)>("onLoadProgress"))
    , m_onUriChanged(getMethod<void(jstring)>("onUriChanged"))
    , m_onTitleChanged(getMethod<void(jstring, jboolean, jboolean)>("onTitleChanged"))
    , m_onInputMethodContextIn(getMethod<void()>("onInputMethodContextIn"))
    , m_onInputMethodContextOut(getMethod<void()>("onInputMethodContextOut"))
    , m_onEnterFullscreenMode(getMethod<void()>("onEnterFullscreenMode"))
    , m_onExitFullscreenMode(getMethod<void()>("onExitFullscreenMode"))
{
    registerNativeMethods(JNI::NativeMethod<jlong(jint, jint)>("nativeInit", JNIPageCache::nativeInit),
        JNI::NativeMethod<void(jlong)>("nativeClose", JNIPageCache::nativeClose),
        JNI::NativeMethod<void(jlong)>("nativeDestroy", JNIPageCache::nativeDestroy),
        JNI::NativeMethod<void(jlong, jstring)>("nativeLoadUrl", JNIPageCache::nativeLoadUrl),
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
            "nativeRequestExitFullscreenMode", JNIPageCache::nativeRequestExitFullscreenMode));
}

jlong JNIPageCache::nativeInit(JNIEnv* env, jobject obj, jint width, jint height)
{
    Logging::logDebug("Page::nativeInit(%p, %d, %d) [tid %d]", obj, width, height, gettid());
    // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
    Page* page = new Page(env, reinterpret_cast<JNIPage>(obj), width, height);
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
    if ((page != nullptr) && (page->m_viewBackendExportable != nullptr) && page->m_renderer) {
        uint32_t uWidth = std::max(0, width);
        uint32_t uHeight = std::max(0, height);
        wpe_view_backend_dispatch_set_size(
            wpe_android_view_backend_exportable_get_view_backend(page->m_viewBackendExportable), uWidth, uHeight);
        page->m_renderer->onSurfaceChanged(format, uWidth, uHeight);
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
    Logging::logDebug("Page::nativeOnTouchEvent(%ld, %d, %f, %f) [tid %d]", time, type, xCoord, yCoord, gettid());
    Page* page = reinterpret_cast<Page*>(pagePtr); // NOLINT(performance-no-int-to-ptr)
    if ((page != nullptr) && (page->m_viewBackendExportable != nullptr)) {
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

        wpe_input_touch_event_raw touchEventRaw = {.type = touchEventType,
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
            wpe_android_view_backend_exportable_get_view_backend(page->m_viewBackendExportable), &touchEvent);
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
    if ((page != nullptr) && (page->m_viewBackendExportable != nullptr)) {
        wpe_view_backend_dispatch_request_exit_fullscreen(
            wpe_android_view_backend_exportable_get_view_backend(page->m_viewBackendExportable));
    }
}

/***********************************************************************************************************************
 * Native Page class implementation
 **********************************************************************************************************************/

void Page::configureJNIMappings() { getJNIPageCache(); }

Page::Page(JNIEnv* env, JNIPage jniPage, int width, int height)
    : m_pageJavaInstance(JNI::createTypedProtectedRef(env, jniPage, true))
    , m_inputMethodContext(this)
{
    static const wpe_android_view_backend_exportable_client s_exportableClient
        = {.export_buffer = +[](void* data, AHardwareBuffer* buffer, uint32_t poolId, uint32_t bufferId) noexcept {
              Logging::logDebug("s_exportableClient::export_buffer(%p, %u, %u)", buffer, poolId, bufferId);
              Page* page = reinterpret_cast<Page*>(data);
              page->handleExportedBuffer(std::make_shared<ExportedBuffer>(buffer, poolId, bufferId));
          }};

    uint32_t uWidth = std::max(0, width);
    uint32_t uHeight = std::max(0, height);

    m_viewBackendExportable = wpe_android_view_backend_exportable_create(&s_exportableClient, this, uWidth, uHeight);
    m_renderer = std::make_shared<RendererASurfaceTransaction>(m_viewBackendExportable, uWidth, uHeight);

    wpe_view_backend* wpeBackend = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    WebKitWebViewBackend* viewBackend = webkit_web_view_backend_new(wpeBackend,
        reinterpret_cast<GDestroyNotify>(wpe_android_view_backend_exportable_destroy), m_viewBackendExportable);

    m_webView = webkit_web_view_new_with_context(viewBackend, Browser::instance().webContext());
    webkit_web_view_set_input_method_context(m_webView, m_inputMethodContext.webKitInputMethodContext());

    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "load-changed", G_CALLBACK(JNIPageCache::onLoadChanged), this));
    m_signalHandlers.push_back(g_signal_connect_swapped(
        m_webView, "notify::estimated-load-progress", G_CALLBACK(JNIPageCache::onLoadProgress), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::uri", G_CALLBACK(JNIPageCache::onUriChanged), this));
    m_signalHandlers.push_back(
        g_signal_connect_swapped(m_webView, "notify::title", G_CALLBACK(JNIPageCache::onTitleChanged), this));

    wpe_view_backend_set_fullscreen_handler(
        wpeBackend, reinterpret_cast<wpe_view_backend_fullscreen_handler>(JNIPageCache::onFullscreenRequest), this);
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

        m_viewBackendExportable = nullptr;
        g_object_unref(m_webView);
        m_webView = nullptr;
    }
}

void Page::onInputMethodContextIn() noexcept { getJNIPageCache().onInputMethodContextIn(m_pageJavaInstance.get()); }

void Page::onInputMethodContextOut() noexcept { getJNIPageCache().onInputMethodContextOut(m_pageJavaInstance.get()); }

void Page::handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer) noexcept
{
    if (m_renderer && (m_viewBackendExportable != nullptr)) {
        Logging::logDebug("Page::handleExportedBuffer(%p) - Size (%ux%u)", exportedBuffer.get(),
            exportedBuffer->width(), exportedBuffer->height());

        m_renderer->handleExportedBuffer(exportedBuffer);

        if (m_isFullscreenRequested && (exportedBuffer->width() == m_renderer->width())
            && (exportedBuffer->height() == m_renderer->height())) {
            Logging::logDebug("Fullscreen ready");
            m_isFullscreenRequested = false;
            wpe_view_backend_dispatch_did_enter_fullscreen(
                wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable));
        }
    }
}
