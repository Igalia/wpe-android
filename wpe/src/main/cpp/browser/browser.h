#pragma once

#include "exportedbuffer.h"
#include "messagepump.h"
#include "page.h"
#include "pageeventobserver.h"
#include "pagesettings.h"

#include <functional>
#include <string>
#include <unordered_map>

#include <wpe/webkit.h>
#include <wpe/wpe.h>

struct ANativeWindow;

class Browser final
{
public:
    static Browser& getInstance()
    {
        static std::unique_ptr<Browser> singleton(new Browser());
        return *singleton;
    }

    Browser(Browser&&) = delete;
    Browser& operator=(Browser&&) = delete;
    Browser(const Browser&) = delete;
    Browser& operator=(const Browser&) = delete;

    ~Browser()
    {
        shut();
    }

    void init();
    void shut();

    void invokeOnUiThread(void (* callback)(void*), void* callbackData, void (* destroy)(void*));

    void newPage(int pageId, int width, int height, std::shared_ptr<PageEventObserver> observer);
    void closePage(int pageId);

    void loadUrl(int pageId, const char* urlData, jsize urlSize);
    void goBack(int pageId);
    void goForward(int pageId);
    void stopLoading(int pageId);
    void reload(int pageId);

    void surfaceCreated(int pageId, ANativeWindow* window);
    void surfaceChanged(int pageId, int format, int width, int height);
    void surfaceRedrawNeeded(int pageId);
    void surfaceDestroyed(int pageId);
    void handleExportedBuffer(Page& page, std::shared_ptr<ExportedBuffer>&& exportedBuffer);

    void onTouch(int pageId, jlong time, jint type, jfloat x, jfloat y);
    void setZoomLevel(int pageId, jdouble zoomLevel);

    void setInputMethodContent(int pageId, const char c);
    void deleteInputMethodContent(int pageId, int offset);

    void requestExitFullscreen(int pageId);

    void updateAllPageSettings(int pageId, const PageSettings& settings);

private:
    Browser() = default;

    std::unique_ptr<MessagePump> m_messagePump;

    std::unordered_map<int, std::unique_ptr<Page>> m_pages;
};
