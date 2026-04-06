/**
 * Copyright (C) 2026 Igalia S.L.
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

package org.wpewebkit.wpe;

/**
 * Mirror of the {@code WPEEventType} enum from WebKit's {@code WPEEvent.h}.
 * Values must be kept in sync with the C header.
 */
public final class WPEEventType {
    private WPEEventType() {}

    public static final int NONE = 0;
    public static final int POINTER_DOWN = 1;
    public static final int POINTER_UP = 2;
    public static final int POINTER_MOVE = 3;
    public static final int POINTER_ENTER = 4;
    public static final int POINTER_LEAVE = 5;
    public static final int SCROLL = 6;
    public static final int KEYBOARD_KEY_DOWN = 7;
    public static final int KEYBOARD_KEY_UP = 8;
    public static final int TOUCH_DOWN = 9;
    public static final int TOUCH_UP = 10;
    public static final int TOUCH_MOVE = 11;
    public static final int TOUCH_CANCEL = 12;
}
