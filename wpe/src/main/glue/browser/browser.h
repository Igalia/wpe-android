#pragma once

#include <functional>
#include <string>
#include <unordered_map>

#include <wpe/webkit.h>
#include <wpe/wpe.h>

#include "exportedbuffer.h"
#include "page.h"
#include "pageeventobserver.h"

typedef struct AHardwareBuffer AHardwareBuffer;
struct ANativeWindow;

class Browser {
public:
    static Browser &getInstance()
    {
        if (m_instance == nullptr) {
            m_instance.reset(new Browser());
        }
        return *m_instance;
    }

    Browser(Browser&&) = delete;
    Browser& operator=(Browser&&) = delete;
    Browser(const Browser&) = delete;
    Browser& operator=(const Browser&) = delete;

    ~Browser() {
        deinit();
    }

    void init();
    void deinit();

    void invoke(void (*callback)(void*), void* callbackData, void (*destroy)(void*));

    void newPage(int pageId, int width, int height, const std::string& userAgent, std::shared_ptr<PageEventObserver> observer);
    void closePage(int pageId);

    void loadUrl(int pageId, const char *urlData, jsize urlSize);
    void goBack(int pageId);
    void goForward(int pageId);
    void stopLoading(int pageId);
    void reload(int pageId);

    void surfaceCreated(int pageId, ANativeWindow*);
    void surfaceChanged(int pageId, int format, int width, int height);
    void surfaceRedrawNeeded(int pageId);
    void surfaceDestroyed(int pageId);
    void handleExportedBuffer(Page&, std::shared_ptr<ExportedBuffer>&&);

    void onTouch(int pageId, jlong time, jint type, jfloat x, jfloat y);
    void setZoomLevel(int pageId, jdouble zoomLevel);

    void setInputMethodContent(int pageId, const char c);
    void deleteInputMethodContent(int pageId, int offset);

private:
    Browser() {}

    static std::unique_ptr<Browser> m_instance;

    std::unique_ptr<GMainContext*> m_uiProcessThreadContext;
    std::unique_ptr<GMainLoop*> m_uiProcessThreadLoop;

    std::unordered_map<int, std::unique_ptr<Page>> m_pages;

    void runMainLoop();
};
