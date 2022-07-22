#pragma once

#include <jni.h>

namespace Wpe::Android {
constexpr jint JNI_VERSION = JNI_VERSION_1_6;

JNIEnv* initVM(JavaVM* vm);
JNIEnv* getCurrentThreadJNIEnv();
void checkException(JNIEnv* env);

} // namespace Wpe::Android
