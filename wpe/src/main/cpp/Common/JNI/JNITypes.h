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

#include <jni.h>
#include <memory>
#include <string_view>
#include <utility>

// NOLINTBEGIN(readability-identifier-naming, bugprone-reserved-identifier)
class _jclassArray : public _jarray {};
class _jthrowableArray : public _jarray {};
class _jstringArray : public _jarray {};
// NOLINTEND(readability-identifier-naming, bugprone-reserved-identifier)

using jclassArray = _jclassArray*;
using jthrowableArray = _jthrowableArray*;
using jstringArray = _jstringArray*;

// NOLINTNEXTLINE(cppcoreguidelines-macro-usage)
#define DECLARE_JNI_CLASS_SIGNATURE(TYPE, JAVA_CLASS_NAME)                                                             \
    class _##TYPE : public _jobject {};                                                                                \
    typedef _##TYPE*(TYPE);                                                                                            \
    class _##TYPE##Array : public _jarray {};                                                                          \
    typedef _##TYPE##Array* TYPE##Array;                                                                               \
    template <> struct JNI::TypeSignature<TYPE> {                                                                      \
        using ComponentType = TYPE;                                                                                    \
        using ArrayType = TYPE##Array;                                                                                 \
        static constexpr std::string_view value = "L" JAVA_CLASS_NAME ";";                                             \
        static constexpr bool isArray = false;                                                                         \
        static constexpr const char* componentClassName = JAVA_CLASS_NAME;                                             \
    };                                                                                                                 \
    template <> struct JNI::TypeSignature<TYPE##Array> {                                                               \
        using ComponentType = TYPE;                                                                                    \
        using ArrayType = TYPE##Array;                                                                                 \
        static constexpr std::string_view value = "[L" JAVA_CLASS_NAME ";";                                            \
        static constexpr bool isArray = true;                                                                          \
        static constexpr const char* componentClassName = JAVA_CLASS_NAME;                                             \
    }

namespace JNI {

template <typename T> struct TypeSignature;

template <typename T> using ComponentType = typename TypeSignature<T>::ComponentType;
template <typename T> using ArrayType = typename TypeSignature<T>::ArrayType;

template <typename T, typename U = void>
using EnableIfObjectType
    = std::enable_if_t<std::is_pointer_v<T> && std::is_base_of_v<_jobject, std::remove_pointer_t<T>>, U>;
template <typename T, typename U = void> using EnableIfScalarType = std::enable_if_t<!std::is_pointer_v<T>, U>;

template <typename T, typename = EnableIfObjectType<T>> using ProtectedType = std::shared_ptr<std::remove_pointer_t<T>>;
template <typename T> using ProtectedArrayType = ProtectedType<ArrayType<T>>;

template <> struct TypeSignature<void> {
    using ComponentType = void;
    using ArrayType = void;
    static constexpr std::string_view value = "V";
    static constexpr bool isArray = false;
    static constexpr const char* componentClassName = nullptr;
};

// NOLINTNEXTLINE(cppcoreguidelines-macro-usage)
#define DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(TYPE, SIGNATURE, JAVA_CLASS_NAME)                                          \
    template <> struct TypeSignature<TYPE> {                                                                           \
        using ComponentType = TYPE;                                                                                    \
        using ArrayType = TYPE##Array;                                                                                 \
        static constexpr std::string_view value = SIGNATURE;                                                           \
        static constexpr bool isArray = false;                                                                         \
        static constexpr const char* componentClassName = JAVA_CLASS_NAME;                                             \
    };                                                                                                                 \
    template <> struct TypeSignature<TYPE##Array> {                                                                    \
        using ComponentType = TYPE;                                                                                    \
        using ArrayType = TYPE##Array;                                                                                 \
        static constexpr std::string_view value = "[" SIGNATURE;                                                       \
        static constexpr bool isArray = true;                                                                          \
        static constexpr const char* componentClassName = JAVA_CLASS_NAME;                                             \
    }

DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jboolean, "Z", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jbyte, "B", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jchar, "C", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jshort, "S", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jint, "I", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jlong, "J", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jfloat, "F", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jdouble, "D", nullptr);
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jobject, "Ljava/lang/Object;", "java/lang/Object");
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jclass, "Ljava/lang/Class;", "java/lang/Class");
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jthrowable, "Ljava/lang/Throwable;", "java/lang/Throwable");
DECLARE_JNI_INTERNAL_TYPE_SIGNATURE(jstring, "Ljava/lang/String;", "java/lang/String");

template <typename T> struct FunctionSignature;

template <typename Ret, typename... Params> struct FunctionSignature<Ret(Params...)> {
private:
    static constexpr std::string_view openParenthesis = "(";
    static constexpr std::string_view closeParenthesis = ")";

    template <const std::string_view& leftStr, typename LeftIdx, const std::string_view& rightStr, typename RightIdx>
    struct ConcatenateStrings;
    template <const std::string_view& leftStr, size_t... leftIdx, const std::string_view& rightStr, size_t... rightIdx>
    struct ConcatenateStrings<leftStr, std::index_sequence<leftIdx...>, rightStr, std::index_sequence<rightIdx...>> {
        static constexpr char value[] {leftStr[leftIdx]..., rightStr[rightIdx]..., '\0'};
    };

    template <const std::string_view&... strs> struct SignatureBuilder;
    template <const std::string_view& leftStr, const std::string_view& rightStr>
    struct SignatureBuilder<leftStr, rightStr> {
        static constexpr std::string_view value = ConcatenateStrings<leftStr,
            std::make_index_sequence<leftStr.length()>, rightStr, std::make_index_sequence<rightStr.length()>>::value;
    };
    template <const std::string_view& leftStr, const std::string_view&... rightStrs>
    struct SignatureBuilder<leftStr, rightStrs...> {
        static constexpr std::string_view value
            = SignatureBuilder<leftStr, SignatureBuilder<rightStrs...>::value>::value;
    };

public:
    static constexpr std::string_view value = SignatureBuilder<openParenthesis, TypeSignature<Params>::value...,
        closeParenthesis, TypeSignature<Ret>::value>::value;
};

} // namespace JNI
