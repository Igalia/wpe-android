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

#include "Environment.h"

#include "Logging.h"

#include <cassert>
#include <cstdlib>

bool Environment::configureEnvironment(jstringArray envStringsArray) noexcept
{
    if (envStringsArray == nullptr)
        return true;

    try {
        auto content = JNI::ObjectArray<jstring>(envStringsArray).getReadOnlyContent();
        const size_t size = content.getSize();

        assert(size % 2 == 0);
        for (size_t i = 1; i < size; i += 2) {
            auto name = JNI::String(content[i - 1]);
            auto value = JNI::String(content[i]);
            setenv(name.getContent().get(), value.getContent().get(), 1); // NOLINT(concurrency-mt-unsafe)
        }

        return true;
    } catch (...) {
        Logging::logError("Cannot configure native environment (JNI environment error)");
        return false;
    }
}
