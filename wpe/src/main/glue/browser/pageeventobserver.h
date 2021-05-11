#pragma once

#include <jni.h>
#include <wpe-webkit/wpe/webkit.h>

class PageEventObserver {
    JavaVM *vm;
    jclass pageClass;
    jobject pageObj;

public:
    PageEventObserver(JavaVM *vm, jclass klass, jobject obj) : vm(vm), pageClass(klass), pageObj(obj) {}
    PageEventObserver(PageEventObserver&&) = delete;
    PageEventObserver& operator=(PageEventObserver&&) = delete;
    PageEventObserver(const PageEventObserver&) = delete;
    PageEventObserver& operator=(const PageEventObserver&) = delete;
    ~PageEventObserver();

    void onLoadChanged(WebKitLoadEvent);
    void onLoadProgress(double);
    void onUriChanged(const char*);
    void onTitleChanged(const char*, gboolean canGoBack, gboolean canGoForward);

    void onInputMethodContextIn();
    void onInputMethodContextOut();
};
