#include "logging.h"
#include "jnihelper.h"
#include "environment.h"

#include <dlfcn.h>
#include <glib.h>
#include <unistd.h>

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*);
}

namespace {
void initializeMain(JNIEnv*, jclass, jint fd1, jint fd2)
{
    wpe::android::pipeStdoutToLogcat();

    using WebProcessEntryPoint = int(int, char**);
    auto* entrypoint = reinterpret_cast<WebProcessEntryPoint*>(dlsym(RTLD_DEFAULT, "android_WebProcess_main"));
    ALOGV("Glue::initializeMain(), fd1 %d, fd2 %d, entrypoint %p", fd1, fd2, entrypoint);

    char pidString[32];
    snprintf(pidString, sizeof(pidString), "%d", getpid());
    char fd1String[32];
    snprintf(fd1String, sizeof(fd1String), "%d", fd1);
    char fd2String[32];
    snprintf(fd2String, sizeof(fd1String), "%d", fd2);

    char* argv[4];
    argv[0] = const_cast<char*>("WPEWebProcess");
    argv[1] = pidString;
    argv[2] = fd1String;
    argv[3] = fd2String;
    (*entrypoint)(4, argv);
}

void setupEnvironment(JNIEnv*, jclass, jobjectArray envStringsArray)
{
    ALOGV("Glue::setupEnvironment()");
    wpe::android::configureEnvironment(envStringsArray);
}
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = wpe::android::initVM(vm);
    jclass klass = env->FindClass("com/wpe/wpe/services/WebProcessGlue");
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
