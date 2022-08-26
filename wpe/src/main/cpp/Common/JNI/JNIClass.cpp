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

#include "JNIClass.h"

using namespace Wpe::Android;

JNI::Class::Class(jobject obj, bool useGlobalRef)
{
    if (obj == nullptr)
        throw std::runtime_error("Invalid null Java object");

    auto* env = getCurrentThreadJNIEnv();
    jclass klass = env->GetObjectClass(obj);
    if (klass == nullptr) {
        checkJavaException(env);
        throw std::runtime_error("Cannot fetch Java object class");
    }

    m_javaClassRef = createTypedProtectedRef(env, std::move(klass), useGlobalRef);
}

JNI::Class::Class(const char* javaClassName, bool useGlobalRef)
{
    if ((javaClassName == nullptr) || (javaClassName[0] == '\0'))
        throw std::runtime_error("Invalid Java class name");

    auto* env = getCurrentThreadJNIEnv();
    jclass klass = env->FindClass(javaClassName);
    if (klass == nullptr) {
        checkJavaException(env);
        throw std::runtime_error("Cannot fetch Java class from name");
    }

    m_javaClassRef = createTypedProtectedRef(env, std::move(klass), useGlobalRef);
}

JNI::Class::Class(const jclass& javaClass, bool useGlobalRef)
{
    if (javaClass == nullptr)
        throw std::runtime_error("Invalid null Java class");

    m_javaClassRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), javaClass, useGlobalRef);
}

JNI::Class::Class(jclass&& javaClass, bool useGlobalRef)
{
    if (javaClass == nullptr)
        throw std::runtime_error("Invalid null Java class");

    m_javaClassRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), std::move(javaClass), useGlobalRef);
}

JNI::ProtectedArrayType<jobject> JNI::Class::createArray(size_t size, jobject initObj) const
{
    auto* env = getCurrentThreadJNIEnv();
    jobjectArray objArray = env->NewObjectArray(static_cast<jsize>(size), m_javaClassRef.get(), initObj);
    if (objArray == nullptr) {
        checkJavaException(env);
        throw std::runtime_error("Cannot create array of Java objects");
    }

    return createTypedProtectedRef(env, std::move(objArray));
}

bool JNI::Class::operator==(const Class& other) const noexcept
{
    try {
        return (getCurrentThreadJNIEnv()->IsSameObject(m_javaClassRef.get(), other.m_javaClassRef.get()) == JNI_TRUE);
    } catch (...) {
        return false;
    }
}
