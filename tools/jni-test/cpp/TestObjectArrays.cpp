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

#include "TestObjectArrays.h"

#include <cassert>
#include <cstring>

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestObjectArrays> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::StaticMethod<jstringArray()> m_getStringArray = getStaticMethod<jstringArray()>("getStringArray");
    const JNI::StaticMethod<void(jstringArray)> m_setStringArray
        = getStaticMethod<void(jstringArray)>("setStringArray");

    const JNI::StaticField<jstringArray> m_staticStringArray = getStaticField<jstringArray>("staticStringArray");
    const JNI::StaticMethod<void()> m_checkStaticStringArrayAfterModification
        = getStaticMethod<void()>("checkStaticStringArrayAfterModification");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

void TestObjectArrays::checkStaticStringArrayAfterModification()
{
    getJNIClassCache().m_checkStaticStringArrayAfterModification.invoke();
}

void TestObjectArrays::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    const auto jstringTypedClass = JNI::TypedClass<jstring>();

    auto javaArray = getJNIClassCache().m_getStringArray.invoke();
    auto arrayWrapper = JNI::ObjectArray<jstring>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    static constexpr const char* callRefValues[] = {"call1", "call2", "call3"};
    int idx = 0;
    for (auto javaStr : arrayWrapper.getReadOnlyContent()) {
        assert(env->IsInstanceOf(javaStr.get(), jstringTypedClass));
        // NOLINTNEXTLINE(bugprone-assert-side-effect)
        assert(strcmp(JNI::String(javaStr).getContent().get(), callRefValues[idx++]) == 0);
    }

    auto newJavaArray = JNI::ObjectArray<jstring>(jstringTypedClass.createArray(3));
    assert(newJavaArray.getSize() == 3);
    newJavaArray.setValue(0, JNI::String("resp1"));
    newJavaArray.setValue(1, JNI::String("resp2"));
    newJavaArray.setValue(2, JNI::String("resp3"));
    getJNIClassCache().m_setStringArray.invoke(newJavaArray);

    javaArray = getJNIClassCache().m_staticStringArray.getValue();
    arrayWrapper = JNI::ObjectArray<jstring>(javaArray);
    assert(arrayWrapper.getSize() == 4);

    static constexpr const char* testRefValues[] = {"test1", "test2", "test3", "test4"};
    idx = 0;
    for (auto javaStr : arrayWrapper.getReadOnlyContent()) {
        assert(env->IsInstanceOf(javaStr.get(), jstringTypedClass));
        // NOLINTNEXTLINE(bugprone-assert-side-effect)
        assert(strcmp(JNI::String(javaStr).getContent().get(), testRefValues[idx++]) == 0);
    }

    arrayWrapper.setValue(0, JNI::String("changed1"));
    arrayWrapper.setValue(1, JNI::String("changed2"));
    arrayWrapper.setValue(2, JNI::String("changed3"));
    arrayWrapper.setValue(3, JNI::String("changed4"));
    checkStaticStringArrayAfterModification();
}
