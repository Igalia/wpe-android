#include "logging.h"
#include "jnihelper.h"

#include <dlfcn.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*);
}

namespace {
void initializeMain(JNIEnv*, jclass, jint fd)
{
    wpe::android::pipeStdoutToLogcat();

    using NetworkProcessEntryPoint = int(int, char**);
    auto* entrypoint = reinterpret_cast<NetworkProcessEntryPoint*>(dlsym(RTLD_DEFAULT, "android_NetworkProcess_main"));

    ALOGV("Glue::initializeMain(), fd %d, entrypoint %p", fd, entrypoint);

    char pidString[32];
    snprintf(pidString, sizeof(pidString), "%d", getpid());
    char fdString[32];
    snprintf(fdString, sizeof(fdString), "%d", fd);

    char* argv[3];
    argv[0] = (char*)"WPENetworkProcess";
    argv[1] = pidString;
    argv[2] = fdString;
    (*entrypoint)(3, argv);
}

void setupEnvironment(JNIEnv* env, jclass, jstring cachePath, jstring extraModulesPath)
{
    ALOGV("Glue::setupEnvironment()");

    const char* str = env->GetStringUTFChars(cachePath, nullptr);
    setenv("XDG_RUNTIME_DIR", str, 1);
    env->ReleaseStringUTFChars(cachePath, str);

    str = env->GetStringUTFChars(extraModulesPath, nullptr);
    setenv("GIO_EXTRA_MODULES", str, 1);
    env->ReleaseStringUTFChars(extraModulesPath, str);
}
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = wpe::android::initVM(vm);
    jclass klass = env->FindClass("com/wpe/wpe/services/NetworkProcessGlue");
    if (klass == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[] = {
            { "initializeMain",   "(I)V",                                    reinterpret_cast<void*>(initializeMain) },
            { "setupEnvironment", "(Ljava/lang/String;Ljava/lang/String;)V", reinterpret_cast<void*>(setupEnvironment) }
    };
    int result = env->RegisterNatives(klass, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(klass);

    return (result != JNI_OK) ? result : wpe::android::JNI_VERSION;
}
