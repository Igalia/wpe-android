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
    inline operator jstring() const noexcept { return m_javaStringRef.get(); }

    size_t getLength() const;
    std::shared_ptr<const char> getContent() const;

private:
    ProtectedType<jstring> m_javaStringRef {};
};

} // namespace JNI
