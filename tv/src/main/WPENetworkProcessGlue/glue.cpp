#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#include "logging.h"

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpe_NetworkProcess_Glue_initializeGioExtraModulesPath(JNIEnv*, jobject, jstring);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_NetworkProcess_Glue_initializeMain(JNIEnv*, jobject, jint);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_NetworkProcess_Glue_initializeGioExtraModulesPath(JNIEnv* env, jobject, jstring extraModulesPath)
{
    ALOGV("Glue::initializeGIOExtraModulesPath(), path %p", extraModulesPath);
    jsize pathLength = env->GetStringUTFLength(extraModulesPath);
    const char* pathChars = env->GetStringUTFChars(extraModulesPath, 0);
    ALOGV("  pathLength %d, pathChars %s", pathLength, pathChars);

    setenv("GIO_EXTRA_MODULES", pathChars, 1);
}


JNIEXPORT void JNICALL
Java_com_wpe_wpe_NetworkProcess_Glue_initializeMain(JNIEnv*, jobject, jint fd)
{
    using NetworkProcessEntryPoint = int(int, char**);
    auto* entrypoint = reinterpret_cast<NetworkProcessEntryPoint*>(dlsym(RTLD_DEFAULT, "android_NetworkProcess_main"));

    ALOGV("Glue::initializeMain(), fd %d, entrypoint %p", fd, entrypoint);

    char fdString[32];
    snprintf(fdString, sizeof(fdString), "%d", fd);

    char* argv[2];
    argv[0] = "WPENetworkProcess";
    argv[1] = fdString;
    (*entrypoint)(2, argv);
}