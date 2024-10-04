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
template <typename T, typename = void> class TypedClass;

template <typename T, typename = void> class Constructor;

template <typename Ret, typename... Params> class Constructor<Ret(Params...), EnableIfObjectType<Ret>> final {
public:
    ProtectedType<Ret> createNewInstance(Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();
        jobject obj = env->NewObject(m_javaClassRef.get(), m_methodId, params...);
        if (obj == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot create instance of a Java object");
        }

        return createTypedProtectedRef(env, std::move(reinterpret_cast<Ret>(obj)));
    }

    ProtectedArrayType<Ret> createNewArray(size_t size, Params... params) const
    {
        auto* env = getCurrentThreadJNIEnv();

        jobject initObj = nullptr;
        if (size > 0) {
            initObj = env->NewObject(m_javaClassRef.get(), m_methodId, params...);
            if (initObj == nullptr) {
                checkJavaException(env);
                throw std::runtime_error("Cannot create instance of a Java object");
            }
        }

        jobjectArray objArray = env->NewObjectArray(static_cast<jsize>(size), m_javaClassRef.get(), initObj);
        if (initObj != nullptr)
            env->DeleteLocalRef(initObj);

        if (objArray == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot create array of Java objects");
        }

        return createTypedProtectedRef(env, std::move(reinterpret_cast<ArrayType<Ret>>(objArray)));
    }

private:
    friend Class;
    friend TypedClass<Ret>;

    Constructor(ProtectedType<jclass> javaClassRef)
        : m_javaClassRef(std::move(javaClassRef))
    {
        auto* env = getCurrentThreadJNIEnv();
        m_methodId = env->GetMethodID(m_javaClassRef.get(), "<init>", FunctionSignature<void(Params...)>::value.data());
        if (m_methodId == nullptr) {
            checkJavaException(env);
            throw std::runtime_error("Cannot find constructor in Java class");
        }
    }

    ProtectedType<jclass> m_javaClassRef;
    jmethodID m_methodId;
};

} // namespace JNI
