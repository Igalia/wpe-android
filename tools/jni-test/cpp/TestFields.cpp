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

#include "TestFields.h"

#include <cassert>
#include <cstring>

using namespace Wpe::Android;

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestFields> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::Constructor<JNITestFields()> m_constructor = getConstructor<>();

    const JNI::Method<void()> m_checkFieldsAfterModification = getMethod<void()>("checkFieldsAfterModification");

    const JNI::Field<jboolean> m_booleanValue = getField<jboolean>("booleanValue");
    const JNI::Field<jbyte> m_byteValue = getField<jbyte>("byteValue");
    const JNI::Field<jchar> m_charValue = getField<jchar>("charValue");
    const JNI::Field<jshort> m_shortValue = getField<jshort>("shortValue");
    const JNI::Field<jint> m_intValue = getField<jint>("intValue");
    const JNI::Field<jlong> m_longValue = getField<jlong>("longValue");
    const JNI::Field<jfloat> m_floatValue = getField<jfloat>("floatValue");
    const JNI::Field<jdouble> m_doubleValue = getField<jdouble>("doubleValue");
    const JNI::Field<jobject> m_objectValue = getField<jobject>("objectValue");
    const JNI::Field<jclass> m_classValue = getField<jclass>("classValue");
    const JNI::Field<jthrowable> m_throwableValue = getField<jthrowable>("throwableValue");
    const JNI::Field<jstring> m_stringValue = getField<jstring>("stringValue");
    const JNI::Field<JNITestFields> m_thisValue = getField<JNITestFields>("thisValue");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

TestFields::TestFields()
    : m_javaInstance(getJNIClassCache().m_constructor.createNewInstance())
{
}

void TestFields::checkFieldsAfterModification() const
{
    getJNIClassCache().m_checkFieldsAfterModification.invoke(m_javaInstance.get());
}

void TestFields::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    TestFields test;
    assert(test.m_javaInstance != nullptr);
    assert(env->IsInstanceOf(test.m_javaInstance.get(), getJNIClassCache()));

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    assert(getJNIClassCache().m_booleanValue.getValue(test.m_javaInstance.get()) == JNI_TRUE);
    assert(getJNIClassCache().m_byteValue.getValue(test.m_javaInstance.get()) == 1);
    assert(getJNIClassCache().m_charValue.getValue(test.m_javaInstance.get()) == 'A');
    assert(getJNIClassCache().m_shortValue.getValue(test.m_javaInstance.get()) == 2);
    assert(getJNIClassCache().m_intValue.getValue(test.m_javaInstance.get()) == 3);
    assert(getJNIClassCache().m_longValue.getValue(test.m_javaInstance.get()) == 4);
    assert(getJNIClassCache().m_floatValue.getValue(test.m_javaInstance.get()) == 5.2F);
    assert(getJNIClassCache().m_doubleValue.getValue(test.m_javaInstance.get()) == 2.4);
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    auto obj = getJNIClassCache().m_objectValue.getValue(test.m_javaInstance.get());
    assert(env->IsInstanceOf(obj.get(), JNI::TypedClass<jobject>()));

    auto classValue = getJNIClassCache().m_classValue.getValue(test.m_javaInstance.get());
    assert(env->IsSameObject(classValue.get(), getJNIClassCache()));

    auto throwable = getJNIClassCache().m_throwableValue.getValue(test.m_javaInstance.get());
    assert(env->IsInstanceOf(throwable.get(), JNI::TypedClass<jthrowable>()));

    auto javaStr = getJNIClassCache().m_stringValue.getValue(test.m_javaInstance.get());
    assert(env->IsInstanceOf(javaStr.get(), JNI::TypedClass<jstring>()));
    JNI::String str(javaStr);
    assert(str.getLength() == 4);
    assert(strcmp(str.getContent().get(), "test") == 0);

    auto thiz = getJNIClassCache().m_thisValue.getValue(test.m_javaInstance.get());
    assert(env->IsSameObject(thiz.get(), test.m_javaInstance.get()));

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    getJNIClassCache().m_booleanValue.setValue(test.m_javaInstance.get(), JNI_FALSE);
    getJNIClassCache().m_byteValue.setValue(test.m_javaInstance.get(), 2);
    getJNIClassCache().m_charValue.setValue(test.m_javaInstance.get(), 'B');
    getJNIClassCache().m_shortValue.setValue(test.m_javaInstance.get(), 3);
    getJNIClassCache().m_intValue.setValue(test.m_javaInstance.get(), 4);
    getJNIClassCache().m_longValue.setValue(test.m_javaInstance.get(), 5);
    getJNIClassCache().m_floatValue.setValue(test.m_javaInstance.get(), 2.5F);
    getJNIClassCache().m_doubleValue.setValue(test.m_javaInstance.get(), 4.2);
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    getJNIClassCache().m_objectValue.setValue(test.m_javaInstance.get(), nullptr);
    getJNIClassCache().m_classValue.setValue(test.m_javaInstance.get(), nullptr);
    getJNIClassCache().m_throwableValue.setValue(test.m_javaInstance.get(), nullptr);
    getJNIClassCache().m_stringValue.setValue(test.m_javaInstance.get(), JNI::String("changed"));
    getJNIClassCache().m_thisValue.setValue(test.m_javaInstance.get(), nullptr);

    test.checkFieldsAfterModification();
}
