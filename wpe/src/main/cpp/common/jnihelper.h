#pragma once

#include <jni.h>

namespace wpe { namespace android {

constexpr jint JNI_VERSION = JNI_VERSION_1_6;

JNIEnv* initVM(JavaVM* vm);
JNIEnv* getCurrentThreadJNIEnv();

}} // namespace wpe::android
