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
#include "JNIHelper.h"
#include "Logging.h"
#include "RendererASurfaceTransaction.h"

#include <algorithm>
#include <android/api-level.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <jni.h>

namespace {

/** JNI Interface **/

inline Page* getPage(JNIEnv* env, jobject obj, bool resetNativePtrField = false)
{
    jclass clazz = env->GetObjectClass(obj);
    jfieldID nativePtrField = env->GetFieldID(clazz, "nativePtr", "J");
    Page* page = reinterpret_cast<Page*>(env->GetLongField(obj, nativePtrField));
    if (resetNativePtrField)
        env->SetLongField(obj, nativePtrField, reinterpret_cast<intptr_t>(page));
    env->DeleteLocalRef(clazz);
    return page;
}

void pageInit(JNIEnv* env, jobject obj, jint width, jint height)
{
    ALOGV("pageInit(%p, %d, %d) [tid %d]", obj, width, height, gettid());

    jclass clazz = env->GetObjectClass(obj);
    auto page = new Page(width, height, std::make_shared<PageEventObserver>(env, clazz, obj));
    jfieldID nativePtrField = env->GetFieldID(clazz, "nativePtr", "J");
    env->SetLongField(obj, nativePtrField, reinterpret_cast<intptr_t>(page));
    page->init();
    env->DeleteLocalRef(clazz);
}

void pageClose(JNIEnv* env, jobject obj)
{
    ALOGV("pageClose(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->close();
}

void pageDestroy(JNIEnv* env, jobject obj)
{
    ALOGV("pageClose(%p) [tid %d]", obj, gettid());
    Page* page = getPage(env, obj, true);
    delete page;
}

void pageLoadUrl(JNIEnv* env, jobject obj, jstring jurl)
{
    const char* urlChars = env->GetStringUTFChars(jurl, nullptr);
    ALOGV("pageLoadUrl(%p, %s) [tid %d]", obj, urlChars, gettid());
    getPage(env, obj)->loadUrl(urlChars);
    env->ReleaseStringUTFChars(jurl, urlChars);
}

void pageGoBack(JNIEnv* env, jobject obj)
{
    ALOGV("pageGoBack(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->goBack();
}

void pageGoForward(JNIEnv* env, jobject obj)
{
    ALOGV("pageGoForward(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->goForward();
}

void pageStopLoading(JNIEnv* env, jobject obj)
{
    ALOGV("pageStopLoading(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->stopLoading();
}

void pageReload(JNIEnv* env, jobject obj)
{
    ALOGV("pageReload(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->reload();
}

void pageSurfaceCreated(JNIEnv* env, jobject obj, jobject jSurface)
{
    ALOGV("pageSurfaceCreated(%p, %p) [tid %d]", obj, jSurface, gettid());

    getPage(env, obj)->surfaceCreated(ANativeWindow_fromSurface(env, jSurface));
}

void pageSurfaceDestroyed(JNIEnv* env, jobject obj)
{
    ALOGV("pageSurfaceDestroyed(%p) [tid %d]", obj, gettid());
    auto* page = getPage(env, obj);
    page->surfaceDestroyed();
}

void pageSurfaceChanged(JNIEnv* env, jobject obj, jint format, jint width, jint height)
{
    ALOGV("pageSurfaceChanged(%p, %d, %d, %d) [tid %d]", obj, format, width, height, gettid());
    getPage(env, obj)->surfaceChanged(format, width, height);
}

void pageSurfaceRedrawNeeded(JNIEnv* env, jobject obj)
{
    ALOGV("pageSurfaceRedrawNeeded(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->surfaceRedrawNeeded();
}

void pageSetZoomLevel(JNIEnv* env, jobject obj, jdouble zoomLevel)
{
    ALOGV("pageSetZoomLevel(%p, %f) [tid %d]", obj, zoomLevel, gettid());
    getPage(env, obj)->setZoomLevel(zoomLevel);
}

void pageOnTouchEvent(JNIEnv* env, jobject obj, jlong time, jint type, jfloat x, jfloat y)
{
    ALOGV("pageTouchEvent(%p, %ld, %d, %f, %f) [tid %d]", obj, static_cast<long>(time), type, x, y, gettid());
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
    }

    struct wpe_input_touch_event_raw touchEventRaw;
    touchEventRaw.type = touchEventType;
    touchEventRaw.time = (uint32_t)time;
    touchEventRaw.id = 0;
    touchEventRaw.x = (int32_t)x;
    touchEventRaw.y = (int32_t)y;

    getPage(env, obj)->onTouch(&touchEventRaw);
}

void pageSetInputMethodContent(JNIEnv* env, jobject obj, jchar c)
{
    ALOGV("pageSetInputMethodContent(%p, %c) [tid %d]", obj, c, gettid());
    getPage(env, obj)->setInputMethodContent(c);
}

void pageDeleteInputMethodContent(JNIEnv* env, jobject obj, jint offset)
{
    ALOGV("pageDeleteInputMethodContent(%p, %d) [tid %d]", obj, offset, gettid());
    getPage(env, obj)->deleteInputMethodContent(offset);
}

void pageRequestExitFullscreenMode(JNIEnv* env, jobject obj)
{
    ALOGV("pageRequestExitFullscreenMode(%p) [tid %d]", obj, gettid());
    getPage(env, obj)->requestExitFullscreen();
}

void pageUpdateAllSettings(JNIEnv* env, jobject obj, jobject jPageSettings)
{
    ALOGV("pageUpdateAllSettings(%p) [tid %d]", obj, gettid());

    PageSettings settings;

    jclass jPageSettingsClass = env->GetObjectClass(jPageSettings);

    jmethodID methodID = env->GetMethodID(jPageSettingsClass, "getUserAgentString", "()Ljava/lang/String;");
    if (methodID != nullptr) {
        jstring jniString = reinterpret_cast<jstring>(env->CallObjectMethod(jPageSettings, methodID));
        Wpe::Android::checkException(env);
        const char* str = env->GetStringUTFChars(jniString, nullptr);
        settings.setUserAgent(str);
        env->ReleaseStringUTFChars(jniString, str);
    } else
        ALOGE("Cannot update user agent setting (cannot find \"getUserAgentString\" method)");

    methodID = env->GetMethodID(jPageSettingsClass, "getMediaPlaybackRequiresUserGesture", "()Z");
    if (methodID != nullptr) {
        jboolean require = env->CallBooleanMethod(jPageSettings, methodID);
        Wpe::Android::checkException(env);
        settings.setMediaPlayerRequiresUserGesture(require);
    } else
        ALOGE("Cannot update media playback requires user gesture setting (cannot find \"getUserAgentString\" "
              "method)");

    env->DeleteLocalRef(jPageSettingsClass);

    getPage(env, obj)->updateAllSettings(settings);
}

/** Callbacks **/

void onLoadChanged(WebKitWebView*, WebKitLoadEvent loadEvent, gpointer data)
{
    auto* observer = reinterpret_cast<PageEventObserver*>(data);
    if (observer != nullptr) {
        observer->onLoadChanged(loadEvent);
    }
}

void onLoadProgressChanged(WebKitWebView* webView, GParamSpec*, gpointer data)
{
    auto* observer = reinterpret_cast<PageEventObserver*>(data);
    if (observer != nullptr) {
        gdouble progress = webkit_web_view_get_estimated_load_progress(webView);
        observer->onLoadProgress(progress);
    }
}

void onUriChanged(WebKitWebView* webView, GParamSpec*, gpointer data)
{
    auto* observer = reinterpret_cast<PageEventObserver*>(data);
    if (observer != nullptr) {
        const char* uri = webkit_web_view_get_uri(webView);
        observer->onUriChanged(uri);
    }
}

void onTitleChanged(WebKitWebView* webView, GParamSpec*, gpointer data)
{
    auto* observer = reinterpret_cast<PageEventObserver*>(data);
    if (observer != nullptr) {
        const char* title = webkit_web_view_get_title(webView);
        gboolean canGoBack = webkit_web_view_can_go_back(webView);
        gboolean canGoForward = webkit_web_view_can_go_forward(webView);
        observer->onTitleChanged(title, canGoBack, canGoForward);
    }
}

bool onDomFullscreenRequest(void* data, bool fullscreen)
{
    ALOGV("onDomFullscreenRequest() fullscreen: %s", fullscreen ? "true" : "false");
    auto* page = reinterpret_cast<Page*>(data);
    if (page != nullptr) {
        page->domFullscreenRequest(fullscreen);
    }

    return true;
}

} // namespace

struct wpe_android_view_backend_exportable_client Page::s_exportableClient = {
    // export_buffer
    [](void* data, AHardwareBuffer* buffer, uint32_t poolID, uint32_t bufferID) {
        ALOGV("s_exportableClient::export_buffer() buffer %p poolID %u bufferID %u", buffer, poolID, bufferID);
        auto* page = reinterpret_cast<Page*>(data);

        Browser::instance().handleExportedBuffer(*page, std::make_shared<ExportedBuffer>(buffer, poolID, bufferID));
    }};

Page::Page(int width, int height, std::shared_ptr<PageEventObserver> observer)
    : m_width(width)
    , m_height(height)
    , m_observer(observer)
    , m_webView(nullptr)
    , m_viewBackendExportable(nullptr)
    , m_initialized(false)
{
    m_signalHandlers.clear();
    m_renderer = std::make_unique<RendererASurfaceTransaction>(*this, m_width, m_height);
}

void Page::init()
{
    if (m_initialized) {
        return;
    }

    ALOGV("Initializing Page");
    struct wpe_view_backend* wpeBackend;
    {
        m_viewBackendExportable = wpe_android_view_backend_exportable_create(
            &s_exportableClient, this, std::max(0, m_width), std::max(0, m_height));
        wpeBackend = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    }

    auto* viewBackend = webkit_web_view_backend_new(wpeBackend, nullptr, nullptr);

    m_webView = webkit_web_view_new_with_context(viewBackend, Browser::instance().webContext());

    m_signalHandlers.push_back(
        g_signal_connect(m_webView, "load-changed", G_CALLBACK(onLoadChanged), m_observer.get()));
    m_signalHandlers.push_back(g_signal_connect(
        m_webView, "notify::estimated-load-progress", G_CALLBACK(onLoadProgressChanged), m_observer.get()));
    m_signalHandlers.push_back(g_signal_connect(m_webView, "notify::uri", G_CALLBACK(onUriChanged), m_observer.get()));
    m_signalHandlers.push_back(
        g_signal_connect(m_webView, "notify::title", G_CALLBACK(onTitleChanged), m_observer.get()));

    m_input_method_context = input_method_context_new(m_observer);

    webkit_web_view_set_input_method_context(m_webView, m_input_method_context);

    wpe_view_backend_set_fullscreen_handler(
        webkit_web_view_backend_get_wpe_backend(viewBackend), onDomFullscreenRequest, this);

    ALOGV("Created WebKitWebView %p", m_webView);

    m_initialized = true;
}

Page::~Page()
{
    close();
    g_object_unref(m_input_method_context);
}

void Page::close()
{
    ALOGV("Page::close");
    if (!m_initialized) {
        return;
    }
    m_initialized = false;
    for (auto& handler : m_signalHandlers) {
        g_signal_handler_disconnect(m_webView, handler);
    }
    m_signalHandlers.clear();
    webkit_web_view_try_close(m_webView);

    g_clear_object(&m_webView);
}

void Page::loadUrl(const char* url)
{
    ALOGV("Page::loadUrl %s %p", url, m_webView);
    webkit_web_view_load_uri(m_webView, url);
}

void Page::goBack() { webkit_web_view_go_back(m_webView); }

void Page::goForward() { webkit_web_view_go_forward(m_webView); }

void Page::stopLoading() { webkit_web_view_stop_loading(m_webView); }

void Page::reload() { webkit_web_view_reload(m_webView); }

void Page::surfaceCreated(ANativeWindow* window) { m_renderer->surfaceCreated(window); }

void Page::surfaceChanged(int format, int width, int height)
{
    uint32_t uWidth = uint32_t(std::max(0, width));
    uint32_t uHeight = uint32_t(std::max(0, height));
    ALOGV("Page::surfaceChanged() format %d size (%ux%u)", format, uWidth, uHeight);
    struct wpe_view_backend* viewBackend
        = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    wpe_view_backend_dispatch_set_size(viewBackend, uWidth, uHeight);
    m_renderer->surfaceChanged(format, uWidth, uHeight);
}

void Page::surfaceRedrawNeeded() { m_renderer->surfaceRedrawNeeded(); }

void Page::surfaceDestroyed() { m_renderer->surfaceDestroyed(); }

void Page::handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer)
{
    ALOGV("Page::renderFrame() %p exportedBuffer %p size (%ux%u)", this, exportedBuffer.get(),
        exportedBuffer->size.width, exportedBuffer->size.height);
    m_renderer->handleExportedBuffer(exportedBuffer);

    if (m_resizing_fullscreen && exportedBuffer->size.width == m_renderer->width()
        && exportedBuffer->size.height == m_renderer->height()) {
        fullscreenImageReady();
    }
}

void Page::onTouch(wpe_input_touch_event_raw* touchEventRaw)
{
    struct wpe_input_touch_event touchEvent {};
    touchEvent.touchpoints = touchEventRaw;
    touchEvent.touchpoints_length = 1;
    touchEvent.type = touchEventRaw->type;
    touchEvent.id = 0;
    touchEvent.time = touchEventRaw->time;

    struct wpe_view_backend* viewBackend
        = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    wpe_view_backend_dispatch_touch_event(viewBackend, &touchEvent);
}

void Page::setZoomLevel(double zoomLevel) { webkit_web_view_set_zoom_level(m_webView, zoomLevel); }

void Page::setInputMethodContent(const char c) { input_method_context_set_content(m_input_method_context, c); }

void Page::deleteInputMethodContent(int offset) { input_method_context_delete_content(m_input_method_context, offset); }

void Page::domFullscreenRequest(bool fullscreen)
{
    ALOGV("Page::domFullscreenRequest()");
    m_resizing_fullscreen = fullscreen;
    if (fullscreen) {
        m_observer->enterFullscreenMode();
    } else {
        m_observer->exitFullscreenMode();

        struct wpe_view_backend* viewBackend
            = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
        wpe_view_backend_dispatch_did_exit_fullscreen(viewBackend);
    }
}

void Page::requestExitFullscreen()
{
    struct wpe_view_backend* viewBackend
        = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    wpe_view_backend_dispatch_request_exit_fullscreen(viewBackend);
}

void Page::fullscreenImageReady()
{
    ALOGV("Page::fullscreenImageReady()");
    m_resizing_fullscreen = false;
    struct wpe_view_backend* viewBackend
        = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    wpe_view_backend_dispatch_did_enter_fullscreen(viewBackend);
}

void Page::updateAllSettings(const PageSettings& settings)
{
    ALOGV("Page::updateAllSettings()");

    auto* webViewSettings = webkit_web_view_get_settings(m_webView);
    webkit_settings_set_user_agent(webViewSettings, settings.userAgent().c_str());
    webkit_settings_set_media_playback_requires_user_gesture(
        webViewSettings, settings.mediaPlayerRequiresUserGesture());
    webkit_web_view_set_settings(m_webView, webViewSettings);
}

int Page::registerJNINativeFunctions(JNIEnv* env)
{
    jclass clazz = env->FindClass("com/wpe/wpe/Page");
    if (clazz == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[] = {{"nativeInit", "(II)V", reinterpret_cast<void*>(pageInit)},
        {"nativeClose", "()V", reinterpret_cast<void*>(pageClose)},
        {"nativeDestroy", "()V", reinterpret_cast<void*>(pageDestroy)},
        {"nativeLoadUrl", "(Ljava/lang/String;)V", reinterpret_cast<void*>(pageLoadUrl)},
        {"nativeGoBack", "()V", reinterpret_cast<void*>(pageGoBack)},
        {"nativeGoForward", "()V", reinterpret_cast<void*>(pageGoForward)},
        {"nativeStopLoading", "()V", reinterpret_cast<void*>(pageStopLoading)},
        {"nativeReload", "()V", reinterpret_cast<void*>(pageReload)},
        {"nativeSurfaceCreated", "(Landroid/view/Surface;)V", reinterpret_cast<void*>(pageSurfaceCreated)},
        {"nativeSurfaceDestroyed", "()V", reinterpret_cast<void*>(pageSurfaceDestroyed)},
        {"nativeSurfaceChanged", "(III)V", reinterpret_cast<void*>(pageSurfaceChanged)},
        {"nativeSurfaceRedrawNeeded", "()V", reinterpret_cast<void*>(pageSurfaceRedrawNeeded)},
        {"nativeSetZoomLevel", "(D)V", reinterpret_cast<void*>(pageSetZoomLevel)},
        {"nativeOnTouchEvent", "(JIFF)V", reinterpret_cast<void*>(pageOnTouchEvent)},
        {"nativeSetInputMethodContent", "(C)V", reinterpret_cast<void*>(pageSetInputMethodContent)},
        {"nativeDeleteInputMethodContent", "(I)V", reinterpret_cast<void*>(pageDeleteInputMethodContent)},
        {"nativeRequestExitFullscreenMode", "()V", reinterpret_cast<void*>(pageRequestExitFullscreenMode)},
        {"nativeUpdateAllSettings", "(Lcom/wpe/wpe/PageSettings;)V", reinterpret_cast<void*>(pageUpdateAllSettings)}};
    int result = env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(clazz);
    return result;
}
