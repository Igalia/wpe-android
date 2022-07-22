#include "Service.h"

#include "Environment.h"
#include "JNIHelper.h"
#include "Logging.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <string>
#include <unistd.h>

namespace {
void initializeMain(JNIEnv*, jclass, Wpe::Android::ProcessType processType, jint fd)
{
    // As this function can exclusively be called from JNI, we just
    // need to assert having the right value for the process type in
    // case that one day the Java enum is modified but the native enum
    // has not been well synchronized.
    assert(processType >= Wpe::Android::ProcessType::FirstType);
    assert(processType < Wpe::Android::ProcessType::TypesCount);

    Wpe::Android::pipeStdoutToLogcat();

    static const char* const processName[static_cast<int>(Wpe::Android::ProcessType::TypesCount)]
        = {"WPEWebProcess", "WPENetworkProcess"};

    static const char* const entrypointName[static_cast<int>(Wpe::Android::ProcessType::TypesCount)]
        = {"android_WebProcess_main", "android_NetworkProcess_main"};

    using ProcessEntryPoint = int(int, char**);
    auto* entrypoint
        = reinterpret_cast<ProcessEntryPoint*>(dlsym(RTLD_DEFAULT, entrypointName[static_cast<int>(processType)]));
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
    Wpe::Android::configureEnvironment(envStringsArray);
}
} // namespace

jint Wpe::Android::registerServiceEntryPoints(JavaVM* vm, const char* serviceGlueClass)
{
    JNIEnv* env = Wpe::Android::initVM(vm);
    jclass klass = env->FindClass(serviceGlueClass);
    if (klass == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[] = {{"initializeMain", "(II)V", reinterpret_cast<void*>(initializeMain)},
        {"setupEnvironment", "([Ljava/lang/String;)V", reinterpret_cast<void*>(setupEnvironment)}};
    int result = env->RegisterNatives(klass, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(klass);

    return (result != JNI_OK) ? result : Wpe::Android::JNI_VERSION;
}
