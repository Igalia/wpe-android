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

void Browser::init(std::string dataDir, std::string cacheDir)
{
    ALOGV("Browser::init - tid: %d", gettid());

    m_messagePump = std::make_unique<MessagePump>();
    m_websiteDataManager = webkit_website_data_manager_new(
        "base-data-directory", dataDir.c_str(), "base-cache-directory", cacheDir.c_str(), NULL);
    m_webContext = webkit_web_context_new_with_website_data_manager(m_websiteDataManager);
}

void Browser::shut()
{
    ALOGV("Browser::shut - tid: %d", gettid());
    m_messagePump->quit();

    g_clear_object(&m_webContext);
    g_clear_object(&m_websiteDataManager);
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
