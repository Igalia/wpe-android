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

#pragma once

#include "InputMethodContext.h"
#include "PageEventObserver.h"
#include "PageSettings.h"
#include "Renderer.h"

#include <memory>
#include <string>
#include <vector>
#include <wpe/webkit.h>
#include <wpe/wpe.h>

#include <wpe-android/view-backend-exportable.h>

struct ANativeWindow;
struct ExportedBuffer;

class Page final {
public:
    Page(int width, int height, std::shared_ptr<PageEventObserver> observer);

    Page(Page&&) = delete;
    Page& operator=(Page&&) = delete;
    Page(const Page&) = delete;
    Page& operator=(const Page&) = delete;

    ~Page();

    void init();
    void close();

    void loadUrl(const char* url);
    void goBack();
    void goForward();
    void stopLoading();
    void reload();

    void surfaceCreated(ANativeWindow* window);
    void surfaceChanged(int format, int width, int height);
    void surfaceRedrawNeeded();
    void surfaceDestroyed();
    void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer);

    void onTouch(wpe_input_touch_event_raw* touchEventRaw);
    void setZoomLevel(double zoomLevel);

    void setInputMethodContent(const char c);
    void deleteInputMethodContent(int offset);

    void domFullscreenRequest(bool fullscreen);
    void requestExitFullscreen();
    void fullscreenImageReady();

    struct wpe_android_view_backend_exportable* exportable() { return m_viewBackendExportable; }

    void updateAllSettings(const PageSettings& settings);

    static int registerJNINativeFunctions(JNIEnv* env);

private:
    static struct wpe_android_view_backend_exportable_client s_exportableClient;

    int m_width = 0;
    int m_height = 0;
    bool m_initialized = false;
    bool m_resizing_fullscreen = false;
    WebKitWebView* m_webView = nullptr;
    struct wpe_android_view_backend_exportable* m_viewBackendExportable = nullptr;

    std::unique_ptr<Renderer> m_renderer;
    std::shared_ptr<PageEventObserver> m_observer;
    std::vector<gulong> m_signalHandlers;
    WebKitInputMethodContext* m_input_method_context = nullptr;
};
