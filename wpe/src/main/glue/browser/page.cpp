#include <algorithm>
#include "page.h"

#include "logging.h"

static void onLoadChanged(WebKitWebView *, WebKitLoadEvent, gpointer);

static void onLoadProgressChanged(WebKitWebView *, GParamSpec *, gpointer);

static void onUriChanged(WebKitWebView *, GParamSpec *, gpointer);

static void onTitleChanged(WebKitWebView *, GParamSpec *, gpointer);

Page::Page(int width, int height, std::unique_ptr<PageEventObserver> observer)
        : m_width(width),
          m_height(height),
          m_observer(std::move(observer)),
          m_webView(nullptr),
          m_viewBackendExportable(nullptr),
          m_initialized(false)
{
    m_signalHandlers.clear();
}

void Page::init()
{
    if (m_initialized) {
        return;
    }

    ALOGV("Initializing Page");
    struct wpe_view_backend *wpeBackend;
    {
        m_viewBackendExportable = wpe_android_view_backend_exportable_create(std::max(0, m_width),
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

void Page::frameComplete()
{
    wpe_android_view_backend_exportable_dispatch_frame_complete(m_viewBackendExportable);
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
        observer->onTitleChanged(title);
    }
}
