#include "logging.h"
#include "jnihelper.h"

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
    wpe::android::enableGstDebug();

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

void setupEnvironment(JNIEnv* env, jclass, jstring fontconfigPath, jstring gstreamerPath, jstring gioPath,
                      jstring nativeLibsPath, jstring cachePath, jstring filesPath)
{
    ALOGV("Glue::setupEnvironment()");

    const char* str = env->GetStringUTFChars(fontconfigPath, nullptr);
    setenv("FONTCONFIG_PATH", str, 1);
    env->ReleaseStringUTFChars(fontconfigPath, str);

    str = env->GetStringUTFChars(gstreamerPath, nullptr);
    setenv("GST_PLUGIN_PATH", str, 1);
    setenv("GST_PLUGIN_SYSTEM_PATH", str, 1);
    env->ReleaseStringUTFChars(gstreamerPath, str);

    str = env->GetStringUTFChars(gioPath, nullptr);
    setenv("GIO_EXTRA_MODULES", str, 1);
    env->ReleaseStringUTFChars(gioPath, str);

    str = env->GetStringUTFChars(nativeLibsPath, nullptr);
    setenv("LD_LIBRARY_PATH", str, 1);
    setenv("LIBRARY_PATH", str, 1);
    env->ReleaseStringUTFChars(nativeLibsPath, str);

    str = env->GetStringUTFChars(cachePath, nullptr);
    setenv("TMP", str, 1);
    setenv("TEMP", str, 1);
    setenv("TMPDIR", str, 1);
    gchar* registry = g_build_filename(str, "registry.bin", nullptr);
    setenv("GST_REGISTRY", registry, 1);
    g_free(registry);
    setenv("XDG_CACHE_HOME", str, 1);
    setenv("XDG_RUNTIME_DIR", str, 1);
    env->ReleaseStringUTFChars(cachePath, str);

    setenv("GST_REGISTRY_UPDATE", "no", 1);
    setenv("GST_REGISTRY_REUSE_PLUGIN_SCANNER", "no", 1);

    str = env->GetStringUTFChars(filesPath, nullptr);
    setenv("HOME", str, 1);
    setenv("XDG_DATA_DIRS", str, 1);
    setenv("XDG_CONFIG_DIRS", str, 1);
    setenv("XDG_CONFIG_HOME", str, 1);
    setenv("XDG_DATA_HOME", str, 1);
    env->ReleaseStringUTFChars(filesPath, str);
}
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = wpe::android::initVM(vm);
    jclass klass = env->FindClass("com/wpe/wpe/services/WebProcessGlue");
    if (klass == nullptr)
        return JNI_ERR;

    static const JNINativeMethod methods[] = {
            {
                    "initializeMain",
                    "(II)V",
                    reinterpret_cast<void*>(initializeMain)
            },
            {
                    "setupEnvironment",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    reinterpret_cast<void*>(setupEnvironment)
            }
    };
    int result = env->RegisterNatives(klass, methods, sizeof(methods) / sizeof(JNINativeMethod));
    env->DeleteLocalRef(klass);

    return (result != JNI_OK) ? result : wpe::android::JNI_VERSION;
}
