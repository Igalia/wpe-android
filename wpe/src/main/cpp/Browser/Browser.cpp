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

void Browser::handleExportedBuffer(Page& page, std::shared_ptr<ExportedBuffer>&& exportedBuffer)
{
    ALOGV("Browser::renderFrame() page %p, exportedBuffer %p tid: %d", &page, exportedBuffer.get(), gettid());

    page.handleExportedBuffer(std::move(exportedBuffer));
}
