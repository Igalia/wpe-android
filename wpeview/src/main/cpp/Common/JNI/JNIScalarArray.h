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

#include "JNIEnv.h"

#include <functional>

namespace JNI {

template <typename T, typename = void> class ScalarArray;

template <typename T, bool isConst> class GenericScalarSpan final {
public:
    inline size_t getSize() const noexcept { return m_size; }

    inline const T* begin() const noexcept { return m_data.get(); }
    inline const T* end() const noexcept { return m_data.get() + m_size; }
    inline const T& operator[](size_t index) const
    {
        if (index >= m_size)
            throw std::out_of_range("Invalid index");
        return m_data.get()[index];
    }

    template <typename U = T> inline std::enable_if_t<!isConst, U*> begin() noexcept { return m_data.get(); }
    template <typename U = T> inline std::enable_if_t<!isConst, U*> end() noexcept { return m_data.get() + m_size; }
    template <typename U = T> inline std::enable_if_t<!isConst, U&> operator[](size_t index)
    {
        if (index >= m_size)
            throw std::out_of_range("Invalid index");
        return m_data.get()[index];
    }

    // Changes made to the content of a non-const ScalarSpan are automatically
    // committed when the ScalarSpan is destroyed.
    // The following commit() method allows to force this commit immediately.
    // Once commit() is called, the ScalarSpan is set to empty.
    template <typename U = void> inline std::enable_if_t<!isConst, U> commit()
    {
        m_size = 0;
        m_data.reset();
    }

private:
    friend ScalarArray<T>;
    using ProtectedData = std::unique_ptr<T, std::function<void(T*)>>;

    inline GenericScalarSpan(jsize size, ProtectedData&& data)
        : m_size(static_cast<size_t>(size))
        , m_data(std::move(data))
    {
    }

    size_t m_size;
    ProtectedData m_data;
};

template <typename T> using ConstScalarSpan = GenericScalarSpan<T, true>;
template <typename T> using ScalarSpan = GenericScalarSpan<T, false>;

template <typename T> class ScalarArray<T, EnableIfScalarType<T>> final {
public:
    ScalarArray(size_t size, bool useGlobalRef = false)
    {
        auto* env = getCurrentThreadJNIEnv();
        ArrayType<T> localArray;
        if constexpr (std::is_same_v<T, jboolean>) {
            localArray = env->NewBooleanArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jbyte>) {
            localArray = env->NewByteArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jchar>) {
            localArray = env->NewCharArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jshort>) {
            localArray = env->NewShortArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jint>) {
            localArray = env->NewIntArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jlong>) {
            localArray = env->NewLongArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jfloat>) {
            localArray = env->NewFloatArray(static_cast<jsize>(size));
        } else if constexpr (std::is_same_v<T, jdouble>) {
            localArray = env->NewDoubleArray(static_cast<jsize>(size));
        } else {
            static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
        }

        if (localArray == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot create a Java array");
        }

        m_javaArrayRef = createTypedProtectedRef(env, std::move(localArray), useGlobalRef);
    }

    ScalarArray(const ArrayType<T>& javaArray, bool useGlobalRef = false)
    {
        if (javaArray != nullptr)
            m_javaArrayRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), javaArray, useGlobalRef);
    }

    // WARNING: the moved javaArray MUST be a local reference
    ScalarArray(ArrayType<T>&& javaArray, bool useGlobalRef = false)
    {
        if (javaArray != nullptr)
            m_javaArrayRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), std::move(javaArray), useGlobalRef);
    }

    ScalarArray(ProtectedArrayType<T> javaArrayRef)
        : m_javaArrayRef(std::move(javaArrayRef))
    {
    }

    bool operator==(const ScalarArray<T>& other) const noexcept
    {
        ArrayType<T> thisRef = m_javaArrayRef.get();
        ArrayType<T> otherRef = other.m_javaArrayRef.get();

        if ((thisRef == nullptr) || (otherRef == nullptr))
            return (thisRef == otherRef);

        try {
            return (getCurrentThreadJNIEnv()->IsSameObject(thisRef, otherRef) == JNI_TRUE);
        } catch (...) {
            return false;
        }
    }

    inline operator ArrayType<T>() const noexcept { return m_javaArrayRef.get(); }

    size_t getSize() const
    {
        if (m_javaArrayRef == nullptr)
            return 0;

        auto* env = getCurrentThreadJNIEnv();
        jsize size = env->GetArrayLength(m_javaArrayRef.get());
        checkJavaException(env);
        return (size > 0) ? static_cast<size_t>(size) : 0;
    }

    ConstScalarSpan<T> getReadOnlyContent() const { return getInternalContent<JNI_ABORT>(); }
    ScalarSpan<T> getContent() { return getInternalContent<JNI_COMMIT>(); }

private:
    ProtectedArrayType<T> m_javaArrayRef;

    template <int mode> GenericScalarSpan<T, mode != JNI_COMMIT> getInternalContent() const
    {
        ArrayType<T> const javaArrayRef = m_javaArrayRef.get();
        if (javaArrayRef == nullptr)
            return {0, {}};

        auto* env = getCurrentThreadJNIEnv();
        jsize size = env->GetArrayLength(javaArrayRef);
        checkJavaException(env);
        if (size <= 0)
            return {0, {}};

        T* arrayElements = nullptr;
        if constexpr (std::is_same_v<T, jboolean>) {
            arrayElements = env->GetBooleanArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jbyte>) {
            arrayElements = env->GetByteArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jchar>) {
            arrayElements = env->GetCharArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jshort>) {
            arrayElements = env->GetShortArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jint>) {
            arrayElements = env->GetIntArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jlong>) {
            arrayElements = env->GetLongArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jfloat>) {
            arrayElements = env->GetFloatArrayElements(javaArrayRef, nullptr);
        } else if constexpr (std::is_same_v<T, jdouble>) {
            arrayElements = env->GetDoubleArrayElements(javaArrayRef, nullptr);
        } else {
            static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
        }

        if (arrayElements == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot get Java scalar array content");
        }

        return {size, {arrayElements, [javaArrayRef](T* ptr) {
                           try {
                               auto* releaseEnv = getCurrentThreadJNIEnv();
                               if constexpr (std::is_same_v<T, jboolean>) {
                                   releaseEnv->ReleaseBooleanArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jbyte>) {
                                   releaseEnv->ReleaseByteArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jchar>) {
                                   releaseEnv->ReleaseCharArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jshort>) {
                                   releaseEnv->ReleaseShortArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jint>) {
                                   releaseEnv->ReleaseIntArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jlong>) {
                                   releaseEnv->ReleaseLongArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jfloat>) {
                                   releaseEnv->ReleaseFloatArrayElements(javaArrayRef, ptr, mode);
                               } else if constexpr (std::is_same_v<T, jdouble>) {
                                   releaseEnv->ReleaseDoubleArrayElements(javaArrayRef, ptr, mode);
                               } else {
                                   static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
                               }
                           } catch (...) {
                           }
                       }}};
    }
};

} // namespace JNI
