#pragma once

#include <jni.h>

namespace Wpe::Android {
enum class ProcessType : jint {
    FirstType = 0,
    WebProcess = FirstType,
    NetworkProcess,
    TypesCount
};

jint registerServiceEntryPoints(JavaVM* vm, const char* serviceGlueClass);
} // namespace wpe::android
