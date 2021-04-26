#pragma once

#include <wpe/webkit.h>
#include <wpe/wpe.h>
#include <memory>
#include <vector>

#include "inputmethodcontext.h"
#include "pageeventobserver.h"

#include <wpe-android/view-backend-exportable.h>

class Page {
public:
    Page(int width, int height, std::shared_ptr<PageEventObserver>);
    void init();
    void close();

    void loadUrl(const char* url);
    void goBack();
    void goForward();
    void stopLoading();
    void reload();

    void frameComplete();
    void onTouch(wpe_input_touch_event_raw*);
    void setZoomLevel(double zoomLevel);

    void setInputMethodContent(const char c);
    void deleteInputMethodContent(int offset);

private:
    int m_width = 0;
    int m_height = 0;
    bool m_initialized = false;
    WebKitWebView* m_webView;
    wpe_android_view_backend_exportable* m_viewBackendExportable;
    std::shared_ptr<PageEventObserver> m_observer;
    std::vector<gulong> m_signalHandlers;
    WebKitInputMethodContext* m_input_method_context;
};