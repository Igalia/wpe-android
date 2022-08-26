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

package jni;

public final class Test {
    private static native void testConstructors();
    private static native void testDuplexCalls();
    private static native void testFields();
    private static native void testMethods();
    private static native void testObjectArrays();
    private static native void testScalarArrays();
    private static native void testStaticFields();
    private static native void testStaticMethods();

    static { System.loadLibrary("jniTest"); }

    public static void main(String[] args) {
        Test.testConstructors();
        Test.testDuplexCalls();
        Test.testFields();
        Test.testMethods();
        Test.testObjectArrays();
        Test.testScalarArrays();
        Test.testStaticFields();
        Test.testStaticMethods();
    }
}
