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
 * Mirror of the {@code WPEInputHints} flags from WebKit's {@code WPEInputMethodContext.h}.
 * Values must be kept in sync with the C header.
 */
public final class WPEInputHints {
    private WPEInputHints() {}

    public static final int NONE = 0;
    public static final int SPELLCHECK = 1 << 0;
    public static final int NO_SPELLCHECK = 1 << 1;
    public static final int WORD_COMPLETION = 1 << 2;
    public static final int LOWERCASE = 1 << 3;
    public static final int UPPERCASE_CHARS = 1 << 4;
    public static final int UPPERCASE_WORDS = 1 << 5;
    public static final int UPPERCASE_SENTENCES = 1 << 6;
    public static final int INHIBIT_OSK = 1 << 7;
    public static final int VERTICAL_WRITING = 1 << 8;
    public static final int EMOJI = 1 << 9;
    public static final int NO_EMOJI = 1 << 10;
    public static final int PRIVATE = 1 << 11;
}
