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

#pragma once

#include "JNITypes.h"

#include <stdexcept>

namespace JNI {
constexpr jint VERSION = JNI_VERSION_1_6;

JNIEnv* initVM(JavaVM* javaVM);
JNIEnv* getCurrentThreadJNIEnv();

void enableJavaExceptionDescription(bool enable);
void checkJavaException(JNIEnv* env);

ProtectedType<jobject> createProtectedRef(JNIEnv* env, const jobject& obj, bool useGlobalRef = false);

template <typename T>
inline EnableIfObjectType<T, ProtectedType<T>> createTypedProtectedRef(
    JNIEnv* env, const T& obj, bool useGlobalRef = false)
{
    return std::static_pointer_cast<std::remove_pointer_t<T>>(createProtectedRef(env, obj, useGlobalRef));
}

// WARNING: in both following functions, the moved obj MUST be a local reference
ProtectedType<jobject> createProtectedRef(JNIEnv* env, jobject&& obj, bool useGlobalRef = false);

template <typename T>
inline EnableIfObjectType<T, ProtectedType<T>> createTypedProtectedRef(JNIEnv* env, T&& obj, bool useGlobalRef = false)
{
    return std::static_pointer_cast<std::remove_pointer_t<T>>(
        createProtectedRef(env, std::move(obj), useGlobalRef)); // NOLINT(bugprone-move-forwarding-reference)
}

} // namespace JNI
