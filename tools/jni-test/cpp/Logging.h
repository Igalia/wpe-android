/**
 * Copyright (C) 2026 Igalia S.L.
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

// Host version of wpeview/src/main/cpp/Common/Logging.h used by the JNI layer
// when it is built for the jni-test harness: logs go to stderr.

#include <cstdio>
#include <utility>

// NOLINTNEXTLINE(cppcoreguidelines-macro-usage)
#define UNUSED_PARAM(variable) (void)(variable)

namespace Logging {

template <typename... Args> constexpr void logError(const char* format, Args&&... args) noexcept
{
    if constexpr (sizeof...(Args) == 0) {
        std::fprintf(stderr, "%s\n", format);
    } else {
        std::fprintf(stderr, format, std::forward<Args>(args)...);
        std::fputc('\n', stderr);
    }
}

template <typename... Args> constexpr void logVerbose(const char* format, Args&&... args) noexcept
{
    logError(format, std::forward<Args>(args)...);
}

template <typename... Args> constexpr void logDebug(const char* format, Args&&... args) noexcept
{
    logError(format, std::forward<Args>(args)...);
}

} // namespace Logging
