#pragma once

class PageEventObserver {
    JavaVM *vm;
    jclass pageClass;
    jobject pageObj;

public:
    PageEventObserver(JavaVM *vm, jclass klass, jobject obj) : vm(vm), pageClass(klass), pageObj(obj) {}
    ~PageEventObserver();

    void onProgress(double);
};
