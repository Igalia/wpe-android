#include <jni.h>

#include "logging.h"
#include "wpeinstance.h"

extern "C" {
    JNIEXPORT void JNICALL Java_com_wpe_wpedemo_WPEUIProcessGlue_init(JNIEnv*, jobject, jobject);
    JNIEXPORT void JNICALL Java_com_wpe_wpedemo_WPEUIProcessGlue_deinit(JNIEnv*, jobject);

    JNIEXPORT JNIEnv* s_WPEUIProcessGlue_env = 0;
    JNIEXPORT jobject s_WPEUIProcessGlue_object = 0;
}

JNIEXPORT void JNICALL
Java_com_wpe_wpedemo_WPEUIProcessGlue_init(JNIEnv* env, jobject obj, jobject glueObj)
{
    ALOGV("WPEUIProcessGlue.init()");

    s_WPEUIProcessGlue_env = env;
    s_WPEUIProcessGlue_object = glueObj;
    ALOGV("WPEUIProcessGlue initialized VM to %p, its address %p", env, &s_WPEUIProcessGlue_env);
    ALOGV("WPEUIProcessGlue initialized object to %p, its address %p", obj, &s_WPEUIProcessGlue_object);

    wpe_instance_init(env, glueObj);
}

JNIEXPORT void JNICALL
Java_com_wpe_wpedemo_WPEUIProcessGlue_deinit(JNIEnv*, jobject)
{
    ALOGV("WPEUIProcessGlue.deinit()");
    s_WPEUIProcessGlue_env = 0;
    s_WPEUIProcessGlue_object = 0;
    wpe_instance_deinit();
}