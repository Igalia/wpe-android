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

#include "TestStaticMethods.h"

#include <cassert>
#include <cstring>

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestStaticMethods> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::StaticMethod<jboolean()> m_hasVoidMethodBeenCalled
        = getStaticMethod<jboolean()>("hasVoidMethodBeenCalled");

    const JNI::StaticMethod<void()> m_voidMethod = getStaticMethod<void()>("voidMethod");
    const JNI::StaticMethod<jboolean()> m_booleanMethod = getStaticMethod<jboolean()>("booleanMethod");
    const JNI::StaticMethod<jbyte()> m_byteMethod = getStaticMethod<jbyte()>("byteMethod");
    const JNI::StaticMethod<jchar()> m_charMethod = getStaticMethod<jchar()>("charMethod");
    const JNI::StaticMethod<jshort()> m_shortMethod = getStaticMethod<jshort()>("shortMethod");
    const JNI::StaticMethod<jint()> m_intMethod = getStaticMethod<jint()>("intMethod");
    const JNI::StaticMethod<jlong()> m_longMethod = getStaticMethod<jlong()>("longMethod");
    const JNI::StaticMethod<jfloat()> m_floatMethod = getStaticMethod<jfloat()>("floatMethod");
    const JNI::StaticMethod<jdouble()> m_doubleMethod = getStaticMethod<jdouble()>("doubleMethod");
    const JNI::StaticMethod<jobject()> m_objectMethod = getStaticMethod<jobject()>("objectMethod");
    const JNI::StaticMethod<jstring()> m_stringMethod = getStaticMethod<jstring()>("stringMethod");
    const JNI::StaticMethod<jint(jint, jint)> m_intMethodWithParams
        = getStaticMethod<jint(jint, jint)>("intMethodWithParams");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

bool TestStaticMethods::hasVoidMethodBeenCalled()
{
    return (getJNIClassCache().m_hasVoidMethodBeenCalled.invoke() == JNI_TRUE);
}

void TestStaticMethods::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    assert(!hasVoidMethodBeenCalled());
    getJNIClassCache().m_voidMethod.invoke();
    assert(hasVoidMethodBeenCalled());

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    assert(getJNIClassCache().m_booleanMethod.invoke() == JNI_TRUE);
    assert(getJNIClassCache().m_byteMethod.invoke() == 1);
    assert(getJNIClassCache().m_charMethod.invoke() == 'A');
    assert(getJNIClassCache().m_shortMethod.invoke() == 2);
    assert(getJNIClassCache().m_intMethod.invoke() == 3);
    assert(getJNIClassCache().m_longMethod.invoke() == 4);
    assert(getJNIClassCache().m_floatMethod.invoke() == 5.2F);
    assert(getJNIClassCache().m_doubleMethod.invoke() == 2.4);
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    auto obj = getJNIClassCache().m_objectMethod.invoke();
    assert(env->IsInstanceOf(obj.get(), JNI::TypedClass<jobject>()));

    auto javaStr = getJNIClassCache().m_stringMethod.invoke();
    assert(env->IsInstanceOf(javaStr.get(), JNI::TypedClass<jstring>()));
    JNI::String str(javaStr);
    assert(str.getLength() == 4);
    assert(strcmp(str.getContent().get(), "test") == 0);

    assert(getJNIClassCache().m_intMethodWithParams.invoke(5, 6) == 11);
}
