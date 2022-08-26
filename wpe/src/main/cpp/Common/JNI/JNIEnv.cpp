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

using namespace Wpe::Android;

namespace {
// Android threads names have maximum 16 characters (including the terminating null char)
constexpr size_t MAX_THREAD_NAME_SIZE = 16;

// NOLINTBEGIN(cppcoreguidelines-avoid-non-const-global-variables)
JavaVM* globalJavaVM = nullptr;
pthread_key_t globalJNIEnvKey = 0;
std::atomic_bool globalEnableJavaExceptionDescription = true;
// NOLINTEND(cppcoreguidelines-avoid-non-const-global-variables)

void detachTerminatedNativeThread(void* /*keyValue*/)
{
    if (globalJavaVM != nullptr)
        globalJavaVM->DetachCurrentThread();
}
} // namespace

JNIEnv* JNI::initVM(JavaVM* javaVM)
{
    if (globalJavaVM != nullptr) {
        throw std::runtime_error("Java VM already initialized for current process");
    }

    JNIEnv* env = nullptr;
    if (javaVM->GetEnv(reinterpret_cast<void**>(&env), VERSION) != JNI_OK) {
        throw std::runtime_error("Cannot fetch JNIEnv from JavaVM initialization");
    }

    if (pthread_key_create(&globalJNIEnvKey, detachTerminatedNativeThread) != 0) {
        throw std::runtime_error("Cannot create pthread key for native threads");
    }

    globalJavaVM = javaVM;
    return env;
}

JNIEnv* JNI::getCurrentThreadJNIEnv()
{
    auto* env = reinterpret_cast<JNIEnv*>(pthread_getspecific(globalJNIEnvKey));
    if (env == nullptr && globalJavaVM != nullptr) {
        if (globalJavaVM->GetEnv(reinterpret_cast<void**>(&env), VERSION) == JNI_EDETACHED) {
            JavaVMAttachArgs args = {0};
            args.version = VERSION;

            char threadName[MAX_THREAD_NAME_SIZE];
            if (pthread_getname_np(pthread_self(), threadName, sizeof(threadName)) == 0)
                args.name = threadName;

            if (globalJavaVM->AttachCurrentThread(reinterpret_cast<void**>(&env), &args) == JNI_OK)
                pthread_setspecific(globalJNIEnvKey, env);
        }
    }

    if (env == nullptr)
        throw std::runtime_error("Cannot fetch current thread JNIEnv");

    return env;
}

void JNI::enableJavaExceptionDescription(bool enable) { globalEnableJavaExceptionDescription = enable; }

void JNI::checkJavaException(JNIEnv* env)
{
    if (env->ExceptionCheck() == JNI_TRUE) {
        if (globalEnableJavaExceptionDescription)
            env->ExceptionDescribe();
        env->ExceptionClear();
        throw std::runtime_error("A Java exception has been thrown during JNI call");
    }
}

JNI::ProtectedType<jobject> JNI::createProtectedRef(JNIEnv* env, const jobject& obj, bool useGlobalRef)
{
    if (obj == nullptr)
        return {};

    if (useGlobalRef) {
        jobject globalRef = env->NewGlobalRef(obj);
        if (globalRef == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot create Java global ref");
        }

        return {globalRef, [](jobject ref) {
                    try {
                        getCurrentThreadJNIEnv()->DeleteGlobalRef(ref);
                    } catch (...) {
                    }
                }};
    }

    jobject localRef = env->NewLocalRef(obj);
    if (localRef == nullptr) {
        checkJavaException(env);
        throw std::runtime_error("Cannot create Java local ref");
    }

    return {localRef, [](jobject ref) {
                try {
                    getCurrentThreadJNIEnv()->DeleteLocalRef(ref);
                } catch (...) {
                }
            }};
}

JNI::ProtectedType<jobject> JNI::createProtectedRef(JNIEnv* env, jobject&& obj, bool useGlobalRef)
{
    if (obj == nullptr)
        return {};

    if (useGlobalRef) {
        jobject globalRef = env->NewGlobalRef(obj);
        env->DeleteLocalRef(obj);
        obj = nullptr;
        if (globalRef == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot create Java global ref");
        }

        return {globalRef, [](jobject ref) {
                    try {
                        getCurrentThreadJNIEnv()->DeleteGlobalRef(ref);
                    } catch (...) {
                    }
                }};
    }

    return {obj, [](jobject ref) {
                try {
                    getCurrentThreadJNIEnv()->DeleteLocalRef(ref);
                } catch (...) {
                }
            }};
}
