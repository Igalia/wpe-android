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

#include "TestScalarArrays.h"

#include <cassert>

using namespace Wpe::Android;

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestScalarArrays> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::StaticMethod<jbooleanArray()> m_getBooleanArray = getStaticMethod<jbooleanArray()>("getBooleanArray");
    const JNI::StaticMethod<jbyteArray()> m_getByteArray = getStaticMethod<jbyteArray()>("getByteArray");
    const JNI::StaticMethod<jcharArray()> m_getCharArray = getStaticMethod<jcharArray()>("getCharArray");
    const JNI::StaticMethod<jshortArray()> m_getShortArray = getStaticMethod<jshortArray()>("getShortArray");
    const JNI::StaticMethod<jintArray()> m_getIntArray = getStaticMethod<jintArray()>("getIntArray");
    const JNI::StaticMethod<jlongArray()> m_getLongArray = getStaticMethod<jlongArray()>("getLongArray");
    const JNI::StaticMethod<jfloatArray()> m_getFloatArray = getStaticMethod<jfloatArray()>("getFloatArray");
    const JNI::StaticMethod<jdoubleArray()> m_getDoubleArray = getStaticMethod<jdoubleArray()>("getDoubleArray");

    const JNI::StaticMethod<void(jbooleanArray)> m_setBooleanArray
        = getStaticMethod<void(jbooleanArray)>("setBooleanArray");
    const JNI::StaticMethod<void(jbyteArray)> m_setByteArray = getStaticMethod<void(jbyteArray)>("setByteArray");
    const JNI::StaticMethod<void(jcharArray)> m_setCharArray = getStaticMethod<void(jcharArray)>("setCharArray");
    const JNI::StaticMethod<void(jshortArray)> m_setShortArray = getStaticMethod<void(jshortArray)>("setShortArray");
    const JNI::StaticMethod<void(jintArray)> m_setIntArray = getStaticMethod<void(jintArray)>("setIntArray");
    const JNI::StaticMethod<void(jlongArray)> m_setLongArray = getStaticMethod<void(jlongArray)>("setLongArray");
    const JNI::StaticMethod<void(jfloatArray)> m_setFloatArray = getStaticMethod<void(jfloatArray)>("setFloatArray");
    const JNI::StaticMethod<void(jdoubleArray)> m_setDoubleArray
        = getStaticMethod<void(jdoubleArray)>("setDoubleArray");

    const JNI::StaticField<jintArray> m_staticIntArray = getStaticField<jintArray>("staticIntArray");
    const JNI::StaticMethod<void()> m_checkStaticIntArrayAfterModification
        = getStaticMethod<void()>("checkStaticIntArrayAfterModification");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}

void testBooleanArray()
{
    auto javaArray = getJNIClassCache().m_getBooleanArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jboolean>(javaArray);
    assert(arrayWrapper.getSize() == 2);

    auto span = arrayWrapper.getReadOnlyContent();
    assert(span.getSize() == 2);
    assert(span[0] == JNI_FALSE);
    assert(span[1] == JNI_TRUE);

    auto newJavaArray = JNI::ScalarArray<jboolean>(2);
    assert(newJavaArray.getSize() == 2);

    auto writableSpan = newJavaArray.getContent();
    assert(writableSpan.getSize() == 2);

    writableSpan[0] = JNI_TRUE;
    writableSpan[1] = JNI_FALSE;
    writableSpan.commit();
    assert(writableSpan.getSize() == 0);

    getJNIClassCache().m_setBooleanArray.invoke(newJavaArray);
}

void testByteArray()
{
    auto javaArray = getJNIClassCache().m_getByteArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jbyte>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    jbyte value = 1;
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == value++); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jbyte>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = --value;

    getJNIClassCache().m_setByteArray.invoke(newJavaArray);
}

void testCharArray()
{
    auto javaArray = getJNIClassCache().m_getCharArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jchar>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    jchar value = 'A';
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == value++); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jchar>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = --value;

    getJNIClassCache().m_setCharArray.invoke(newJavaArray);
}

void testShortArray()
{
    auto javaArray = getJNIClassCache().m_getShortArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jshort>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    jshort value = 4;
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == value++); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jshort>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = --value;

    getJNIClassCache().m_setShortArray.invoke(newJavaArray);
}

void testIntArray()
{
    auto javaArray = getJNIClassCache().m_getIntArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jint>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    jint value = 7; // NOLINT(cppcoreguidelines-avoid-magic-numbers)
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == value++); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jint>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = --value;

    getJNIClassCache().m_setIntArray.invoke(newJavaArray);
}

void testLongArray()
{
    auto javaArray = getJNIClassCache().m_getLongArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jlong>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    jlong value = 10; // NOLINT(cppcoreguidelines-avoid-magic-numbers)
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == value++); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jlong>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = --value;

    getJNIClassCache().m_setLongArray.invoke(newJavaArray);
}

void testFloatArray()
{
    auto javaArray = getJNIClassCache().m_getFloatArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jfloat>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    static constexpr jfloat refValues[] = {1.1F, 2.2F, 3.3F};
    int idx = 0;
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == refValues[idx++]); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jfloat>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = refValues[--idx];

    getJNIClassCache().m_setFloatArray.invoke(newJavaArray);
}

void testDoubleArray()
{
    auto javaArray = getJNIClassCache().m_getDoubleArray.invoke();
    const auto arrayWrapper = JNI::ScalarArray<jdouble>(javaArray);
    assert(arrayWrapper.getSize() == 3);

    static constexpr jdouble refValues[] = {4.4, 5.5, 6.6};
    int idx = 0;
    for (const auto& arrayValue : arrayWrapper.getReadOnlyContent())
        assert(arrayValue == refValues[idx++]); // NOLINT(bugprone-assert-side-effect)

    auto newJavaArray = JNI::ScalarArray<jdouble>(3);
    assert(newJavaArray.getSize() == 3);
    for (auto& arrayValue : newJavaArray.getContent())
        arrayValue = refValues[--idx];

    getJNIClassCache().m_setDoubleArray.invoke(newJavaArray);
}
} // namespace

void TestScalarArrays::checkStaticIntArrayAfterModification()
{
    getJNIClassCache().m_checkStaticIntArrayAfterModification.invoke();
}

void TestScalarArrays::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    testBooleanArray();
    testByteArray();
    testCharArray();
    testShortArray();
    testIntArray();
    testLongArray();
    testFloatArray();
    testDoubleArray();

    auto javaArray = getJNIClassCache().m_staticIntArray.getValue();
    auto arrayWrapper = JNI::ScalarArray<jint>(javaArray);
    assert(arrayWrapper.getSize() == 4);

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    jint value = 20;
    for (auto& arrayValue : arrayWrapper.getContent()) {
        assert(arrayValue == value);
        value += 10;
        arrayValue += 5;
    }
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    checkStaticIntArrayAfterModification();
}
