#include <algorithm>

#include <gio/gio.h>
#include <glib.h>
#include <glib-object.h>
#include <gobject/gvaluecollector.h>

#include <wpe-android/view-backend-exportable.h>

#include <jni.h>

#include "browser.h"
#include "logging.h"

#define PAGE_METHOD_PROXY(BROWSER_METHOD, PAGE_METHOD, PRIORITY) \
    void Browser::BROWSER_METHOD(int pageId) \
    { \
        if (m_pages.find(pageId) == m_pages.end()) { \
            return; \
        } \
        g_main_context_invoke_full(*m_uiProcessThreadContext, PRIORITY, [](gpointer data) -> gboolean { \
            auto *page = reinterpret_cast<Page*>(data); \
            if (page != nullptr) page->PAGE_METHOD(); \
            return G_SOURCE_REMOVE; \
        }, m_pages[pageId].get(), NULL); \
    }

void Browser::init()
{
    ALOGV("Browser::init");

    if (!m_uiProcessThreadContext) {
        m_uiProcessThreadContext = std::make_unique<GMainContext*>(g_main_context_new());
    };

    runMainLoop();
}

void Browser::deinit()
{
    ALOGV("Browser::deinit");

    g_main_loop_quit(*m_uiProcessThreadLoop.get());
}

void Browser::runMainLoop() {
    pipe_stdout_to_logcat();
    enable_gst_debug();

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
    m_uiProcessThreadLoop.reset(NULL);
}

void Browser::newPage(int pageId, int width, int height, std::shared_ptr<PageEventObserver> observer)
{
    ALOGV("Browser::newPage");
    auto page = std::make_unique<Page>(width, height, observer);
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
        auto *page = reinterpret_cast<Page*>(data);
        if (page != nullptr) {
            page->init();
        }
        return G_SOURCE_REMOVE;
    }, page.get(), NULL);

    m_pages.insert(std::make_pair(pageId, std::move(page)));
}

PAGE_METHOD_PROXY(closePage, close, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(goBack, goBack, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(goForward, goForward, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(stopLoading, stopLoading, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(reload, reload, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(frameComplete, frameComplete, G_PRIORITY_HIGH + 30)

typedef struct {
    Page* page;
    char *url;
} LoadUrlData;

void Browser::loadUrl(int pageId, const char *urlData, jsize urlSize)
{
    if (m_pages.find(pageId) == m_pages.end()) {
        return;
    }

    char *url = g_strndup(urlData, urlSize);
    auto *data = new LoadUrlData { m_pages[pageId].get(), url };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
        auto *loadUrlData = reinterpret_cast<LoadUrlData *>(data);
        if (loadUrlData->page != nullptr) {
            loadUrlData->page->loadUrl(loadUrlData->url);
        }
        return G_SOURCE_REMOVE;
    }, data, [](gpointer data) -> void {
        auto *loadUrlData = reinterpret_cast<LoadUrlData*>(data);
        g_free(loadUrlData->url);
        delete loadUrlData;
    });
}

typedef struct {
    Page* page;
    wpe_input_touch_event_raw *touchEvent;
} OnTouchData;

void Browser::onTouch(int pageId, jlong time, jint type, jfloat x, jfloat y)
{
    if (m_pages.find(pageId) == m_pages.end()) {
        return;
    }

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

    struct wpe_input_touch_event_raw *touchEventRaw = g_new0(struct wpe_input_touch_event_raw, 1);
    touchEventRaw->type = touchEventType;
    touchEventRaw->time = (uint32_t) time;
    touchEventRaw->id = 0;
    touchEventRaw->x = (int32_t) x;
    touchEventRaw->y = (int32_t) y;

    auto *data = new OnTouchData { m_pages[pageId].get(), touchEventRaw };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
        auto *touchData = reinterpret_cast<OnTouchData*>(data);
        if (touchData->page != nullptr) {
            touchData->page->onTouch(touchData->touchEvent);
        }
        return G_SOURCE_REMOVE;
    }, data, [](gpointer data) -> void {
        auto *touchData = reinterpret_cast<OnTouchData*>(data);
        g_free(touchData->touchEvent);
        delete touchData;
    });
}

typedef struct {
    Page* page;
    double zoomLevel;
} ZoomLevelData;

void Browser::setZoomLevel(int pageId, jdouble zoomLevel)
{
    auto *data = new ZoomLevelData { m_pages[pageId].get(), zoomLevel };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
        auto *zoomLevelData = reinterpret_cast<ZoomLevelData*>(data);
        if (zoomLevelData->page != nullptr) {
            zoomLevelData->page->setZoomLevel(zoomLevelData->zoomLevel);
        }
        return G_SOURCE_REMOVE;
    }, data, [](gpointer data) -> void {
        delete reinterpret_cast<ZoomLevelData*>(data);
    });
}

typedef struct {
    Page* page;
    const char c;
    int offset;
} InputMethodContentData;

void Browser::setInputMethodContent(int pageId, const char c) {
    auto *data = new InputMethodContentData { m_pages[pageId].get(), c, 0 };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
        auto *data_ = static_cast<InputMethodContentData*>(data);
        if (data_->page != nullptr) {
            data_->page->setInputMethodContent(data_->c);
        }
        return G_SOURCE_REMOVE;
    }, data, [](gpointer data) {
        delete static_cast<InputMethodContentData*>(data);
    });
}

void Browser::deleteInputMethodContent(int pageId, int offset) {
    auto *data = new InputMethodContentData { m_pages[pageId].get(), 0, offset };
    g_main_context_invoke_full(*m_uiProcessThreadContext, G_PRIORITY_DEFAULT, [](gpointer data) -> gboolean {
        auto *data_ = static_cast<InputMethodContentData*>(data);
        if (data_->page != nullptr) {
            data_->page->deleteInputMethodContent(data_->offset);
        }
        return G_SOURCE_REMOVE;
    }, data, [](gpointer data) {
        delete static_cast<InputMethodContentData*>(data);
    });
}
