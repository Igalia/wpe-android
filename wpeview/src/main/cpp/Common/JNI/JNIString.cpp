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

#include "JNIString.h"

#include "JNIEnv.h"

JNI::String::String(const char* str, bool useGlobalRef)
{
    if (str == nullptr)
        return;

    auto* env = getCurrentThreadJNIEnv();
    jstring localString = env->NewStringUTF(str);
    if (localString == nullptr) {
        clearJavaException(env);
        Logging::logError("Cannot create a Java string");
        return;
    }

    m_javaStringRef = createTypedProtectedRef(env, std::move(localString), useGlobalRef);
}

JNI::String::String(const jstring& str, bool useGlobalRef)
{
    if (str != nullptr)
        m_javaStringRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), str, useGlobalRef);
}

JNI::String::String(jstring&& str, bool useGlobalRef)
{
    if (str != nullptr)
        m_javaStringRef = createTypedProtectedRef(getCurrentThreadJNIEnv(), std::move(str), useGlobalRef);
}

bool JNI::String::operator==(const String& other) const noexcept
{
    jstring thisRef = m_javaStringRef.get();
    jstring otherRef = other.m_javaStringRef.get();

    if ((thisRef == nullptr) || (otherRef == nullptr))
        return (thisRef == otherRef);

    return (getCurrentThreadJNIEnv()->IsSameObject(thisRef, otherRef) == JNI_TRUE);
}

size_t JNI::String::getLength() const
{
    if (m_javaStringRef == nullptr)
        return 0;

    auto* env = getCurrentThreadJNIEnv();
    const jsize length = env->GetStringUTFLength(m_javaStringRef.get());
    if (clearJavaException(env))
        return 0;
    return (length > 0) ? static_cast<size_t>(length) : 0;
}

std::shared_ptr<const char> JNI::String::getContent() const
{
    _jstring* const javaStringRef = m_javaStringRef.get();
    if (javaStringRef == nullptr)
        return {};

    auto* env = getCurrentThreadJNIEnv();
    const char* str = env->GetStringUTFChars(javaStringRef, nullptr);
    if (str == nullptr) {
        clearJavaException(env);
        Logging::logError("Cannot get Java string content");
        return {};
    }

    return {str, [javaStringRef](const char* ptr) {
                if (auto* env = tryGetCurrentThreadJNIEnv())
                    env->ReleaseStringUTFChars(javaStringRef, ptr);
            }};
}
