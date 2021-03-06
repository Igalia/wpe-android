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

class Page {
public:
    Page(int width, int height, std::shared_ptr<PageEventObserver> observer);

    Page(Page&&) = delete;
    Page& operator=(Page&&) = delete;
    Page(const Page&) = delete;
    Page& operator=(const Page&) = delete;

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
