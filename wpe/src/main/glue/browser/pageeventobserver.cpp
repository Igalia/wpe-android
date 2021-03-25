#include "jnihelper.h"
#include "logging.h"
#include "pageeventobserver.h"

PageEventObserver::~PageEventObserver() {
    try {
        JNIEnv *env = ScopedEnv(vm).getEnv();
        env->DeleteGlobalRef(pageClass);
        env->DeleteGlobalRef(pageObj);
    } catch(int) {}
}

void PageEventObserver::onProgress(double progress) {
    try {
        JNIEnv *env = ScopedEnv(vm).getEnv();
        ALOGV("GETTING METHOD env %p pageClass %p", env, pageClass);
        jmethodID onLoadProgress = env->GetMethodID(pageClass, "onLoadProgress", "(D)V");
        if (onLoadProgress == nullptr) {
            return;
        }
        env->CallVoidMethod(pageObj, onLoadProgress, progress);
    } catch(int) {}
}
