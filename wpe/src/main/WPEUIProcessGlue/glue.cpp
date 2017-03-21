#include <jni.h>

#include "logging.h"
#include "wpeinstance.h"
#include <dlfcn.h>

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpe_UIProcess_Glue_init(JNIEnv*, jobject, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpe_UIProcess_Glue_deinit(JNIEnv*, jobject);

    JNIEXPORT void JNICALL Java_com_wpe_wpe_UIProcess_Glue_hackFrameComplete(JNIEnv*, jobject);

    JNIEXPORT JNIEnv* s_WPEUIProcessGlue_env = 0;
    JNIEXPORT jobject s_WPEUIProcessGlue_object = 0;
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_UIProcess_Glue_init(JNIEnv* env, jobject obj, jobject glueObj)
{
    ALOGV("Glue.init()");

    s_WPEUIProcessGlue_env = env;
    s_WPEUIProcessGlue_object = glueObj;
    ALOGV("Glue initialized VM to %p, its address %p", env, &s_WPEUIProcessGlue_env);
    ALOGV("Glue initialized object to %p, its address %p", obj, &s_WPEUIProcessGlue_object);

    wpe_instance_init(env, glueObj);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_UIProcess_Glue_deinit(JNIEnv*, jobject)
{
    ALOGV("Glue.deinit()");
    s_WPEUIProcessGlue_env = 0;
    s_WPEUIProcessGlue_object = 0;
    wpe_instance_deinit();
}

JNIEXPORT void JNICALL
Java_com_wpe_wpe_UIProcess_Glue_hackFrameComplete(JNIEnv*, jobject)
{
    ALOGV("Glue.hack_frameComplete()");

    using HackFrameCompleteEntryPoint = void();
    auto* entrypoint = reinterpret_cast<HackFrameCompleteEntryPoint*>(dlsym(RTLD_DEFAULT, "libwpe_android_hack_frameComplete"));

    (*entrypoint)();
}