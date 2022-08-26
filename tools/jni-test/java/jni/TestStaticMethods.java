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

public final class TestStaticMethods {
    private static boolean voidMethodCalled = false;
    public static boolean hasVoidMethodBeenCalled() { return voidMethodCalled; }
    public static void voidMethod() { voidMethodCalled = true; }

    public static boolean booleanMethod() { return true; }
    public static byte byteMethod() { return 1; }
    public static char charMethod() { return 'A'; }
    public static short shortMethod() { return 2; }
    public static int intMethod() { return 3; }
    public static long longMethod() { return 4; }
    public static float floatMethod() { return 5.2f; }
    public static double doubleMethod() { return 2.4; }
    public static Object objectMethod() { return new Object(); }
    public static String stringMethod() { return "test"; }

    public static int intMethodWithParams(int i, int j) { return i + j; }
}
