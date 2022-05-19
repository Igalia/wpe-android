#include "environment.h"

#include "jnihelper.h"
#include "logging.h"

#include <cstdlib>
#include <cassert>

bool wpe::android::configureEnvironment(jobjectArray envStringsArray)
{
    if (envStringsArray == nullptr)
        return true;

    try {
        JNIEnv* env = wpe::android::getCurrentThreadJNIEnv();
        jsize size = env->GetArrayLength(reinterpret_cast<jarray>(envStringsArray));
        assert(size % 2 == 0);

        for (jsize i = 1; i < size; i += 2) {
            jstring jName = reinterpret_cast<jstring>(env->GetObjectArrayElement(envStringsArray, i - 1));
            if (jName == nullptr)
                continue;

            jstring jValue = reinterpret_cast<jstring>(env->GetObjectArrayElement(envStringsArray, i));
            if (jValue == nullptr) {
                env->DeleteLocalRef(jName);
                continue;
            }

            const char* name = env->GetStringUTFChars(jName, nullptr);
            const char* value = env->GetStringUTFChars(jValue, nullptr);
            setenv(name, value, 1);
            env->ReleaseStringUTFChars(jValue, value);
            env->ReleaseStringUTFChars(jName, name);

            env->DeleteLocalRef(jValue);
            env->DeleteLocalRef(jName);
        }

        return true;
    } catch (...) {
        ALOGE("Cannot configure native environment (JNI environment error)");
        return false;
    }
}
