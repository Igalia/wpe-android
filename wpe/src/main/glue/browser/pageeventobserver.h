#pragma once

#include <wpe-webkit/wpe/webkit.h>

class PageEventObserver {
    JavaVM *vm;
    jclass pageClass;
    jobject pageObj;

public:
    PageEventObserver(JavaVM *vm, jclass klass, jobject obj) : vm(vm), pageClass(klass), pageObj(obj) {}
    ~PageEventObserver();

    void onLoadChanged(WebKitLoadEvent);
    void onLoadProgress(double);
    void onUriChanged(const char*);
    void onTitleChanged(const char*);
};
