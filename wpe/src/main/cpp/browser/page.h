#pragma once

#include "inputmethodcontext.h"
#include "pageeventobserver.h"
#include "renderer.h"

#include <wpe/webkit.h>
#include <wpe/wpe.h>
#include <memory>
#include <string>
#include <vector>

#include <wpe-android/view-backend-exportable.h>

struct ANativeWindow;
struct ExportedBuffer;

class Page
{
public:
    Page(int width, int height, const std::string& userAgent, std::shared_ptr<PageEventObserver> observer);

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

    struct wpe_android_view_backend_exportable* exportable()
    { return m_viewBackendExportable; }

private:
    static struct wpe_android_view_backend_exportable_client s_exportableClient;

    int m_width = 0;
    int m_height = 0;
    std::string m_userAgent;
    bool m_initialized = false;
    WebKitWebView* m_webView = nullptr;
    struct wpe_android_view_backend_exportable* m_viewBackendExportable = nullptr;
    std::unique_ptr<Renderer> m_renderer;
    std::shared_ptr<PageEventObserver> m_observer;
    std::vector<gulong> m_signalHandlers;
    WebKitInputMethodContext* m_input_method_context = nullptr;
};
