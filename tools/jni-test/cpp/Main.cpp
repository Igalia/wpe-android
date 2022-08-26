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
#include "TestDuplexCalls.h"
#include "TestFields.h"
#include "TestMethods.h"
#include "TestObjectArrays.h"
#include "TestScalarArrays.h"
#include "TestStaticFields.h"
#include "TestStaticMethods.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVM, void* /*reserved*/)
{
    using namespace Wpe::Android;

    try {
        JNI::initVM(javaVM);
        JNI::Class("jni/Test")
            .registerNativeMethods(JNI::StaticNativeMethod<void()>("testConstructors", TestConstructors::executeTests),
                JNI::StaticNativeMethod<void()>("testDuplexCalls", TestDuplexCalls::executeTests),
                JNI::StaticNativeMethod<void()>("testFields", TestFields::executeTests),
                JNI::StaticNativeMethod<void()>("testMethods", TestMethods::executeTests),
                JNI::StaticNativeMethod<void()>("testObjectArrays", TestObjectArrays::executeTests),
                JNI::StaticNativeMethod<void()>("testScalarArrays", TestScalarArrays::executeTests),
                JNI::StaticNativeMethod<void()>("testStaticFields", TestStaticFields::executeTests),
                JNI::StaticNativeMethod<void()>("testStaticMethods", TestStaticMethods::executeTests));

        return JNI::VERSION;
    } catch (...) {
        return JNI_ERR;
    }
}
