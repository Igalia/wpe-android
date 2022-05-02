#include <jni.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <gio/gio.h>
#include <glib/gtypes.h>
#include <stdio.h>
#include <stdlib.h>

#include "logging.h"

extern "C" {
JNIEXPORT void JNICALL Java_com_wpe_wpe_services_WebProcessGlue_initializeMain(JNIEnv*, jobject, jint, jint);
JNIEXPORT void JNICALL Java_com_wpe_wpe_services_WebProcessGlue_setupEnvironment(JNIEnv*, jobject, jstring, jstring, jstring, jstring, jstring, jstring);
jint JNI_OnLoad(JavaVM*, void *);
}

using WebProcessEntryPoint = int(int, char**);

JNIEXPORT void JNICALL
Java_com_wpe_wpe_services_WebProcessGlue_initializeMain(JNIEnv*, jobject, jint fd1, jint fd2)
{
    pipe_stdout_to_logcat();
    enable_gst_debug();

    auto* entrypoint = reinterpret_cast<WebProcessEntryPoint*>(dlsym(RTLD_DEFAULT, "android_WebProcess_main"));
    ALOGV("Glue::initializeMain(), fd1 %d, fd2 %d, entrypoint %p", fd1, fd2, entrypoint);

    char pidString[32];
    snprintf(pidString, sizeof(pidString), "%d", getpid());
    char fd1String[32];
    snprintf(fd1String, sizeof(fd1String), "%d", fd1);
    char fd2String[32];
    snprintf(fd2String, sizeof(fd1String), "%d", fd2);

    char* argv[4];
    argv[0] = (char*)"WPEWebProcess";
    argv[1] = pidString;
    argv[2] = fd1String;
    argv[3] = fd2String;
    (*entrypoint)(4, argv);
}

void Java_com_wpe_wpe_services_WebProcessGlue_setupEnvironment(JNIEnv *env, jobject,
                                   jstring fontconfigPath,
                                   jstring gstreamerPath,
                                   jstring gioPath,
                                   jstring nativeLibsPath,
                                   jstring cachePath,
                                   jstring filesPath) {
    ALOGV("Glue::setupEnvironment()");

    const char* _fontconfigPath = env->GetStringUTFChars(fontconfigPath, 0);
    const char* _gstreamerPath = env->GetStringUTFChars(gstreamerPath, 0);
    const char* _gioPath = env->GetStringUTFChars(gioPath, 0);
    const char* _cachePath = env->GetStringUTFChars(cachePath, 0);
    const char* _nativeLibsPath = env->GetStringUTFChars(nativeLibsPath, 0);
    const char* _filesPath = env->GetStringUTFChars(filesPath, 0);

    setenv("FONTCONFIG_PATH", _fontconfigPath, 1);

    setenv("GST_PLUGIN_PATH", _gstreamerPath, 1);
    setenv("GST_PLUGIN_SYSTEM_PATH", _gstreamerPath, 1);
    setenv("LD_LIBRARY_PATH", _nativeLibsPath, 1);
    setenv("LIBRARY_PATH", _nativeLibsPath, 1);
    setenv("TMP", _cachePath, 1);
    setenv("TEMP", _cachePath, 1);
    setenv("TMPDIR", _cachePath, 1);
    gchar* registry = g_build_filename(_cachePath, "registry.bin", NULL);
    setenv("GST_REGISTRY", registry, 1);
    g_free(registry);
    setenv("GST_REGISTRY_UPDATE", "no", 1);
    setenv ("GST_REGISTRY_REUSE_PLUGIN_SCANNER", "no", 1);

    setenv("XDG_CACHE_HOME", _cachePath, 1);
    setenv("XDG_RUNTIME_DIR", _cachePath, 1);
    setenv("HOME", _filesPath, 1);
    setenv("XDG_DATA_DIRS", _filesPath, 1);
    setenv("XDG_CONFIG_DIRS", _filesPath, 1);
    setenv("XDG_CONFIG_HOME", _filesPath, 1);
    setenv("XDG_DATA_HOME", _filesPath, 1);

    setenv("GIO_EXTRA_MODULES", _gioPath, 1);
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

    return JNI_VERSION_1_4;
}

