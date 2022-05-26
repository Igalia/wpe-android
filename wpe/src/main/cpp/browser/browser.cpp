#include "browser.h"

#include "logging.h"

#include <algorithm>
#include <gio/gio.h>
#include <glib.h>
#include <glib-object.h>
#include <gobject/gvaluecollector.h>

#include <wpe-android/view-backend-exportable.h>

#include <android/hardware_buffer.h>
#include <android/surface_control.h>

#define PAGE_METHOD_PROXY(BROWSER_METHOD, PAGE_METHOD, PRIORITY) \
    void Browser::BROWSER_METHOD(int pageId) \
    { \
        if (m_pages.find(pageId) == m_pages.end()) \
            return; \
        g_main_context_invoke_full(*m_uiProcessThreadContext, PRIORITY, +[](gpointer data) -> gboolean { \
            auto* page = reinterpret_cast<Page*>(data); \
            if (page != nullptr) page->PAGE_METHOD(); \
            return G_SOURCE_REMOVE; \
        }, m_pages[pageId].get(), nullptr); \
    }

void Browser::init()
{
    ALOGV("Browser::init");

    if (!m_uiProcessThreadContext)
        m_uiProcessThreadContext = std::make_unique<GMainContext*>(g_main_context_new());

    runMainLoop();
}

void Browser::shut()
{
    ALOGV("Browser::shut");

    g_main_loop_quit(*m_uiProcessThreadLoop.get());
}

void Browser::invoke(void (* callback)(void*), void* callbackData, void (* destroy)(void*))
{
    struct GenericCallback
    {
        void (* callback)(void*);
        void* callbackData;
        void (* destroy)(void*);
    };

    auto* data = new GenericCallback { callback, callbackData, destroy };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* genericData = reinterpret_cast<GenericCallback*>(data);
        genericData->callback(genericData->callbackData);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        auto* genericData = reinterpret_cast<GenericCallback*>(data);
        if (genericData->destroy)
            genericData->destroy(genericData->callbackData);
        delete genericData;
    });
}

void Browser::runMainLoop()
{
    wpe::android::pipeStdoutToLogcat();
    wpe::android::enableGstDebug();

    ALOGV("ui_process_thread_entry -- entered, g_main_context_default() %p",
          g_main_context_default());

    m_uiProcessThreadLoop = std::make_unique<GMainLoop*>(g_main_loop_new(*m_uiProcessThreadContext.get(), FALSE));

    g_main_context_push_thread_default(*m_uiProcessThreadContext.get());

    webkit_web_context_new();

    ALOGV("ui_process_thread_entry -- running via GMainLoop %p for GMainContext %p",
          m_uiProcessThreadLoop.get(), m_uiProcessThreadContext.get());
    g_main_loop_run(*m_uiProcessThreadLoop.get());
    ALOGV("ui_process_thread_entry -- quitting");

    g_main_context_pop_thread_default(*m_uiProcessThreadContext.get());

    g_main_loop_unref(*m_uiProcessThreadLoop.get());
    m_uiProcessThreadLoop.reset(nullptr);
}

void Browser::newPage(int pageId, int width, int height, const std::string& userAgent,
                      std::shared_ptr<PageEventObserver> observer)
{
    ALOGV("Browser::newPage");
    auto page = std::make_unique<Page>(width, height, userAgent, observer);
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* page = reinterpret_cast<Page*>(data);
        if (page != nullptr)
            page->init();

        return G_SOURCE_REMOVE;
    }, page.get(), nullptr);

    m_pages.insert(std::make_pair(pageId, std::move(page)));
}

PAGE_METHOD_PROXY(closePage, close, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(goBack, goBack, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(goForward, goForward, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(stopLoading, stopLoading, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(reload, reload, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(surfaceRedrawNeeded, surfaceRedrawNeeded, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(surfaceDestroyed, surfaceDestroyed, G_PRIORITY_DEFAULT)

void Browser::surfaceCreated(int pageId, ANativeWindow* window)
{
    if (m_pages.find(pageId) == m_pages.end())
        return;

    struct SurfaceCreatedData
    {
        Page* page;
        ANativeWindow* window;
    };

    auto* data = new SurfaceCreatedData { m_pages[pageId].get(), window };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* surfaceCreatedData = reinterpret_cast<SurfaceCreatedData*>(data);
        if (surfaceCreatedData->page != nullptr)
            surfaceCreatedData->page->surfaceCreated(surfaceCreatedData->window);
        surfaceCreatedData->window = nullptr;
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        auto* surfaceCreatedData = reinterpret_cast<SurfaceCreatedData*>(data);
        if (surfaceCreatedData->window)
            ANativeWindow_release(surfaceCreatedData->window);
        delete surfaceCreatedData;
    });
}

void Browser::surfaceChanged(int pageId, int format, int width, int height)
{
    if (m_pages.find(pageId) == m_pages.end())
        return;

    struct SurfaceChangedData
    {
        Page* page;
        int format;
        int width;
        int height;
    };

    auto* data = new SurfaceChangedData { m_pages[pageId].get(), format, width, height };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* surfaceChangedData = reinterpret_cast<SurfaceChangedData*>(data);
        if (surfaceChangedData->page != nullptr)
            surfaceChangedData->page->surfaceChanged(surfaceChangedData->format, surfaceChangedData->width,
                                                     surfaceChangedData->height);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        delete reinterpret_cast<SurfaceChangedData*>(data);
    });
}

void Browser::handleExportedBuffer(Page& page, std::shared_ptr<ExportedBuffer>&& exportedBuffer)
{
    ALOGV("Browser::renderFrame() page %p, exportedBuffer %p", &page, exportedBuffer.get());

    struct FrameOperation
    {
        Page& page;
        std::shared_ptr<ExportedBuffer> buffer;
    };

    auto* data = new FrameOperation { page, std::move(exportedBuffer) };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* frameOperation = reinterpret_cast<FrameOperation*>(data);
        frameOperation->page.handleExportedBuffer(frameOperation->buffer);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        delete reinterpret_cast<FrameOperation*>(data);
    });
}

void Browser::loadUrl(int pageId, const char* urlData, jsize urlSize)
{
    if (m_pages.find(pageId) == m_pages.end())
        return;

    struct LoadUrlData
    {
        Page* page;
        char* url;
    };

    char* url = g_strndup(urlData, urlSize);
    auto* data = new LoadUrlData { m_pages[pageId].get(), url };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* loadUrlData = reinterpret_cast<LoadUrlData*>(data);
        if (loadUrlData->page != nullptr)
            loadUrlData->page->loadUrl(loadUrlData->url);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        auto* loadUrlData = reinterpret_cast<LoadUrlData*>(data);
        g_free(loadUrlData->url);
        delete loadUrlData;
    });
}

void Browser::onTouch(int pageId, jlong time, jint type, jfloat x, jfloat y)
{
    if (m_pages.find(pageId) == m_pages.end())
        return;

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

    struct wpe_input_touch_event_raw* touchEventRaw = g_new0(struct wpe_input_touch_event_raw, 1);
    touchEventRaw->type = touchEventType;
    touchEventRaw->time = (uint32_t)time;
    touchEventRaw->id = 0;
    touchEventRaw->x = (int32_t)x;
    touchEventRaw->y = (int32_t)y;

    struct OnTouchData
    {
        Page* page;
        wpe_input_touch_event_raw* touchEvent;
    };

    auto* data = new OnTouchData { m_pages[pageId].get(), touchEventRaw };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* touchData = reinterpret_cast<OnTouchData*>(data);
        if (touchData->page != nullptr)
            touchData->page->onTouch(touchData->touchEvent);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        auto* touchData = reinterpret_cast<OnTouchData*>(data);
        g_free(touchData->touchEvent);
        delete touchData;
    });
}

void Browser::setZoomLevel(int pageId, jdouble zoomLevel)
{
    struct ZoomLevelData
    {
        Page* page;
        double zoomLevel;
    };

    auto* data = new ZoomLevelData { m_pages[pageId].get(), zoomLevel };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* zoomLevelData = reinterpret_cast<ZoomLevelData*>(data);
        if (zoomLevelData->page != nullptr)
            zoomLevelData->page->setZoomLevel(zoomLevelData->zoomLevel);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) -> void {
        delete reinterpret_cast<ZoomLevelData*>(data);
    });
}

struct InputMethodContentData
{
    Page* page;
    const char c;
    int offset;
};

void Browser::setInputMethodContent(int pageId, const char c)
{
    auto* data = new InputMethodContentData { m_pages[pageId].get(), c, 0 };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* data_ = static_cast<InputMethodContentData*>(data);
        if (data_->page != nullptr)
            data_->page->setInputMethodContent(data_->c);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) {
        delete static_cast<InputMethodContentData*>(data);
    });
}

void Browser::deleteInputMethodContent(int pageId, int offset)
{
    auto* data = new InputMethodContentData { m_pages[pageId].get(), 0, offset };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, +[](gpointer data) -> gboolean {
        auto* data_ = static_cast<InputMethodContentData*>(data);
        if (data_->page != nullptr)
            data_->page->deleteInputMethodContent(data_->offset);
        return G_SOURCE_REMOVE;
    }, data, +[](gpointer data) {
        delete static_cast<InputMethodContentData*>(data);
    });
}
