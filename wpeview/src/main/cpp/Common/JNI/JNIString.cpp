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

#include <cstdint>
#include <utility>
#include <vector>

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

namespace {

constexpr uint32_t replacementCharacter = 0xFFFD;
constexpr uint32_t firstLeadSurrogate = 0xD800;
constexpr uint32_t firstTrailSurrogate = 0xDC00;
constexpr uint32_t lastTrailSurrogate = 0xDFFF;
constexpr uint32_t firstSupplementary = 0x10000;
constexpr uint32_t lastCodePoint = 0x10FFFF;

bool isLeadSurrogate(uint32_t unit)
{
    return unit >= firstLeadSurrogate && unit < firstTrailSurrogate;
}

bool isTrailSurrogate(uint32_t unit)
{
    return unit >= firstTrailSurrogate && unit <= lastTrailSurrogate;
}

void appendUTF8(std::string& text, uint32_t codePoint)
{
    if (codePoint < 0x80) {
        text += static_cast<char>(codePoint);
    } else if (codePoint < 0x800) {
        text += static_cast<char>(0xC0 | (codePoint >> 6));
        text += static_cast<char>(0x80 | (codePoint & 0x3F));
    } else if (codePoint < firstSupplementary) {
        text += static_cast<char>(0xE0 | (codePoint >> 12));
        text += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
        text += static_cast<char>(0x80 | (codePoint & 0x3F));
    } else {
        text += static_cast<char>(0xF0 | (codePoint >> 18));
        text += static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F));
        text += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
        text += static_cast<char>(0x80 | (codePoint & 0x3F));
    }
}

// The code point of the UTF-8 sequence at bytes[index], and its length, or the replacement character and 1 for a byte
// that does not start a valid, shortest sequence.
std::pair<uint32_t, size_t> decodeUTF8(const std::string& bytes, size_t index)
{
    const auto lead = static_cast<unsigned char>(bytes[index]);
    size_t length = 0;
    uint32_t codePoint = 0;
    uint32_t smallest = 0;
    if (lead < 0x80)
        return {lead, 1};
    if ((lead & 0xE0) == 0xC0) {
        length = 2;
        codePoint = lead & 0x1F;
        smallest = 0x80;
    } else if ((lead & 0xF0) == 0xE0) {
        length = 3;
        codePoint = lead & 0x0F;
        smallest = 0x800;
    } else if ((lead & 0xF8) == 0xF0) {
        length = 4;
        codePoint = lead & 0x07;
        smallest = firstSupplementary;
    } else {
        return {replacementCharacter, 1};
    }
    if (index + length > bytes.size())
        return {replacementCharacter, 1};
    for (size_t i = 1; i < length; i++) {
        const auto next = static_cast<unsigned char>(bytes[index + i]);
        if ((next & 0xC0) != 0x80)
            return {replacementCharacter, 1};
        codePoint = (codePoint << 6) | (next & 0x3F);
    }
    if (codePoint < smallest || codePoint > lastCodePoint
        || (codePoint >= firstLeadSurrogate && codePoint <= lastTrailSurrogate))
        return {replacementCharacter, 1};
    return {codePoint, length};
}

} // namespace

std::string JNI::toUTF8(JNIEnv* env, jstring text)
{
    const jsize length = env->GetStringLength(text);
    std::vector<jchar> units(static_cast<size_t>(length));
    if (length > 0)
        env->GetStringRegion(text, 0, length, units.data());
    std::string result;
    result.reserve(units.size());
    for (size_t i = 0; i < units.size(); i++) {
        uint32_t codePoint = units[i];
        if (isLeadSurrogate(codePoint) && i + 1 < units.size() && isTrailSurrogate(units[i + 1])) {
            codePoint
                = firstSupplementary + ((codePoint - firstLeadSurrogate) << 10) + (units[i + 1] - firstTrailSurrogate);
            i++;
        } else if (isLeadSurrogate(codePoint) || isTrailSurrogate(codePoint)) {
            codePoint = replacementCharacter;
        }
        appendUTF8(result, codePoint);
    }
    return result;
}

jstring JNI::newStringFromUTF8(JNIEnv* env, const char* text)
{
    const std::string bytes = text != nullptr ? text : "";
    std::vector<jchar> units;
    units.reserve(bytes.size());
    for (size_t i = 0; i < bytes.size();) {
        const auto [codePoint, length] = decodeUTF8(bytes, i);
        if (codePoint >= firstSupplementary) {
            const uint32_t offset = codePoint - firstSupplementary;
            units.push_back(static_cast<jchar>(firstLeadSurrogate + (offset >> 10)));
            units.push_back(static_cast<jchar>(firstTrailSurrogate + (offset & 0x3FF)));
        } else {
            units.push_back(static_cast<jchar>(codePoint));
        }
        i += length;
    }
    jstring result = env->NewString(units.data(), static_cast<jsize>(units.size()));
    if (result == nullptr)
        clearJavaException(env);
    return result;
}
