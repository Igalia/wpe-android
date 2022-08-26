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

#include "TestMethods.h"

#include <cassert>
#include <cstring>

using namespace Wpe::Android;

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestMethods> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::Constructor<JNITestMethods()> m_constructor = getConstructor<>();

    const JNI::Method<jboolean()> m_hasVoidMethodBeenCalled = getMethod<jboolean()>("hasVoidMethodBeenCalled");

    const JNI::Method<void()> m_voidMethod = getMethod<void()>("voidMethod");
    const JNI::Method<jboolean()> m_booleanMethod = getMethod<jboolean()>("booleanMethod");
    const JNI::Method<jbyte()> m_byteMethod = getMethod<jbyte()>("byteMethod");
    const JNI::Method<jchar()> m_charMethod = getMethod<jchar()>("charMethod");
    const JNI::Method<jshort()> m_shortMethod = getMethod<jshort()>("shortMethod");
    const JNI::Method<jint()> m_intMethod = getMethod<jint()>("intMethod");
    const JNI::Method<jlong()> m_longMethod = getMethod<jlong()>("longMethod");
    const JNI::Method<jfloat()> m_floatMethod = getMethod<jfloat()>("floatMethod");
    const JNI::Method<jdouble()> m_doubleMethod = getMethod<jdouble()>("doubleMethod");
    const JNI::Method<jobject()> m_objectMethod = getMethod<jobject()>("objectMethod");
    const JNI::Method<jstring()> m_stringMethod = getMethod<jstring()>("stringMethod");
    const JNI::Method<JNITestMethods()> m_thisMethod = getMethod<JNITestMethods()>("thisMethod");
    const JNI::Method<jint(jint, jint)> m_intMethodWithParams = getMethod<jint(jint, jint)>("intMethodWithParams");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

TestMethods::TestMethods()
    : m_javaInstance(getJNIClassCache().m_constructor.createNewInstance())
{
}

bool TestMethods::hasVoidMethodBeenCalled() const
{
    return (getJNIClassCache().m_hasVoidMethodBeenCalled.invoke(m_javaInstance.get()) == JNI_TRUE);
}

void TestMethods::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    TestMethods test;
    assert(test.m_javaInstance != nullptr);
    assert(env->IsInstanceOf(test.m_javaInstance.get(), getJNIClassCache()));

    assert(!test.hasVoidMethodBeenCalled());
    getJNIClassCache().m_voidMethod.invoke(test.m_javaInstance.get());
    assert(test.hasVoidMethodBeenCalled());

    // NOLINTBEGIN(cppcoreguidelines-avoid-magic-numbers)
    assert(getJNIClassCache().m_booleanMethod.invoke(test.m_javaInstance.get()) == JNI_TRUE);
    assert(getJNIClassCache().m_byteMethod.invoke(test.m_javaInstance.get()) == 1);
    assert(getJNIClassCache().m_charMethod.invoke(test.m_javaInstance.get()) == 'A');
    assert(getJNIClassCache().m_shortMethod.invoke(test.m_javaInstance.get()) == 2);
    assert(getJNIClassCache().m_intMethod.invoke(test.m_javaInstance.get()) == 3);
    assert(getJNIClassCache().m_longMethod.invoke(test.m_javaInstance.get()) == 4);
    assert(getJNIClassCache().m_floatMethod.invoke(test.m_javaInstance.get()) == 5.2F);
    assert(getJNIClassCache().m_doubleMethod.invoke(test.m_javaInstance.get()) == 2.4);
    // NOLINTEND(cppcoreguidelines-avoid-magic-numbers)

    auto obj = getJNIClassCache().m_objectMethod.invoke(test.m_javaInstance.get());
    assert(env->IsInstanceOf(obj.get(), JNI::TypedClass<jobject>()));

    auto javaStr = getJNIClassCache().m_stringMethod.invoke(test.m_javaInstance.get());
    assert(env->IsInstanceOf(javaStr.get(), JNI::TypedClass<jstring>()));
    JNI::String str(javaStr);
    assert(str.getLength() == 4);
    assert(strcmp(str.getContent().get(), "test") == 0);

    auto thiz = getJNIClassCache().m_thisMethod.invoke(test.m_javaInstance.get());
    assert(env->IsSameObject(thiz.get(), test.m_javaInstance.get()));

    assert(getJNIClassCache().m_intMethodWithParams.invoke(test.m_javaInstance.get(), 5, 6) == 11);
}
