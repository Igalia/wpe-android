#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#include "logging.h"

extern "C" {
JNIEXPORT void JNICALL Java_com_wpe_wpe_StorageProcess_Glue_initializeXdg(JNIEnv*, jobject, jstring);
JNIEXPORT void JNICALL Java_com_wpe_wpe_StorageProcess_Glue_initializeMain(JNIEnv*, jobject, jint);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_StorageProcess_Glue_initializeXdg(JNIEnv* env, jobject, jstring xdgRuntimePath)
{
    ALOGV("Glue::initializeXdg()");

    const char* cachePath = env->GetStringUTFChars(xdgRuntimePath, 0);
    ALOGV("  runtimePath %s", cachePath);

    setenv("XDG_RUNTIME_DIR", cachePath, 1);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_StorageProcess_Glue_initializeMain(JNIEnv*, jobject, jint fd)
{
    using StorageProcessEntryPoint = int(int, char**);
    auto* entrypoint = reinterpret_cast<StorageProcessEntryPoint*>(dlsym(RTLD_DEFAULT, "android_StorageProcess_main"));

    ALOGV("Glue::initializeMain(), fd %d, entrypoint %p", fd, entrypoint);

    char fdString[32];
    snprintf(fdString, sizeof(fdString), "%d", fd);

    char* argv[2];
    argv[0] = "WPEStorageProcess";
    argv[1] = fdString;
    (*entrypoint)(2, argv);
}