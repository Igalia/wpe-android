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

class Class;

template <typename T, bool isStatic, typename = void> class GenericMethod;

template <typename... Params, bool isStatic> class GenericMethod<void(Params...), isStatic> final {
public:
    template <typename U = void> std::enable_if_t<!isStatic, U> invoke(jobject obj, Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        env->CallVoidMethod(obj, m_methodId, params...);
        checkJavaException(env);
    }

    template <typename U = void> std::enable_if_t<isStatic, U> invoke(Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        env->CallStaticVoidMethod(m_javaClassRef.get(), m_methodId, params...);
        checkJavaException(env);
    }

private:
    friend Class;

    GenericMethod(ProtectedType<jclass> javaClassRef, const char* methodName)
        : m_javaClassRef(std::move(javaClassRef))
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (isStatic) {
            m_methodId = env->GetStaticMethodID(
                m_javaClassRef.get(), methodName, FunctionSignature<void(Params...)>::value.data());
        } else {
            m_methodId
                = env->GetMethodID(m_javaClassRef.get(), methodName, FunctionSignature<void(Params...)>::value.data());
        }
        if (m_methodId == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot find method in Java class");
        }
    }

    ProtectedType<jclass> m_javaClassRef;
    jmethodID m_methodId;
};

template <typename Ret, typename... Params, bool isStatic>
class GenericMethod<Ret(Params...), isStatic, EnableIfScalarType<Ret>> final {
public:
    template <typename U = Ret> std::enable_if_t<!isStatic, U> invoke(jobject obj, Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        Ret ret;
        if constexpr (std::is_same_v<Ret, jboolean>) {
            ret = env->CallBooleanMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jbyte>) {
            ret = env->CallByteMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jchar>) {
            ret = env->CallCharMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jshort>) {
            ret = env->CallShortMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jint>) {
            ret = env->CallIntMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jlong>) {
            ret = env->CallLongMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jfloat>) {
            ret = env->CallFloatMethod(obj, m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jdouble>) {
            ret = env->CallDoubleMethod(obj, m_methodId, params...);
        } else {
            static_assert(!std::is_same_v<Ret, Ret>, "Invalid JNI scalar type");
        }
        checkJavaException(env);
        return ret;
    }

    template <typename U = Ret> std::enable_if_t<isStatic, U> invoke(Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        Ret ret;
        if constexpr (std::is_same_v<Ret, jboolean>) {
            ret = env->CallStaticBooleanMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jbyte>) {
            ret = env->CallStaticByteMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jchar>) {
            ret = env->CallStaticCharMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jshort>) {
            ret = env->CallStaticShortMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jint>) {
            ret = env->CallStaticIntMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jlong>) {
            ret = env->CallStaticLongMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jfloat>) {
            ret = env->CallStaticFloatMethod(m_javaClassRef.get(), m_methodId, params...);
        } else if constexpr (std::is_same_v<Ret, jdouble>) {
            ret = env->CallStaticDoubleMethod(m_javaClassRef.get(), m_methodId, params...);
        } else {
            static_assert(!std::is_same_v<Ret, Ret>, "Invalid JNI scalar type");
        }
        checkJavaException(env);
        return ret;
    }

private:
    friend Class;

    GenericMethod(ProtectedType<jclass> javaClassRef, const char* methodName)
        : m_javaClassRef(std::move(javaClassRef))
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (isStatic) {
            m_methodId = env->GetStaticMethodID(
                m_javaClassRef.get(), methodName, FunctionSignature<Ret(Params...)>::value.data());
        } else {
            m_methodId
                = env->GetMethodID(m_javaClassRef.get(), methodName, FunctionSignature<Ret(Params...)>::value.data());
        }
        if (m_methodId == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot find method in Java class");
        }
    }

    ProtectedType<jclass> m_javaClassRef;
    jmethodID m_methodId;
};

template <typename Ret, typename... Params, bool isStatic>
class GenericMethod<Ret(Params...), isStatic, EnableIfObjectType<Ret>> final {
public:
    template <typename U = Ret>
    std::enable_if_t<!isStatic, ProtectedType<U>> invoke(jobject obj, Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        jobject ret = env->CallObjectMethod(obj, m_methodId, params...);
        checkJavaException(env);
        if (ret == nullptr)
            return {};

        return createTypedProtectedRef(env, std::move(reinterpret_cast<Ret>(ret)));
    }

    template <typename U = Ret> std::enable_if_t<isStatic, ProtectedType<U>> invoke(Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        jobject ret = env->CallStaticObjectMethod(m_javaClassRef.get(), m_methodId, params...);
        checkJavaException(env);
        if (ret == nullptr)
            return {};

        return createTypedProtectedRef(env, std::move(reinterpret_cast<Ret>(ret)));
    }

private:
    friend Class;

    GenericMethod(ProtectedType<jclass> javaClassRef, const char* methodName)
        : m_javaClassRef(std::move(javaClassRef))
    {
        auto* env = getCurrentThreadJNIEnv();
        if constexpr (isStatic) {
            m_methodId = env->GetStaticMethodID(
                m_javaClassRef.get(), methodName, FunctionSignature<Ret(Params...)>::value.data());
        } else {
            m_methodId
                = env->GetMethodID(m_javaClassRef.get(), methodName, FunctionSignature<Ret(Params...)>::value.data());
        }
        if (m_methodId == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot find method in Java class");
        }
    }

    ProtectedType<jclass> m_javaClassRef;
    jmethodID m_methodId;
};

template <typename T> using Method = GenericMethod<T, false>;
template <typename T> using StaticMethod = GenericMethod<T, true>;

} // namespace JNI
