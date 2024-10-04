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

namespace JNI {

template <typename T, typename = void> class ObjectArray;

template <typename T> class ConstObjectSpan final {
public:
    inline size_t getSize() const noexcept { return m_size; }

    ProtectedType<T> operator[](size_t index) const
    {
        if (index >= m_size)
            throw std::out_of_range("Invalid index");

        auto* env = getCurrentThreadJNIEnv();
        jobject ret = env->GetObjectArrayElement(
            reinterpret_cast<jobjectArray>(static_cast<jarray>(m_javaArrayRef.get())), static_cast<jsize>(index));
        checkJavaException(env);
        if (ret == nullptr)
            return {};

        return createTypedProtectedRef(env, std::move(reinterpret_cast<T>(ret)));
    }

    class Iterator final {
    public:
        using iterator_category = std::input_iterator_tag;
        using difference_type = size_t;
        using value_type = ProtectedType<T>;
        using pointer = void;
        using reference = void;

        inline ProtectedType<T> operator*() const { return m_span[m_currentIndex]; }

        inline Iterator& operator++() noexcept
        {
            ++m_currentIndex;
            return *this;
        }

        inline Iterator operator++(int) noexcept
        {
            Iterator tmp = *this;
            ++m_currentIndex;
            return tmp;
        }

        friend inline bool operator==(const Iterator& left, const Iterator& right)
        {
            return left.m_currentIndex == right.m_currentIndex;
        }

        friend inline bool operator!=(const Iterator& left, const Iterator& right)
        {
            return left.m_currentIndex != right.m_currentIndex;
        }

    private:
        friend ConstObjectSpan<T>;

        inline Iterator(const ConstObjectSpan& span, size_t currentIndex)
            : m_span(span)
            , m_currentIndex(currentIndex)
        {
        }

        // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
        const ConstObjectSpan& m_span;
        // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
        size_t m_currentIndex;
    };

    inline Iterator begin() const noexcept { return {*this, 0}; }
    inline Iterator end() const noexcept { return {*this, m_size}; }

private:
    friend ObjectArray<T>;

    inline ConstObjectSpan(jsize size, ProtectedArrayType<T> javaArrayRef)
        : m_size(static_cast<size_t>(size))
        , m_javaArrayRef(std::move(javaArrayRef))
    {
    }

    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const size_t m_size;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
    ProtectedArrayType<T> m_javaArrayRef;
};

template <typename T> class ObjectArray<T, EnableIfObjectType<T>> final {
public:
    // In order to allow flexible Java classes caching, you cannot build an objects array directly.
    // To create an objects array you can call:
    // - ObjectArray<T>(TypedClass<T>().createArray(size)) to create an array of null objects of type T.
    // - ObjectArray<T>(TypedClass<T>().getConstructor<Type1, Type2, ...>().createNewArray(size, value1, value2, ...))
    //   to create an array of valid instances of type T initialized with (value1, value2, ...) parameters.

    ObjectArray(const ArrayType<T>& javaArray, bool useGlobalRef = false)
    {
        if (javaArray != nullptr)
            m_javaArrayRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), javaArray, useGlobalRef);
    }

    // WARNING: the moved javaArray MUST be a local reference
    ObjectArray(ArrayType<T>&& javaArray, bool useGlobalRef = false)
    {
        if (javaArray != nullptr)
            m_javaArrayRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), std::move(javaArray), useGlobalRef);
    }

    ObjectArray(ProtectedArrayType<T> javaArrayRef)
        : m_javaArrayRef(std::move(javaArrayRef))
    {
    }

    bool operator==(const ObjectArray<T>& other) const noexcept
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

    ConstObjectSpan<T> getReadOnlyContent() const
    {
        if (m_javaArrayRef == nullptr)
            return {0, {}};

        auto* env = getCurrentThreadJNIEnv();
        const jsize size = env->GetArrayLength(m_javaArrayRef.get());
        checkJavaException(env);
        if (size <= 0)
            return {0, {}};

        return {size, m_javaArrayRef};
    }

    void setValue(size_t index, const T& value)
    {
        if (m_javaArrayRef == nullptr)
            throw std::out_of_range("Invalid index");

        auto* env = getCurrentThreadJNIEnv();
        env->SetObjectArrayElement(reinterpret_cast<jobjectArray>(static_cast<jarray>(m_javaArrayRef.get())),
            static_cast<jsize>(index), value);
        checkJavaException(env);
    }

private:
    ProtectedArrayType<T> m_javaArrayRef;
};

} // namespace JNI
