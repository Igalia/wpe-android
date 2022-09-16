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
