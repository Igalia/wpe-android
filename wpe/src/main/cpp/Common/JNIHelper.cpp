/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez <fjimenez@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

#include "JNIHelper.h"

#include "Logging.h"

#include <pthread.h>
#include <stdexcept>

namespace {
JavaVM* s_javaVM = nullptr;
pthread_key_t s_currentJNIEnvKey = 0;

void detachTerminatedNativeThread(void*)
{
    if (s_javaVM != nullptr) {
        ALOGV("Detaching terminated native thread %ld", pthread_self());
        s_javaVM->DetachCurrentThread();
    }
}
} // namespace

JNIEnv* Wpe::Android::initVM(JavaVM* vm)
{
    if (s_javaVM != nullptr) {
        ALOGE("Fatal error: Java VM already initialized for current process");
        throw std::runtime_error("Java VM already initialized for current process");
    }

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), Wpe::Android::JNI_VERSION) != JNI_OK) {
        ALOGE("Fatal error: cannot fetch JNIEnv from JavaVM initialization");
        throw std::runtime_error("Cannot fetch JNIEnv from JavaVM initialization");
    }

    if (pthread_key_create(&s_currentJNIEnvKey, detachTerminatedNativeThread) != 0) {
        ALOGE("Fatal error: cannot create pthread key for native threads");
        throw std::runtime_error("Cannot create pthread key for native threads");
    }

    s_javaVM = vm;
    return env;
}

JNIEnv* Wpe::Android::getCurrentThreadJNIEnv()
{
    JNIEnv* env = reinterpret_cast<JNIEnv*>(pthread_getspecific(s_currentJNIEnvKey));
    if (env == nullptr && s_javaVM != nullptr) {
        if (s_javaVM->GetEnv(reinterpret_cast<void**>(&env), Wpe::Android::JNI_VERSION) == JNI_EDETACHED) {
            JavaVMAttachArgs args = {0};
            args.version = Wpe::Android::JNI_VERSION;

            char threadName[16]; // 16 is the max size for android thread names (including terminating char)
            if (pthread_getname_np(pthread_self(), threadName, sizeof(threadName)) == 0)
                args.name = threadName;

            ALOGV("Attaching native thread %ld", pthread_self());
            if (s_javaVM->AttachCurrentThread(&env, &args) == JNI_OK)
                pthread_setspecific(s_currentJNIEnvKey, env);
        }
    }

    if (env == nullptr) {
        ALOGE("Fatal error: cannot fetch current thread JNIEnv");
        throw std::runtime_error("Cannot fetch current thread JNIEnv");
    }

    return env;
}

void Wpe::Android::checkException(JNIEnv* env)
{
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        throw std::runtime_error("Java exception from JNI call");
    }
}
