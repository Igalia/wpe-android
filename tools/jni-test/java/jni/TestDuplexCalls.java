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

public final class TestDuplexCalls {
    private volatile long nativeInstancePointer;
    TestDuplexCalls(long nativeInstancePointer) { this.nativeInstancePointer = nativeInstancePointer; }

    private int value = 0;
    public int getValue() { return value; }

    private native int addTwoNativeMethod(int i);

    public void callNativeMethod(int i) {
        if (nativeInstancePointer == 0)
            return;

        value = addTwoNativeMethod(i);
        if (value != i + 2)
            throw new Error("TestDuplexCalls.callNativeMethod failed");
    }

    public void throwingMethod() { throw new Error(); }
}
