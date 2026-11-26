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

#include <string>

namespace JNI {

class String final {
public:
    String(const char* str, bool useGlobalRef = false);
    String(const jstring& str, bool useGlobalRef = false);
    String(jstring&& str, bool useGlobalRef = false); // WARNING: the moved jstring MUST be a local reference
    String(ProtectedType<jstring> javaStringRef)
        : m_javaStringRef(std::move(javaStringRef))
    {
    }

    bool operator==(const String& other) const noexcept;
    operator jstring() const noexcept
    {
        return m_javaStringRef.get();
    }

    size_t getLength() const;
    std::shared_ptr<const char> getContent() const;

private:
    ProtectedType<jstring> m_javaStringRef;
};

// Standard UTF-8 for a Java string, which GetStringUTFChars does not give: it encodes a character outside the BMP, such
// as an emoji, as two surrogates. An unpaired surrogate, as when an input method splits an emoji, becomes U+FFFD rather
// than lose the whole text.
std::string toUTF8(JNIEnv* env, jstring text);

// A new local reference to a Java string of standard UTF-8, which NewStringUTF does not take for a character outside
// the BMP. A byte that does not start a valid sequence becomes U+FFFD.
jstring newStringFromUTF8(JNIEnv* env, const char* text);

} // namespace JNI
