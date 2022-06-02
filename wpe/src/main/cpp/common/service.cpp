#include "service.h"

#include "logging.h"
#include "jnihelper.h"
#include "environment.h"

#include <dlfcn.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <cassert>

namespace {
void initializeMain(JNIEnv*, jclass, wpe::android::ProcessType processType, jint fd)
{
    // As this function can exclusively be called from JNI, we just
    // need to assert having the right value for the process type in
    // case that one day the Java enum is modified but the native enum
    // has not been well synchronized.
    assert(processType >= wpe::android::ProcessType::FirstType);
    assert(processType < wpe::android::ProcessType::TypesCount);

    wpe::android::pipeStdoutToLogcat();

    static const char* const processName[static_cast<int>(wpe::android::ProcessType::TypesCount)] = {
            "WPEWebProcess",
            "WPENetworkProcess"
    };

    static const char* const entrypointName[static_cast<int>(wpe::android::ProcessType::TypesCount)] = {
            "android_WebProcess_main",
            "android_NetworkProcess_main"
    };

    using ProcessEntryPoint = int(int, char**);
    auto* entrypoint = reinterpret_cast<ProcessEntryPoint*>(dlsym(RTLD_DEFAULT,
                                                                  entrypointName[static_cast<int>(processType)]));
    ALOGV("Glue::initializeMain() for %s, fd: %d, entrypoint: %p", processName[static_cast<int>(processType)], fd,
          entrypoint);

    char pidString[32];
    snprintf(pidString, sizeof(pidString), "%d", getpid());
    char fdString[32];
    snprintf(fdString, sizeof(fdString), "%d", fd);

    char* argv[3];
    argv[0] = const_cast<char*>(processName[static_cast<int>(processType)]);
    argv[1] = pidString;
    argv[2] = fdString;
    (*entrypoint)(3, argv);
}

void setupEnvironment(JNIEnv*, jclass, jobjectArray envStringsArray)
{
    ALOGV("Glue::setupEnvironment()");
    wpe::android::configureEnvironment(envStringsArray);
}
} // namespace

jint wpe::android::registerServiceEntryPoints(JavaVM* vm, const char* serviceGlueClass)
{
    JNIEnv* env = wpe::android::initVM(vm);
    jclass klass = env->FindClass(serviceGlueClass);
    if (klass == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[] = {
            { "initializeMain",   "(II)V",                  reinterpret_cast<void*>(initializeMain) },
            { "setupEnvironment", "([Ljava/lang/String;)V", reinterpret_cast<void*>(setupEnvironment) }
    };
    int result = env->RegisterNatives(klass, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(klass);

    return (result != JNI_OK) ? result : wpe::android::JNI_VERSION;
}
