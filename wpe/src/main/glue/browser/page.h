#pragma once

#include <wpe/webkit.h>
#include <wpe/wpe.h>
#include <memory>
#include <vector>

#include "inputmethodcontext.h"
#include "pageeventobserver.h"
#include "renderer.h"

#include <wpe-android/view-backend-exportable.h>

typedef struct AHardwareBuffer AHardwareBuffer;
struct ANativeWindow;
struct ASurfaceControl;

struct ExportedBuffer;

class Page {
public:
    Page(int width, int height, std::shared_ptr<PageEventObserver>);
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

    void surfaceCreated(ANativeWindow*);
    void surfaceChanged(int format, int width, int height);
    void surfaceRedrawNeeded();
    void surfaceDestroyed();
    void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>&);

    void onTouch(wpe_input_touch_event_raw*);
    void setZoomLevel(double zoomLevel);

    void setInputMethodContent(const char c);
    void deleteInputMethodContent(int offset);

    struct wpe_android_view_backend_exportable* exportable() { return m_viewBackendExportable; }

private:
    static struct wpe_android_view_backend_exportable_client s_exportableClient;

    int m_width = 0;
    int m_height = 0;
    bool m_initialized = false;
    WebKitWebView* m_webView;
    struct wpe_android_view_backend_exportable* m_viewBackendExportable;
    std::unique_ptr<Renderer> m_renderer;
    std::shared_ptr<PageEventObserver> m_observer;
    std::vector<gulong> m_signalHandlers;
    WebKitInputMethodContext* m_input_method_context;

};
