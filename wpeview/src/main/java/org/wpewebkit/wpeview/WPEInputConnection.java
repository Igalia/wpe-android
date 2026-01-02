/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
 *   Author: Felipe Erias <fei@igalia.com>
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

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WKWebView;

/**
 * Bridges Android IME input to WebKit's input method system. Handles text commit, deletion,
 * and special keys. Note: composition text is currently treated as committed text.
 */
public class WPEInputConnection extends BaseInputConnection {
    private final WKWebView wkWebView;

    public WPEInputConnection(@NonNull View targetView, boolean fullEditor, @NonNull WKWebView wkWebView) {
        super(targetView, fullEditor);
        this.wkWebView = wkWebView;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (text == null || text.length() == 0) {
            return true;
        }

        // This loop iterates by char position but we need to send whole codepoints to WebKit, so when we detect a
        // supplementary character (emoji, etc.) we get the codepoint at that location and skip the second char.
        for (int i = 0; i < text.length(); i++) {
            int codePoint = Character.codePointAt(text, i);
            wkWebView.setInputMethodContent(codePoint);

            if (Character.isSupplementaryCodePoint(codePoint)) {
                i++;
            }
        }

        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        // Counts Java char units (supplementary chars count as 2)
        if (beforeLength > 0) {
            wkWebView.deleteInputMethodContent(-beforeLength, beforeLength);
        }

        if (afterLength > 0) {
            wkWebView.deleteInputMethodContent(0, afterLength);
        }

        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        // TODO: Implement proper preedit support via WebKit's input method context
        return commitText(text, newCursorPosition);
    }

    @Override
    public boolean finishComposingText() {
        // Nothing to do since we treat composing text as committed
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        // Counts code points (not char units), but we delegate to deleteSurroundingText
        // since we don't track text buffer state. May not handle supplementary chars correctly.
        return deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean sendKeyEvent(@NonNull KeyEvent event) {
        // Forward key events to WebKit
        wkWebView.onKeyEvent(event);
        return true;
    }
}
