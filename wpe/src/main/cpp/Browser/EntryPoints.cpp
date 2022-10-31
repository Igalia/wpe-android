/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "Browser.h"
#include "Environment.h"
#include "JNIHelper.h"
#include "Logging.h"
#include "LooperThread.h"
#include "Page.h"
#include "PageEventObserver.h"
#include "Service.h"

#include <wpe/wpe.h>

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*);
}

namespace {
jweak s_browserGlue_object = nullptr;

void setupEnvironment(JNIEnv*, jclass, jobjectArray envStringsArray)
{
    ALOGV("BrowserGlue::setupEnvironment() [tid %d]", gettid());
    Wpe::Android::pipeStdoutToLogcat();
    Wpe::Android::configureEnvironment(envStringsArray);
}

void init(JNIEnv* env, jclass, jobject glueObj, jstring jdataDir, jstring jcacheDir)
{
    ALOGV("BrowserGlue::init(%p) [tid %d]", glueObj, gettid());

    if (s_browserGlue_object == nullptr)
        s_browserGlue_object = env->NewWeakGlobalRef(glueObj);

    const char* dataDirChars = env->GetStringUTFChars(jdataDir, nullptr);
    const char* cacheDirChars = env->GetStringUTFChars(jcacheDir, nullptr);

    Browser::instance().init(dataDirChars, cacheDirChars);

    env->ReleaseStringUTFChars(jdataDir, dataDirChars);
    env->ReleaseStringUTFChars(jcacheDir, cacheDirChars);
}

void initLooperHelper(JNIEnv*, jclass)
{
    ALOGV("BrowserGlue::initLooperHelper() [tid %d]", gettid());

    LooperThread::initialize();
}

void shut(JNIEnv* env, jclass)
{
    ALOGV("BrowserGlue::shut() [tid %d]", gettid());

    Browser::instance().shut();

    if (s_browserGlue_object != nullptr) {
        env->DeleteWeakGlobalRef(s_browserGlue_object);
        s_browserGlue_object = nullptr;
    }
}

// Process management API

struct AndroidProcessProvider {
    struct wpe_process_provider* wpe_provider;
};

void* createProcessProvider(struct wpe_process_provider* process_provider)
{
    ALOGV("BrowserGlue createProcessProvider()");
    return new AndroidProcessProvider {process_provider};
}

void destroyProcessProvider(void* data)
{
    ALOGV("BrowserGlue destroyProcessProvider()");
    delete reinterpret_cast<AndroidProcessProvider*>(data);
}

int64_t launchProcess(void* data, enum wpe_process_type wpeProcessType, void* options)
{
    ALOGV("BrowserGlue launchProcess()");
    auto* provider = reinterpret_cast<AndroidProcessProvider*>(data);
    if (!provider)
        return -1;

    auto** argv = reinterpret_cast<char**>(options);
    if (!argv)
        return -1;

    uint64_t pid = std::strtoll(argv[0], nullptr, 10);
    int32_t fd = std::stoi(argv[1]);

    static auto processTypeToAndroidProcessType = [](enum wpe_process_type wpeProcessType) {
        switch (wpeProcessType) {
        case WPE_PROCESS_TYPE_WEB:
            return Wpe::Android::ProcessType::WebProcess;

        case WPE_PROCESS_TYPE_NETWORK:
            return Wpe::Android::ProcessType::NetworkProcess;

        case WPE_PROCESS_TYPE_GPU:
        case WPE_PROCESS_TYPE_WEB_AUTHN:
        default:
            return Wpe::Android::ProcessType::TypesCount;
        }
    };

    auto processType = processTypeToAndroidProcessType(wpeProcessType);
    if (processType < Wpe::Android::ProcessType::FirstType || processType >= Wpe::Android::ProcessType::TypesCount) {
        ALOGE("Cannot launch process (invalid process type: %d)", static_cast<int>(processType));
        return -1;
    }

    ALOGV("BrowserGlue launchProcess - pid: %lu, processType: %d, fd: %d)", static_cast<unsigned long>(pid),
        static_cast<int>(processType), fd);

    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        if (!env->IsSameObject(s_browserGlue_object, nullptr)) {
            jobject obj = env->NewLocalRef(s_browserGlue_object);
            jclass klass = env->GetObjectClass(obj);

            jmethodID methodID = env->GetMethodID(klass, "launchProcess", "(JII)V");
            if (methodID != nullptr) {
                env->CallVoidMethod(obj, methodID, static_cast<jlong>(pid), static_cast<jint>(processType), fd);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    ALOGE("Cannot launch process (exception occurred on Java side)");
                }
            } else
                ALOGE("Cannot launch process (cannot find \"launchProcess\" method)");

            env->DeleteLocalRef(klass);
            env->DeleteLocalRef(obj);
        } else
            ALOGE("Cannot launch process (BrowserGlue has been garbage collected)");
    } catch (...) {
        ALOGE("Cannot launch process (JNI environment error)");
    }

    return 0;
}

void terminateProcess(void* data, int64_t pid)
{
    ALOGV("BrowserGlue terminateProcess()");
    auto* provider = reinterpret_cast<AndroidProcessProvider*>(data);
    if (!provider)
        return;

    uint64_t pid64 = static_cast<uint64_t>(pid);
    ALOGV("BrowserGlue terminateProcess - pid: %lu)", static_cast<unsigned long>(pid64));

    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
        if (!env->IsSameObject(s_browserGlue_object, nullptr)) {
            jobject obj = env->NewLocalRef(s_browserGlue_object);
            jclass klass = env->GetObjectClass(obj);

            jmethodID methodID = env->GetMethodID(klass, "terminateProcess", "(J)V");
            if (methodID != nullptr) {
                env->CallVoidMethod(obj, methodID, static_cast<jlong>(pid64));
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    ALOGE("Cannot terminate process (exception occurred on Java side)");
                }
            } else
                ALOGE("Cannot terminate process (cannot find \"terminateProcess\" method)");

            env->DeleteLocalRef(klass);
            env->DeleteLocalRef(obj);
        } else
            ALOGE("Cannot terminate process (BrowserGlue has been garbage collected)");
    } catch (...) {
        ALOGE("Cannot terminate process (JNI environment error)");
    }
}

struct wpe_process_provider_interface processProviderInterface = {
    .create = createProcessProvider,
    .destroy = destroyProcessProvider,
    .launch = launchProcess,
    .terminate = terminateProcess,
};
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = Wpe::Android::initVM(vm);
    jclass klass = env->FindClass("com/wpe/wpe/BrowserGlue");
    if (klass == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[]
        = {{"setupEnvironment", "([Ljava/lang/String;)V", reinterpret_cast<void*>(setupEnvironment)},
            {"init", "(Lcom/wpe/wpe/BrowserGlue;Ljava/lang/String;Ljava/lang/String;)V", reinterpret_cast<void*>(init)},
            {"initLooperHelper", "()V", reinterpret_cast<void*>(initLooperHelper)},
            {"shut", "()V", reinterpret_cast<void*>(shut)}};

    int result = env->RegisterNatives(klass, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(klass);

    if (result == JNI_OK) {
        result = Page::registerJNINativeFunctions(env);
    }

    wpe_process_provider_register_interface(&processProviderInterface);

    return (result != JNI_OK) ? result : Wpe::Android::JNI_VERSION;
}
