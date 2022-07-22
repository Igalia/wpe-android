#pragma once

#include <jni.h>
#include <wpe-webkit/wpe/webkit.h>

class PageEventObserver final {
public:
    PageEventObserver(JNIEnv* env, jclass klass, jobject obj);
    ~PageEventObserver();

    PageEventObserver(PageEventObserver&&) = delete;
    PageEventObserver& operator=(PageEventObserver&&) = delete;
    PageEventObserver(const PageEventObserver&) = delete;
    PageEventObserver& operator=(const PageEventObserver&) = delete;

    static constexpr int NB_JAVA_METHODS = 8;

    void onLoadChanged(WebKitLoadEvent loadEvent);
    void onLoadProgress(double progress);
    void onUriChanged(const char* uri);
    void onTitleChanged(const char* title, gboolean canGoBack, gboolean canGoForward);

    void onInputMethodContextIn();
    void onInputMethodContextOut();

    void enterFullscreenMode();
    void exitFullscreenMode();

private:
    template <typename... Args> void callJavaVoidMethod(int methodIdx, Args&&... args);

    jclass m_pageClass = nullptr;
    jobject m_pageObj = nullptr;
    jmethodID m_javaMethodIDs[NB_JAVA_METHODS] = {0};
};
