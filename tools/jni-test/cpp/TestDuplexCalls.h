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

#include "JNI/JNI.h"

DECLARE_JNI_CLASS_SIGNATURE(JNITestDuplexCalls, "jni/TestDuplexCalls");

class TestDuplexCalls final {
public:
    static void executeTests(JNIEnv* env, jclass klass);

    TestDuplexCalls();

    int addTwo(int value);
    int getValue() const;

    void callNativeMethodThroughJava(int value);
    void throwingMethod() const;

private:
    bool m_addTwoHasBeenCalled = false;
    Wpe::Android::JNI::ProtectedType<JNITestDuplexCalls> m_javaInstance;
};
