#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#include "logging.h"

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpe_services_NetworkProcessGlue_initializeMain(JNIEnv*, jobject, jint);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_services_NetworkProcessGlue_setupEnvironment(JNIEnv*, jobject, jstring, jstring);
    jint JNI_OnLoad (JavaVM *, void *);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_services_NetworkProcessGlue_initializeMain(JNIEnv*, jobject, jint fd)
{
    pipe_stdout_to_logcat();

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

JNIEXPORT void JNICALL
Java_com_wpe_wpe_services_NetworkProcessGlue_setupEnvironment(JNIEnv* env, jobject,
        jstring cachePath, jstring extraModulesPath)
{
    ALOGV("Glue::setupEnvironment()");

    const char* _cachePath = env->GetStringUTFChars(cachePath, 0);
    setenv("XDG_RUNTIME_DIR", _cachePath, 1);

    const char* _extraModulesPath = env->GetStringUTFChars(extraModulesPath, 0);
    setenv("GIO_EXTRA_MODULES", _extraModulesPath, 1);
}

__attribute__((visibility("default")))
jint JNI_OnLoad (JavaVM * vm, void *reserved)
{
    // VM resolves and calls JNI_OnLoad from loaded library. libWPEWebKit has dependency
    // to libgstreamer.so which also exports JNI_OnLoad. JNI_OnLoad in libgstreamer requires
    // GStreamer java class to be present in specific package and call fails with invalid JNI
    // version if GStreamer java class is not found. By declaring JNI_OnLoad here we prevent
    // JNI_OnLoad in libgstreamer.so from being called as for now we don't need GStreamer Java bindings.


    // TODO: Instead of explicitly exporting native methods, register them using registerNativeMethods
    //       which is recommended in https://developer.android.com/training/articles/perf-jni.html

    return JNI_VERSION_1_6;
}

