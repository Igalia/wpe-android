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

#include "TestStaticFields.h"

#include <cassert>
#include <cstring>

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestStaticFields> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::StaticMethod<void()> m_checkFieldsAfterModification
        = getStaticMethod<void()>("checkFieldsAfterModification");

    const JNI::StaticField<jboolean> m_booleanValue = getStaticField<jboolean>("booleanValue");
    const JNI::StaticField<jbyte> m_byteValue = getStaticField<jbyte>("byteValue");
    const JNI::StaticField<jchar> m_charValue = getStaticField<jchar>("charValue");
    const JNI::StaticField<jshort> m_shortValue = getStaticField<jshort>("shortValue");
    const JNI::StaticField<jint> m_intValue = getStaticField<jint>("intValue");
    const JNI::StaticField<jlong> m_longValue = getStaticField<jlong>("longValue");
    const JNI::StaticField<jfloat> m_floatValue = getStaticField<jfloat>("floatValue");
    const JNI::StaticField<jdouble> m_doubleValue = getStaticField<jdouble>("doubleValue");
    const JNI::StaticField<jobject> m_objectValue = getStaticField<jobject>("objectValue");
    const JNI::StaticField<jclass> m_classValue = getStaticField<jclass>("classValue");
    const JNI::StaticField<jthrowable> m_throwableValue = getStaticField<jthrowable>("throwableValue");
    const JNI::StaticField<jstring> m_stringValue = getStaticField<jstring>("stringValue");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

void TestStaticFields::checkFieldsAfterModification() { getJNIClassCache().m_checkFieldsAfterModification.invoke(); }

void TestStaticFields::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    assert(getJNIClassCache().m_booleanValue.getValue() == JNI_TRUE);
    assert(getJNIClassCache().m_byteValue.getValue() == 1);
    assert(getJNIClassCache().m_charValue.getValue() == 'A');
    assert(getJNIClassCache().m_shortValue.getValue() == 2);
    assert(getJNIClassCache().m_intValue.getValue() == 3);
    assert(getJNIClassCache().m_longValue.getValue() == 4);
    assert(getJNIClassCache().m_floatValue.getValue() == 5.2F);
    assert(getJNIClassCache().m_doubleValue.getValue() == 2.4);
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    auto obj = getJNIClassCache().m_objectValue.getValue();
    assert(env->IsInstanceOf(obj.get(), JNI::TypedClass<jobject>()));

    auto classValue = getJNIClassCache().m_classValue.getValue();
    assert(env->IsSameObject(classValue.get(), getJNIClassCache()));

    auto throwable = getJNIClassCache().m_throwableValue.getValue();
    assert(env->IsInstanceOf(throwable.get(), JNI::TypedClass<jthrowable>()));

    auto javaStr = getJNIClassCache().m_stringValue.getValue();
    assert(env->IsInstanceOf(javaStr.get(), JNI::TypedClass<jstring>()));
    JNI::String str(javaStr);
    assert(str.getLength() == 4);
    assert(strcmp(str.getContent().get(), "test") == 0);

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    getJNIClassCache().m_booleanValue.setValue(JNI_FALSE);
    getJNIClassCache().m_byteValue.setValue(2);
    getJNIClassCache().m_charValue.setValue('B');
    getJNIClassCache().m_shortValue.setValue(3);
    getJNIClassCache().m_intValue.setValue(4);
    getJNIClassCache().m_longValue.setValue(5);
    getJNIClassCache().m_floatValue.setValue(2.5F);
    getJNIClassCache().m_doubleValue.setValue(4.2);
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    getJNIClassCache().m_objectValue.setValue(nullptr);
    getJNIClassCache().m_classValue.setValue(nullptr);
    getJNIClassCache().m_throwableValue.setValue(nullptr);
    getJNIClassCache().m_stringValue.setValue(JNI::String("changed"));

    checkFieldsAfterModification();
}
