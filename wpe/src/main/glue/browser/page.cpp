#include <algorithm>
#include "page.h"

#include "browser.h"
#include "logging.h"
#include "renderer_asurfacetransaction.h"
#include "renderer_fallback.h"
#include <android/api-level.h>
#include <android/hardware_buffer.h>

static void onLoadChanged(WebKitWebView *, WebKitLoadEvent, gpointer);

static void onLoadProgressChanged(WebKitWebView *, GParamSpec *, gpointer);

static void onUriChanged(WebKitWebView *, GParamSpec *, gpointer);

static void onTitleChanged(WebKitWebView *, GParamSpec *, gpointer);

Page::Page(int width, int height, std::shared_ptr<PageEventObserver> observer)
        : m_width(width),
          m_height(height),
          m_observer(observer),
          m_webView(nullptr),
          m_viewBackendExportable(nullptr),
          m_initialized(false) {
    m_signalHandlers.clear();

#if __ANDROID_API__ >= 29
    if (android_get_device_api_level() >= 29)
        m_renderer = std::make_unique<RendererASurfaceTransaction>(*this, m_width, m_height);
    else
#endif
        m_renderer = std::make_unique<RendererFallback>(*this, m_width, m_height);
}

void Page::init()
{
    if (m_initialized) {
        return;
    }

    ALOGV("Initializing Page");
    struct wpe_view_backend *wpeBackend;
    {
        m_viewBackendExportable = wpe_android_view_backend_exportable_create(&s_exportableClient, this,
                                                                             std::max(0, m_width),
                                                                             std::max(0, m_height));
        wpeBackend = wpe_android_view_backend_exportable_get_view_backend(m_viewBackendExportable);
    }

    auto *viewBackend = webkit_web_view_backend_new(wpeBackend, NULL, NULL);

    m_webView = webkit_web_view_new(viewBackend);

    m_signalHandlers.push_back(
            g_signal_connect(m_webView, "load-changed", G_CALLBACK(onLoadChanged),
                             m_observer.get()));
    m_signalHandlers.push_back(
            g_signal_connect(m_webView, "notify::estimated-load-progress",
                             G_CALLBACK(onLoadProgressChanged), m_observer.get()));
    m_signalHandlers.push_back(
            g_signal_connect(m_webView, "notify::uri", G_CALLBACK(onUriChanged),
                             m_observer.get()));
    m_signalHandlers.push_back(
            g_signal_connect(m_webView, "notify::title", G_CALLBACK(onTitleChanged),
                             m_observer.get()));

    m_input_method_context = input_method_context_new(m_observer);

    webkit_web_view_set_input_method_context(m_webView, m_input_method_context);

    ALOGV("Created WebKitWebView %p", m_webView);

    m_initialized = true;
}

void Page::close()
{
    ALOGV("Page::close");
    if (!m_initialized) {
        return;
    }
    m_initialized = false;
    for (auto &handler : m_signalHandlers) {
        g_signal_handler_disconnect(m_webView, handler);
    }
    m_signalHandlers.clear();
    webkit_web_view_try_close(m_webView);
}

void Page::loadUrl(const char *url)
{
    ALOGV("Page::loadUrl %s %p", url, m_webView);
    webkit_web_view_load_uri(m_webView, url);
}

void Page::goBack()
{
    webkit_web_view_go_back(m_webView);
}

void Page::goForward()
{
    webkit_web_view_go_forward(m_webView);
}

void Page::stopLoading()
{
    webkit_web_view_stop_loading(m_webView);
}

void Page::reload()
{
    webkit_web_view_reload(m_webView);
}

void Page::surfaceCreated(ANativeWindow* window)
{
    ALOGV("Page::surfaceCreated() window %p", window);
    m_renderer->surfaceCreated(window);
}

void Page::surfaceChanged(int format, int width, int height)
{
    uint32_t uwidth = uint32_t(std::max(0, width));
    uint32_t uheight = uint32_t(std::max(0, height));
    ALOGV("Page::surfaceChanged() format %d size (%u,%u)", format, uwidth, uheight);
    struct wpe_view_backend *viewBackend = wpe_android_view_backend_exportable_get_view_backend(
            m_viewBackendExportable);
    wpe_view_backend_dispatch_set_size(viewBackend, uwidth, uheight);
    m_renderer->surfaceChanged(format, uwidth, uheight);
}

void Page::surfaceRedrawNeeded()
{
    m_renderer->surfaceRedrawNeeded();
}

void Page::surfaceDestroyed()
{
    m_renderer->surfaceDestroyed();
}

void Page::handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer)
{
    ALOGV("Page::renderFrame() %p exportedBuffer %p", this, exportedBuffer.get());
    m_renderer->handleExportedBuffer(exportedBuffer);
}

void Page::onTouch(wpe_input_touch_event_raw *touchEventRaw)
{
    struct wpe_input_touch_event touchEvent {};
    touchEvent.touchpoints = touchEventRaw;
    touchEvent.touchpoints_length = 1;
    touchEvent.type = touchEventRaw->type;
    touchEvent.id = 0;
    touchEvent.time = touchEventRaw->time;

    struct wpe_view_backend *viewBackend = wpe_android_view_backend_exportable_get_view_backend(
            m_viewBackendExportable);
    wpe_view_backend_dispatch_touch_event(viewBackend, &touchEvent);
}

void Page::setZoomLevel(double zoomLevel)
{
    webkit_web_view_set_zoom_level(m_webView, zoomLevel);
}

void Page::setInputMethodContent(const char c)
{
    input_method_context_set_content(m_input_method_context, c);
}

void Page::deleteInputMethodContent(int offset)
{
    input_method_context_delete_content(m_input_method_context, offset);
}

struct wpe_android_view_backend_exportable_client Page::s_exportableClient = {
    // export_buffer
    [](void* data, AHardwareBuffer* buffer, uint32_t poolID, uint32_t bufferID)
    {
        ALOGV("s_exportableClient::export_buffer() buffer %p poolID %u bufferID %u", buffer, poolID, bufferID);
        auto *page = reinterpret_cast<Page *>(data);

        Browser::getInstance().handleExportedBuffer(*page, std::make_shared<ExportedBuffer>(buffer, poolID, bufferID));
    },
};

static void onLoadChanged(WebKitWebView *, WebKitLoadEvent loadEvent, gpointer data)
{
    auto *observer = reinterpret_cast<PageEventObserver *>(data);
    if (observer != nullptr) {
        observer->onLoadChanged(loadEvent);
    }
}

static void onLoadProgressChanged(WebKitWebView *webView, GParamSpec *, gpointer data)
{
    auto *observer = reinterpret_cast<PageEventObserver *>(data);
    if (observer != nullptr) {
        gdouble progress = webkit_web_view_get_estimated_load_progress(webView);
        observer->onLoadProgress(progress);
    }
}

static void onUriChanged(WebKitWebView *webView, GParamSpec *, gpointer data)
{
    auto *observer = reinterpret_cast<PageEventObserver *>(data);
    if (observer != nullptr) {
        const char *uri = webkit_web_view_get_uri(webView);
        observer->onUriChanged(uri);
    }
}

static void onTitleChanged(WebKitWebView *webView, GParamSpec *, gpointer data)
{
    auto *observer = reinterpret_cast<PageEventObserver *>(data);
    if (observer != nullptr) {
        const char *title = webkit_web_view_get_title(webView);
        gboolean canGoBack = webkit_web_view_can_go_back(webView);
        gboolean canGoForward = webkit_web_view_can_go_forward(webView);
        observer->onTitleChanged(title, canGoBack, canGoForward);
    }
}
