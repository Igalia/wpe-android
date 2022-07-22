#include "Browser.h"

#include "Logging.h"
#include "MessagePump.h"

#include <algorithm>
#include <gio/gio.h>
#include <glib-object.h>
#include <glib.h>
#include <gobject/gvaluecollector.h>

#include <wpe-android/view-backend-exportable.h>

#include <android/hardware_buffer.h>
#include <android/surface_control.h>

#define PAGE_METHOD_PROXY(BROWSER_METHOD, PAGE_METHOD, PRIORITY)                                                       \
    void Browser::BROWSER_METHOD(int pageId)                                                                           \
    {                                                                                                                  \
        auto it = m_pages.find(pageId);                                                                                \
        if (it == m_pages.end())                                                                                       \
            return;                                                                                                    \
                                                                                                                       \
        auto* page = it->second.get();                                                                                 \
        if (page != nullptr)                                                                                           \
            page->PAGE_METHOD();                                                                                       \
    }

void Browser::init()
{
    ALOGV("Browser::init - tid: %d", gettid());

    m_messagePump = std::make_unique<MessagePump>();
    webkit_web_context_new();
}

void Browser::shut()
{
    ALOGV("Browser::shut - tid: %d", gettid());
    m_messagePump->quit();
}

void Browser::invokeOnUiThread(void (*callback)(void*), void* callbackData, void (*destroy)(void*))
{
    m_messagePump->invoke(callback, callbackData, destroy);
}

void Browser::newPage(int pageId, int width, int height, std::shared_ptr<PageEventObserver> observer)
{
    ALOGV("Browser::newPage - tid: %d", gettid());
    auto page = std::make_unique<Page>(width, height, observer);
    page->init();
    m_pages.insert(std::make_pair(pageId, std::move(page)));
}

PAGE_METHOD_PROXY(closePage, close, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(goBack, goBack, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(goForward, goForward, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(stopLoading, stopLoading, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(reload, reload, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(surfaceRedrawNeeded, surfaceRedrawNeeded, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(surfaceDestroyed, surfaceDestroyed, G_PRIORITY_DEFAULT)
PAGE_METHOD_PROXY(requestExitFullscreen, requestExitFullscreen, G_PRIORITY_DEFAULT)

void Browser::surfaceCreated(int pageId, ANativeWindow* window)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    page->surfaceCreated(window);
}

void Browser::surfaceChanged(int pageId, int format, int width, int height)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    page->surfaceChanged(format, width, height);
}

void Browser::handleExportedBuffer(Page& page, std::shared_ptr<ExportedBuffer>&& exportedBuffer)
{
    ALOGV("Browser::renderFrame() page %p, exportedBuffer %p tid: %d", &page, exportedBuffer.get(), gettid());

    page.handleExportedBuffer(std::move(exportedBuffer));
}

void Browser::loadUrl(int pageId, const char* urlData, jsize urlSize)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    char* url = g_strndup(urlData, urlSize);
    page->loadUrl(url);
    g_free(url);
}

void Browser::onTouch(int pageId, jlong time, jint type, jfloat x, jfloat y)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
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

    page->onTouch(&touchEventRaw);
}

void Browser::setZoomLevel(int pageId, jdouble zoomLevel)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    page->setZoomLevel(zoomLevel);
}

void Browser::setInputMethodContent(int pageId, const char c)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    page->setInputMethodContent(c);
}

void Browser::deleteInputMethodContent(int pageId, int offset)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    page->deleteInputMethodContent(offset);
}

void Browser::updateAllPageSettings(int pageId, const PageSettings& settings)
{
    auto it = m_pages.find(pageId);
    if (it == m_pages.end())
        return;

    auto* page = it->second.get();
    page->updateAllSettings(settings);
}
