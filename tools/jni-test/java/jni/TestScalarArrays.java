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

public final class TestScalarArrays {
    public static boolean[] getBooleanArray() { return new boolean[] {false, true}; }
    public static byte[] getByteArray() { return new byte[] {1, 2, 3}; }
    public static char[] getCharArray() { return new char[] {'A', 'B', 'C'}; }
    public static short[] getShortArray() { return new short[] {4, 5, 6}; }
    public static int[] getIntArray() { return new int[] {7, 8, 9}; }
    public static long[] getLongArray() { return new long[] {10, 11, 12}; }
    public static float[] getFloatArray() { return new float[] {1.1f, 2.2f, 3.3f}; }
    public static double[] getDoubleArray() { return new double[] {4.4, 5.5, 6.6}; }

    public static void setBooleanArray(boolean[] array) {
        if (array.length != 2)
            throw new Error("TestScalarArrays.setBooleanArray failed");

        if (array[0] != true)
            throw new Error("TestScalarArrays.setBooleanArray failed");

        if (array[1] != false)
            throw new Error("TestScalarArrays.setBooleanArray failed");
    }

    public static void setByteArray(byte[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setByteArray failed");

        if (array[0] != 3)
            throw new Error("TestScalarArrays.setByteArray failed");

        if (array[1] != 2)
            throw new Error("TestScalarArrays.setByteArray failed");

        if (array[2] != 1)
            throw new Error("TestScalarArrays.setByteArray failed");
    }

    public static void setCharArray(char[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setCharArray failed");

        if (array[0] != 'C')
            throw new Error("TestScalarArrays.setCharArray failed");

        if (array[1] != 'B')
            throw new Error("TestScalarArrays.setCharArray failed");

        if (array[2] != 'A')
            throw new Error("TestScalarArrays.setCharArray failed");
    }

    public static void setShortArray(short[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setShortArray failed");

        if (array[0] != 6)
            throw new Error("TestScalarArrays.setShortArray failed");

        if (array[1] != 5)
            throw new Error("TestScalarArrays.setShortArray failed");

        if (array[2] != 4)
            throw new Error("TestScalarArrays.setShortArray failed");
    }

    public static void setIntArray(int[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setIntArray failed");

        if (array[0] != 9)
            throw new Error("TestScalarArrays.setIntArray failed");

        if (array[1] != 8)
            throw new Error("TestScalarArrays.setIntArray failed");

        if (array[2] != 7)
            throw new Error("TestScalarArrays.setIntArray failed");
    }

    public static void setLongArray(long[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setLongArray failed");

        if (array[0] != 12)
            throw new Error("TestScalarArrays.setLongArray failed");

        if (array[1] != 11)
            throw new Error("TestScalarArrays.setLongArray failed");

        if (array[2] != 10)
            throw new Error("TestScalarArrays.setLongArray failed");
    }

    public static void setFloatArray(float[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setFloatArray failed");

        if (array[0] != 3.3f)
            throw new Error("TestScalarArrays.setFloatArray failed");

        if (array[1] != 2.2f)
            throw new Error("TestScalarArrays.setFloatArray failed");

        if (array[2] != 1.1f)
            throw new Error("TestScalarArrays.setFloatArray failed");
    }

    public static void setDoubleArray(double[] array) {
        if (array.length != 3)
            throw new Error("TestScalarArrays.setDoubleArray failed");

        if (array[0] != 6.6)
            throw new Error("TestScalarArrays.setDoubleArray failed");

        if (array[1] != 5.5)
            throw new Error("TestScalarArrays.setDoubleArray failed");

        if (array[2] != 4.4)
            throw new Error("TestScalarArrays.setDoubleArray failed");
    }

    public static final int[] staticIntArray = new int[] {20, 30, 40, 50};

    public static void checkStaticIntArrayAfterModification() {
        if (staticIntArray.length != 4)
            throw new Error("TestScalarArrays.checkStaticIntArrayAfterModification failed");

        if (staticIntArray[0] != 25)
            throw new Error("TestScalarArrays.checkStaticIntArrayAfterModification failed");

        if (staticIntArray[1] != 35)
            throw new Error("TestScalarArrays.checkStaticIntArrayAfterModification failed");

        if (staticIntArray[2] != 45)
            throw new Error("TestScalarArrays.checkStaticIntArrayAfterModification failed");

        if (staticIntArray[3] != 55)
            throw new Error("TestScalarArrays.checkStaticIntArrayAfterModification failed");
    }
}
