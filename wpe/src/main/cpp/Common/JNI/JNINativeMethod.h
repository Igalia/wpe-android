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

template <typename T, typename U, typename = void> struct GenericNativeMethod;

template <typename Ret, typename... Params, typename U>
struct GenericNativeMethod<Ret(Params...), U,
    std::enable_if_t<std::is_same_v<U, jobject> || std::is_same_v<U, jclass>>> {

    using FunctionPtrType = Ret (*)(JNIEnv*, U, Params...);

    static constexpr std::string_view signature = FunctionSignature<Ret(Params...)>::value;

    constexpr GenericNativeMethod(const char* name, FunctionPtrType function)
        : methodName(name)
        , nativeFunction(function)
    {
    }

    // NOLINTBEGIN(misc-non-private-member-variables-in-classes, readability-identifier-naming)
    const char* const methodName;
    const FunctionPtrType nativeFunction;
    // NOLINTEND(misc-non-private-member-variables-in-classes, readability-identifier-naming)
};

template <typename T> using NativeMethod = GenericNativeMethod<T, jobject>;
template <typename T> using StaticNativeMethod = GenericNativeMethod<T, jclass>;

} // namespace JNI
