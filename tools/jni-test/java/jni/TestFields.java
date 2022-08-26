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

public final class TestFields {
    public boolean booleanValue = true;
    public byte byteValue = 1;
    public char charValue = 'A';
    public short shortValue = 2;
    public int intValue = 3;
    public long longValue = 4;
    public float floatValue = 5.2f;
    public double doubleValue = 2.4;
    public Object objectValue = new Object();
    public Class<?> classValue = TestFields.class;
    public Throwable throwableValue = new Throwable();
    public String stringValue = "test";
    public TestFields thisValue = this;

    public void checkFieldsAfterModification() {
        if (booleanValue != false)
            throw new Error("TestFields.setBooleanValue failed");

        if (byteValue != 2)
            throw new Error("TestFields.setByteValue failed");

        if (charValue != 'B')
            throw new Error("TestFields.setCharValue failed");

        if (shortValue != 3)
            throw new Error("TestFields.setShortValue failed");

        if (intValue != 4)
            throw new Error("TestFields.setIntValue failed");

        if (longValue != 5)
            throw new Error("TestFields.setLongValue failed");

        if (floatValue != 2.5f)
            throw new Error("TestFields.setFloatValue failed");

        if (doubleValue != 4.2)
            throw new Error("TestFields.setDoubleValue failed");

        if (objectValue != null)
            throw new Error("TestFields.setObjectValue failed");

        if (classValue != null)
            throw new Error("TestFields.setClassValue failed");

        if (throwableValue != null)
            throw new Error("TestFields.setThrowableValue failed");

        if (stringValue.compareTo("changed") != 0)
            throw new Error("TestFields.setStringValue failed");

        if (thisValue != null)
            throw new Error("TestFields.setThisValue failed");
    }
}
