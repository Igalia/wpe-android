/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "JNIEnv.h"

#include <atomic>
#include <pthread.h>
#include <string>

namespace {
// Android threads names have maximum 16 characters (including the terminating null char)
constexpr size_t MAX_THREAD_NAME_SIZE = 16;

// NOLINTBEGIN(cppcoreguidelines-avoid-non-const-global-variables)
JavaVM* globalJavaVM = nullptr;
pthread_key_t globalJNIEnvKey = 0;
std::atomic_bool globalEnableJavaExceptionDescription = true;
// NOLINTEND(cppcoreguidelines-avoid-non-const-global-variables)

// TODO NOLINTNEXTLINE(readability-identifier-naming)
extern "C" __attribute__((visibility("default"))) JavaVM* wpe_android_runtime_get_current_java_vm()
{
    return globalJavaVM;
}

void detachTerminatedNativeThread(void* /*keyValue*/)
{
    if (globalJavaVM != nullptr)
        globalJavaVM->DetachCurrentThread();
}

// Fetches Throwable.toString() from an already cleared exception, so the JNI calls are legal.
std::string exceptionDescription(JNIEnv* env, jthrowable exception)
{
    std::string description = "no description";
    if (exception == nullptr)
        return description;

    jclass exceptionClass = env->GetObjectClass(exception);
    if (exceptionClass != nullptr) {
        jmethodID toStringMethod = env->GetMethodID(exceptionClass, "toString", "()Ljava/lang/String;");
        if (toStringMethod != nullptr) {
            auto str = reinterpret_cast<jstring>(env->CallObjectMethod(exception, toStringMethod));
            if (env->ExceptionCheck() == JNI_TRUE)
                env->ExceptionClear();
            if (str != nullptr) {
                if (const char* content = env->GetStringUTFChars(str, nullptr)) {
                    description = content;
                    env->ReleaseStringUTFChars(str, content);
                }
                env->DeleteLocalRef(str);
            }
        }
        env->DeleteLocalRef(exceptionClass);
    }
    return description;
}
} // namespace

JNIEnv* JNI::initVM(JavaVM* javaVM)
{
    if (globalJavaVM != nullptr) {
        fatalError("Java VM already initialized for current process");
    }
    // TODO NOLINTNEXTLINE(misc-const-correctness)
    JNIEnv* env = nullptr;
    if (javaVM->GetEnv(reinterpret_cast<void**>(&env), VERSION) != JNI_OK) {
        fatalError("Cannot fetch JNIEnv from JavaVM initialization");
    }

    if (pthread_key_create(&globalJNIEnvKey, detachTerminatedNativeThread) != 0) {
        fatalError("Cannot create pthread key for native threads");
    }

    globalJavaVM = javaVM;
    return env;
}

JNIEnv* JNI::tryGetCurrentThreadJNIEnv() noexcept
{
    auto* env = reinterpret_cast<JNIEnv*>(pthread_getspecific(globalJNIEnvKey));
    if (env == nullptr && globalJavaVM != nullptr) {
        if (globalJavaVM->GetEnv(reinterpret_cast<void**>(&env), VERSION) == JNI_EDETACHED) {
            JavaVMAttachArgs args = {};
            args.version = VERSION;

            char threadName[MAX_THREAD_NAME_SIZE];
            if (pthread_getname_np(pthread_self(), threadName, sizeof(threadName)) == 0)
                args.name = threadName;

#ifdef USE_JAVA_JDK
            if (globalJavaVM->AttachCurrentThread(reinterpret_cast<void**>(&env), &args) == JNI_OK) {
#else
            if (globalJavaVM->AttachCurrentThread(&env, &args) == JNI_OK) {
#endif // USE_JAVA_JDK
                pthread_setspecific(globalJNIEnvKey, env);
            }
        }
    }

    return env;
}

JNIEnv* JNI::getCurrentThreadJNIEnv()
{
    auto* env = tryGetCurrentThreadJNIEnv();
    if (env == nullptr)
        fatalError("Cannot fetch current thread JNIEnv");

    return env;
}

void JNI::enableJavaExceptionDescription(bool enable)
{
    globalEnableJavaExceptionDescription = enable;
}

bool JNI::clearJavaException(JNIEnv* env)
{
    if (env->ExceptionCheck() != JNI_TRUE)
        return false;

    jthrowable exception = env->ExceptionOccurred();
    if (globalEnableJavaExceptionDescription)
        env->ExceptionDescribe();
    env->ExceptionClear();

    Logging::logError(
        "A Java exception has been thrown during JNI call (%s)", exceptionDescription(env, exception).c_str());
    if (exception != nullptr)
        env->DeleteLocalRef(exception);
    return true;
}

JNI::ProtectedType<jobject> JNI::createProtectedRef(JNIEnv* env, const jobject& obj, bool useGlobalRef)
{
    if (obj == nullptr)
        return {};

    if (useGlobalRef) {
        jobject globalRef = env->NewGlobalRef(obj);
        if (globalRef == nullptr) {
            clearJavaException(env);
            fatalError("Cannot create Java global ref");
        }

        return {globalRef, [](jobject ref) {
                    if (auto* env = tryGetCurrentThreadJNIEnv())
                        env->DeleteGlobalRef(ref);
                }};
    }

    jobject localRef = env->NewLocalRef(obj);
    if (localRef == nullptr) {
        clearJavaException(env);
        fatalError("Cannot create Java local ref");
    }

    return {localRef, [](jobject ref) {
                if (auto* env = tryGetCurrentThreadJNIEnv())
                    env->DeleteLocalRef(ref);
            }};
}

// TODO NOLINTNEXTLINE(cppcoreguidelines-rvalue-reference-param-not-moved)
JNI::ProtectedType<jobject> JNI::createProtectedRef(JNIEnv* env, jobject&& obj, bool useGlobalRef)
{
    if (obj == nullptr)
        return {};

    if (useGlobalRef) {
        jobject globalRef = env->NewGlobalRef(obj);
        env->DeleteLocalRef(obj);
        obj = nullptr;
        if (globalRef == nullptr) {
            clearJavaException(env);
            fatalError("Cannot create Java global ref");
        }

        return {globalRef, [](jobject ref) {
                    if (auto* env = tryGetCurrentThreadJNIEnv())
                        env->DeleteGlobalRef(ref);
                }};
    }

    return {obj, [](jobject ref) {
                if (auto* env = tryGetCurrentThreadJNIEnv())
                    env->DeleteLocalRef(ref);
            }};
}
