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

#include "JNIConstructor.h"
#include "JNIField.h"
#include "JNIMethod.h"
#include "JNINativeMethod.h"

namespace Wpe::Android::JNI {

class Class {
public:
    Class(jobject obj, bool useGlobalRef = false);
    Class(const char* javaClassName, bool useGlobalRef = false);
    Class(const jclass& javaClass, bool useGlobalRef = false);
    Class(jclass&& javaClass, bool useGlobalRef = false); // WARNING: the moved jclass MUST be a local reference
    Class(ProtectedType<jclass> javaClassRef)
        : m_javaClassRef(std::move(javaClassRef))
    {
    }

    ProtectedArrayType<jobject> createArray(size_t size, jobject initObj = nullptr) const;

    template <typename... Params> Constructor<jobject(Params...)> getConstructor() const { return {m_javaClassRef}; }

    template <typename T> Field<T> getField(const char* fieldName) const { return {m_javaClassRef, fieldName}; }
    template <typename T> Method<T> getMethod(const char* methodName) const { return {m_javaClassRef, methodName}; }

    template <typename T> StaticField<T> getStaticField(const char* fieldName) const
    {
        return {m_javaClassRef, fieldName};
    }
    template <typename T> StaticMethod<T> getStaticMethod(const char* methodName) const
    {
        return {m_javaClassRef, methodName};
    }

    template <typename... Args> void registerNativeMethods(const Args&... args) const
    {
        static_assert(sizeof...(Args) > 0, "You must specify at least one native method");

        // NOLINTBEGIN(cppcoreguidelines-pro-type-const-cast)
        const JNINativeMethod methods[sizeof...(Args)] = {{const_cast<char*>(args.methodName),
            const_cast<char*>(Args::signature.data()), reinterpret_cast<void*>(args.nativeFunction)}...};
        // NOLINTEND(cppcoreguidelines-pro-type-const-cast)

        auto* env = getCurrentThreadJNIEnv();
        if (env->RegisterNatives(m_javaClassRef.get(), methods, sizeof...(Args)) != JNI_OK) {
            checkJavaException(env);
            throw std::runtime_error("Cannot register native methods");
        }
    }

    bool operator==(const Class& other) const noexcept;
    inline operator jclass() const noexcept { return m_javaClassRef.get(); }

protected:
    ProtectedType<jclass> m_javaClassRef; // NOLINT(cppcoreguidelines-non-private-member-variables-in-classes)
};

template <typename T> class TypedClass<T, EnableIfObjectType<T>> : public Class {
public:
    static_assert((TypeSignature<T>::componentClassName != nullptr) && !TypeSignature<T>::isArray,
        "Undeclared Java class, call DECLARE_JNI_CLASS_SIGNATURE(TYPE, JAVA_CLASS_NAME) first");

    TypedClass(bool useGlobalRef = false)
        : Class(TypeSignature<T>::componentClassName, useGlobalRef)
    {
    }

    ProtectedArrayType<T> createArray(size_t size, T initObj = nullptr) const
    {
        auto* env = getCurrentThreadJNIEnv();
        jobjectArray objArray = env->NewObjectArray(static_cast<jsize>(size), m_javaClassRef.get(), initObj);
        if (objArray == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot create array of Java objects");
        }

        return createTypedProtectedRef(env, std::move(reinterpret_cast<ArrayType<T>>(objArray)));
    }

    template <typename... Params> Constructor<T(Params...)> getConstructor() const { return {m_javaClassRef}; }
};

} // namespace Wpe::Android::JNI
