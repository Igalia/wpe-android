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

void PageEventObserver::onLoadChanged(WebKitLoadEvent loadEvent) {
    try {
        JNIEnv *env = ScopedEnv(vm).getEnv();
        jmethodID onLoadChanged = env->GetMethodID(pageClass, "onLoadChanged", "(I)V");
        if (onLoadChanged == nullptr) {
            throw;
        }
        env->CallVoidMethod(pageObj, onLoadChanged, (int)loadEvent);
    } catch(int) {
        ALOGE("Could not send onLoadChanged event");
    }
}

void PageEventObserver::onLoadProgress(double progress) {
    try {
        JNIEnv *env = ScopedEnv(vm).getEnv();
        jmethodID onLoadProgress = env->GetMethodID(pageClass, "onLoadProgress", "(D)V");
        if (onLoadProgress == nullptr) {
            throw;
        }
        env->CallVoidMethod(pageObj, onLoadProgress, progress);
    } catch(int) {
        ALOGE("Could not send onLoadProgress event");
    }
}
