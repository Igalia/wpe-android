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

#include "Environment.h"

#include "JNIHelper.h"
#include "Logging.h"

#include <cassert>
#include <cstdlib>

bool Wpe::Android::configureEnvironment(jobjectArray envStringsArray)
{
    if (envStringsArray == nullptr)
        return true;

    try {
        JNIEnv* env = Wpe::Android::getCurrentThreadJNIEnv();
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
