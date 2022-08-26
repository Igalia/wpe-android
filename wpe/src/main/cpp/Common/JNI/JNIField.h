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

namespace Wpe::Android::JNI {

class Class;

template <typename T, bool isStatic, typename = void> class GenericField;

template <typename T, bool isStatic> class GenericField<T, isStatic, EnableIfScalarType<T>> final {
public:
    template <typename U = T> std::enable_if_t<!isStatic, U> getValue(jobject obj) const
    {
        auto* env = getCurrentThreadJNIEnv();
        T ret;
        if constexpr (std::is_same_v<T, jboolean>) {
            ret = env->GetBooleanField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jbyte>) {
            ret = env->GetByteField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jchar>) {
            ret = env->GetCharField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jshort>) {
            ret = env->GetShortField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jint>) {
            ret = env->GetIntField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jlong>) {
            ret = env->GetLongField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jfloat>) {
            ret = env->GetFloatField(obj, m_fieldId);
        } else if constexpr (std::is_same_v<T, jdouble>) {
            ret = env->GetDoubleField(obj, m_fieldId);
        } else {
            static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
        }
        checkJavaException(env);
        return ret;
    }

    template <typename U = T> std::enable_if_t<isStatic, U> getValue() const
    {
        auto* env = getCurrentThreadJNIEnv();
        T ret;
        if constexpr (std::is_same_v<T, jboolean>) {
            ret = env->GetStaticBooleanField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jbyte>) {
            ret = env->GetStaticByteField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jchar>) {
            ret = env->GetStaticCharField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jshort>) {
            ret = env->GetStaticShortField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jint>) {
            ret = env->GetStaticIntField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jlong>) {
            ret = env->GetStaticLongField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jfloat>) {
            ret = env->GetStaticFloatField(m_javaClassRef.get(), m_fieldId);
        } else if constexpr (std::is_same_v<T, jdouble>) {
            ret = env->GetStaticDoubleField(m_javaClassRef.get(), m_fieldId);
        } else {
            static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
        }
        checkJavaException(env);
        return ret;
    }

    template <typename U = void> std::enable_if_t<!isStatic, U> setValue(jobject obj, const T& value) const
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (std::is_same_v<T, jboolean>) {
            env->SetBooleanField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jbyte>) {
            env->SetByteField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jchar>) {
            env->SetCharField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jshort>) {
            env->SetShortField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jint>) {
            env->SetIntField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jlong>) {
            env->SetLongField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jfloat>) {
            env->SetFloatField(obj, m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jdouble>) {
            env->SetDoubleField(obj, m_fieldId, value);
        } else {
            static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
        }
        checkJavaException(env);
    }

    template <typename U = void> std::enable_if_t<isStatic, U> setValue(const T& value) const
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (std::is_same_v<T, jboolean>) {
            env->SetStaticBooleanField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jbyte>) {
            env->SetStaticByteField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jchar>) {
            env->SetStaticCharField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jshort>) {
            env->SetStaticShortField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jint>) {
            env->SetStaticIntField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jlong>) {
            env->SetStaticLongField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jfloat>) {
            env->SetStaticFloatField(m_javaClassRef.get(), m_fieldId, value);
        } else if constexpr (std::is_same_v<T, jdouble>) {
            env->SetStaticDoubleField(m_javaClassRef.get(), m_fieldId, value);
        } else {
            static_assert(!std::is_same_v<T, T>, "Invalid JNI scalar type");
        }
        checkJavaException(env);
    }

private:
    friend Class;

    GenericField(ProtectedType<jclass> javaClassRef, const char* fieldName)
        : m_javaClassRef(std::move(javaClassRef))
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (isStatic) {
            m_fieldId = env->GetStaticFieldID(m_javaClassRef.get(), fieldName, TypeSignature<T>::value.data());
        } else {
            m_fieldId = env->GetFieldID(m_javaClassRef.get(), fieldName, TypeSignature<T>::value.data());
        }
        if (m_fieldId == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot find field in Java class");
        }
    }

    ProtectedType<jclass> m_javaClassRef;
    jfieldID m_fieldId;
};

template <typename T, bool isStatic> class GenericField<T, isStatic, EnableIfObjectType<T>> final {
public:
    template <typename U = T> std::enable_if_t<!isStatic, ProtectedType<U>> getValue(jobject obj) const
    {
        auto* env = getCurrentThreadJNIEnv();
        jobject ret = env->GetObjectField(obj, m_fieldId);
        checkJavaException(env);
        if (ret == nullptr)
            return {};

        return createTypedProtectedRef(env, std::move(reinterpret_cast<T>(ret)));
    }

    template <typename U = T> std::enable_if_t<isStatic, ProtectedType<U>> getValue() const
    {
        auto* env = getCurrentThreadJNIEnv();
        jobject ret = env->GetStaticObjectField(m_javaClassRef.get(), m_fieldId);
        checkJavaException(env);
        if (ret == nullptr)
            return {};

        return createTypedProtectedRef(env, std::move(reinterpret_cast<T>(ret)));
    }

    template <typename U = void> std::enable_if_t<!isStatic, U> setValue(jobject obj, const T& value) const
    {
        auto* env = getCurrentThreadJNIEnv();
        env->SetObjectField(obj, m_fieldId, value);
        checkJavaException(env);
    }

    template <typename U = void> std::enable_if_t<isStatic, U> setValue(const T& value) const
    {
        auto* env = getCurrentThreadJNIEnv();
        env->SetStaticObjectField(m_javaClassRef.get(), m_fieldId, value);
        checkJavaException(env);
    }

private:
    friend Class;

    GenericField(ProtectedType<jclass> javaClassRef, const char* fieldName)
        : m_javaClassRef(std::move(javaClassRef))
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (isStatic) {
            m_fieldId = env->GetStaticFieldID(m_javaClassRef.get(), fieldName, TypeSignature<T>::value.data());
        } else {
            m_fieldId = env->GetFieldID(m_javaClassRef.get(), fieldName, TypeSignature<T>::value.data());
        }
        if (m_fieldId == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot find field in Java class");
        }
    }

    ProtectedType<jclass> m_javaClassRef;
    jfieldID m_fieldId;
};

template <typename T> using Field = GenericField<T, false>;
template <typename T> using StaticField = GenericField<T, true>;

} // namespace Wpe::Android::JNI
