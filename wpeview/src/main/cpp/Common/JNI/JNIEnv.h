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

// Protected refs are for short-lived RAII ownership inside the current native flow.
// For async callbacks or native objects that keep a Java back-reference beyond the
// current stack frame, prefer GlobalRef explicitly.
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
    return std::static_pointer_cast<std::remove_pointer_t<T>>(createProtectedRef(
        env, std::move(std::forward<T>(obj)), useGlobalRef)); // NOLINT(bugprone-move-forwarding-reference)
}

// Move-only RAII owner of a single Java global reference.
// Analogous to Chromium's ScopedJavaGlobalRef and preferred for async callback
// holders plus native-to-Java back-references that outlive the current JNI call.
template <typename T, typename = EnableIfObjectType<T>> class GlobalRef final {
public:
    GlobalRef() noexcept
        : m_ref(nullptr)
    {
    }
    GlobalRef(JNIEnv* env, T obj)
        : m_ref(obj ? env->NewGlobalRef(obj) : nullptr)
    {
    }
    GlobalRef(JNIEnv* env, jobject obj)
        : m_ref(obj ? env->NewGlobalRef(obj) : nullptr)
    {
    }
    ~GlobalRef()
    {
        reset();
    }

    GlobalRef(GlobalRef&& other) noexcept
        : m_ref(other.release())
    {
    }
    GlobalRef& operator=(GlobalRef&& other) noexcept
    {
        if (this != &other) {
            reset();
            m_ref = other.release();
        }
        return *this;
    }
    GlobalRef(const GlobalRef&) = delete;
    GlobalRef& operator=(const GlobalRef&) = delete;

    T get() const noexcept
    {
        return static_cast<T>(m_ref);
    }
    explicit operator bool() const noexcept
    {
        return m_ref != nullptr;
    }

    T release() noexcept
    {
        auto* ref = static_cast<T>(m_ref);
        m_ref = nullptr;
        return ref;
    }

    void reset() noexcept
    {
        if (m_ref) {
            getCurrentThreadJNIEnv()->DeleteGlobalRef(m_ref);
            m_ref = nullptr;
        }
    }

private:
    jobject m_ref;
};

} // namespace JNI
