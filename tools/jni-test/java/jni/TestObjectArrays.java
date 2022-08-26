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

public final class TestObjectArrays {
    public static String[] getStringArray() { return new String[] {"call1", "call2", "call3"}; }

    public static void setStringArray(String[] array) {
        if (array.length != 3)
            throw new Error("TestObjectArrays.setStringArray failed");

        if (!array[0].equals("resp1"))
            throw new Error("TestObjectArrays.setStringArray failed");

        if (!array[1].equals("resp2"))
            throw new Error("TestObjectArrays.setStringArray failed");

        if (!array[2].equals("resp3"))
            throw new Error("TestObjectArrays.setStringArray failed");
    }

    public static final String[] staticStringArray = new String[] {"test1", "test2", "test3", "test4"};

    public static void checkStaticStringArrayAfterModification() {
        if (staticStringArray.length != 4)
            throw new Error("TestObjectArrays.checkStaticStringArrayAfterModification failed");

        if (!staticStringArray[0].equals("changed1"))
            throw new Error("TestObjectArrays.checkStaticStringArrayAfterModification failed");

        if (!staticStringArray[1].equals("changed2"))
            throw new Error("TestObjectArrays.checkStaticStringArrayAfterModification failed");

        if (!staticStringArray[2].equals("changed3"))
            throw new Error("TestObjectArrays.checkStaticStringArrayAfterModification failed");

        if (!staticStringArray[3].equals("changed4"))
            throw new Error("TestObjectArrays.checkStaticStringArrayAfterModification failed");
    }
}
