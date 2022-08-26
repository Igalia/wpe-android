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

#include "TestConstructors.h"

#include <cassert>

using namespace Wpe::Android;

namespace {
class JNIClassCache final : public JNI::TypedClass<JNITestConstructors> {
public:
    // NOLINTBEGIN(misc-non-private-member-variables-in-classes)
    const JNI::Constructor<JNITestConstructors()> m_defaultConstructor = getConstructor<>();
    const JNI::Constructor<JNITestConstructors(jint)> m_intConstructor = getConstructor<jint>();

    const JNI::Method<jint()> m_getValue = getMethod<jint()>("getValue");
    // NOLINTEND(misc-non-private-member-variables-in-classes)
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

TestConstructors::TestConstructors()
    : m_javaInstance(getJNIClassCache().m_defaultConstructor.createNewInstance())
{
}

TestConstructors::TestConstructors(int value)
    : m_javaInstance(getJNIClassCache().m_intConstructor.createNewInstance(value))
{
}

int TestConstructors::getValue() const { return getJNIClassCache().m_getValue.invoke(m_javaInstance.get()); }

void TestConstructors::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    TestConstructors test1;
    assert(test1.m_javaInstance != nullptr);
    assert(env->IsInstanceOf(test1.m_javaInstance.get(), getJNIClassCache()));
    assert(test1.getValue() == 1);

    TestConstructors test2(4);
    assert(test2.m_javaInstance != nullptr);
    assert(env->IsInstanceOf(test2.m_javaInstance.get(), getJNIClassCache()));
    assert(test2.getValue() == 4);
}
