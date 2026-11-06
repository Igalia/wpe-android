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

package org.wpewebkit.wpeview;

import android.view.inputmethod.EditorInfo;

import org.wpewebkit.wpe.WPEInputHints;
import org.wpewebkit.wpe.WPEInputPurpose;

/**
 * Tells the input method what the focused field of the page takes, from the purpose and hints that WebKit gives for
 * it, so that it shows the right keyboard and does not learn passwords.
 */
final class FieldInputType {
    private FieldInputType() {}

    /** The {@link EditorInfo#inputType} for the given {@link WPEInputPurpose} and {@link WPEInputHints}. */
    static int inputType(int purpose, int hints) {
        switch (purpose) {
        case WPEInputPurpose.DIGITS:
            return EditorInfo.TYPE_CLASS_NUMBER;
        case WPEInputPurpose.NUMBER:
            // Number fields and the decimal input mode take fractions and negative numbers.
            return EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_FLAG_DECIMAL |
                EditorInfo.TYPE_NUMBER_FLAG_SIGNED;
        case WPEInputPurpose.PIN:
            return EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
        case WPEInputPurpose.PHONE:
            return EditorInfo.TYPE_CLASS_PHONE;
        default:
            break;
        }

        int type = EditorInfo.TYPE_CLASS_TEXT;
        switch (purpose) {
        case WPEInputPurpose.PASSWORD:
            type |= EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            break;
        case WPEInputPurpose.EMAIL:
            type |= EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
            break;
        case WPEInputPurpose.URL:
            type |= EditorInfo.TYPE_TEXT_VARIATION_URI;
            break;
        case WPEInputPurpose.NAME:
            type |= EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME;
            break;
        default:
            type |= EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
            break;
        }
        // WebKit asks for privacy for a field that has been a password and shows its text now: suggestions there would
        // show or correct the password.
        if ((hints & WPEInputHints.PRIVATE) != 0)
            type |= EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if ((hints & WPEInputHints.UPPERCASE_CHARS) != 0)
            type |= EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        else if ((hints & WPEInputHints.UPPERCASE_WORDS) != 0)
            type |= EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS;
        else if ((hints & WPEInputHints.UPPERCASE_SENTENCES) != 0)
            type |= EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES;
        return type;
    }

    /** The {@link EditorInfo#imeOptions} for the given {@link WPEInputPurpose} and {@link WPEInputHints}. */
    static int imeOptions(int purpose, int hints) {
        int options = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        if (purpose == WPEInputPurpose.PASSWORD || purpose == WPEInputPurpose.PIN ||
            (hints & WPEInputHints.PRIVATE) != 0)
            options |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return options;
    }
}
