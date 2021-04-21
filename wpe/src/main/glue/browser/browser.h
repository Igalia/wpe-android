#pragma once

#include <functional>
#include <unordered_map>

#include <wpe/webkit.h>
#include <wpe/wpe.h>

#include "page.h"
#include "pageeventobserver.h"

class Browser {
public:
    static Browser &getInstance()
    {
        if (m_instance == nullptr) {
            m_instance.reset(new Browser());
        }
        return *m_instance;
    }

    ~Browser() {
        deinit();
    }

    void init();
    void deinit();

    void newPage(int pageId, int width, int height, std::shared_ptr<PageEventObserver> observer);
    void closePage(int pageId);

    void loadUrl(int pageId, const char *urlData, jsize urlSize);
    void goBack(int pageId);
    void goForward(int pageId);
    void stopLoading(int pageId);
    void reload(int pageId);

    void frameComplete(int pageId);
    void onTouch(int pageId, jlong time, jint type, jfloat x, jfloat y);
    void setZoomLevel(int pageId, jdouble zoomLevel);

private:
    Browser() {}

    static std::unique_ptr<Browser> m_instance;

    std::unique_ptr<GMainContext*> m_uiProcessThreadContext;
    std::unique_ptr<GMainLoop*> m_uiProcessThreadLoop;

    std::unordered_map<int, std::unique_ptr<Page>> m_pages;

    void runMainLoop();
};
