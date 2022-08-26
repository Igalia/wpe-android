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

public final class TestStaticFields {
    public static boolean booleanValue = true;
    public static byte byteValue = 1;
    public static char charValue = 'A';
    public static short shortValue = 2;
    public static int intValue = 3;
    public static long longValue = 4;
    public static float floatValue = 5.2f;
    public static double doubleValue = 2.4;
    public static Object objectValue = new Object();
    public static Class<?> classValue = TestStaticFields.class;
    public static Throwable throwableValue = new Throwable();
    public static String stringValue = "test";

    public static void checkFieldsAfterModification() {
        if (booleanValue != false)
            throw new Error("TestStaticFields.setBooleanValue failed");

        if (byteValue != 2)
            throw new Error("TestStaticFields.setByteValue failed");

        if (charValue != 'B')
            throw new Error("TestStaticFields.setCharValue failed");

        if (shortValue != 3)
            throw new Error("TestStaticFields.setShortValue failed");

        if (intValue != 4)
            throw new Error("TestStaticFields.setIntValue failed");

        if (longValue != 5)
            throw new Error("TestStaticFields.setLongValue failed");

        if (floatValue != 2.5f)
            throw new Error("TestStaticFields.setFloatValue failed");

        if (doubleValue != 4.2)
            throw new Error("TestStaticFields.setDoubleValue failed");

        if (objectValue != null)
            throw new Error("TestStaticFields.setObjectValue failed");

        if (classValue != null)
            throw new Error("TestStaticFields.setClassValue failed");

        if (throwableValue != null)
            throw new Error("TestStaticFields.setThrowableValue failed");

        if (stringValue.compareTo("changed") != 0)
            throw new Error("TestStaticFields.setStringValue failed");
    }
}
