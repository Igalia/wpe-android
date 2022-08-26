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

#include "TestDuplexCalls.h"

#include <cassert>

using namespace Wpe::Android;

namespace {
class JNIClassCache;
const JNIClassCache& getJNIClassCache();

class JNIClassCache final : public JNI::TypedClass<JNITestDuplexCalls> {
public:
    JNIClassCache()
    {
        registerNativeMethods(
            JNI::NativeMethod<jint(jint)>("addTwoNativeMethod", [](JNIEnv*, jobject obj, jint value) -> jint {
                return getJNIClassCache().getNativeInstance(obj)->addTwo(value);
            }));
    }

    // NOLINTBEGIN(cppcoreguidelines-non-private-member-variables-in-classes)
    const JNI::Constructor<JNITestDuplexCalls(jlong)> m_constructor = getConstructor<jlong>();

    const JNI::Method<jint()> m_getValue = getMethod<jint()>("getValue");
    const JNI::Method<void(jint)> m_callNativeMethod = getMethod<void(jint)>("callNativeMethod");
    const JNI::Method<void()> m_throwingMethod = getMethod<void()>("throwingMethod");
    // NOLINTEND(cppcoreguidelines-non-private-member-variables-in-classes)

    TestDuplexCalls* getNativeInstance(jobject obj) const
    {
        // NOLINTNEXTLINE(performance-no-int-to-ptr)
        return reinterpret_cast<TestDuplexCalls*>(m_nativeInstancePointer.getValue(obj));
    }

private:
    const JNI::Field<jlong> m_nativeInstancePointer = getField<jlong>("nativeInstancePointer");
};

const JNIClassCache& getJNIClassCache()
{
    static const JNIClassCache s_singleton;
    return s_singleton;
}
} // namespace

TestDuplexCalls::TestDuplexCalls()
    : m_javaInstance(getJNIClassCache().m_constructor.createNewInstance(reinterpret_cast<jlong>(this)))
{
}

int TestDuplexCalls::addTwo(int value)
{
    m_addTwoHasBeenCalled = true;
    return value + 2;
}

int TestDuplexCalls::getValue() const { return getJNIClassCache().m_getValue.invoke(m_javaInstance.get()); }

void TestDuplexCalls::callNativeMethodThroughJava(int value)
{
    m_addTwoHasBeenCalled = false;
    getJNIClassCache().m_callNativeMethod.invoke(m_javaInstance.get(), value);
}

void TestDuplexCalls::throwingMethod() const { getJNIClassCache().m_throwingMethod.invoke(m_javaInstance.get()); }

void TestDuplexCalls::executeTests(JNIEnv* env, jclass klass)
{
    assert(env != nullptr);
    assert(klass != nullptr);

    TestDuplexCalls test;
    assert(test.m_javaInstance != nullptr);
    assert(env->IsInstanceOf(test.m_javaInstance.get(), getJNIClassCache()));
    assert(getJNIClassCache().getNativeInstance(test.m_javaInstance.get()) == &test);

    assert(!test.m_addTwoHasBeenCalled);
    test.callNativeMethodThroughJava(6); // NOLINT(cppcoreguidelines-avoid-magic-numbers)
    assert(test.m_addTwoHasBeenCalled);
    assert(test.getValue() == 8);

    JNI::enableJavaExceptionDescription(false);
    bool throwing = false;
    try {
        test.throwingMethod();
    } catch (...) {
        throwing = true;
    }
    JNI::enableJavaExceptionDescription(true);
    assert(throwing);
}
