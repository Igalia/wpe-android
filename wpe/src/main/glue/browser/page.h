#pragma once

#include <wpe/webkit.h>
#include <wpe/wpe.h>
#include <memory>
#include <vector>

#include "pageeventobserver.h"
#include <wpe-android/view-backend-exportable.h>

class Page {
public:
    Page(int width, int height, std::unique_ptr<PageEventObserver>);
    void init();
    void close();

    void loadUrl(const char* url);
    void goBack();
    void goForward();
    void reload();

    void frameComplete();
    void onTouch(wpe_input_touch_event_raw*);

private:
    int m_width = 0;
    int m_height = 0;
    bool m_initialized = false;
    WebKitWebView* m_webView;
    wpe_android_view_backend_exportable* m_viewBackendExportable;
    std::shared_ptr<PageEventObserver> m_observer;
    std::vector<gulong> m_signalHandlers;
};