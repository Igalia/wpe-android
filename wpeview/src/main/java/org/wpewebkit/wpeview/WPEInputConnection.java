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
 * InputConnection implementation for WPEView that bridges Android IME input
 * to the WebKit text input system.
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

        // Send each character to WebKit
        for (int i = 0; i < text.length(); i++) {
            int codePoint = Character.codePointAt(text, i);
            wkWebView.setInputMethodContent(codePoint);

            // Handle surrogate pairs (characters outside BMP that use two Java chars)
            if (Character.isSupplementaryCodePoint(codePoint)) {
                i++; // Skip the low surrogate
            }
        }

        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (beforeLength > 0) {
            // Delete characters before cursor
            // WebKit's delete-surrounding signal: offset is negative for before cursor, count is number to delete
            wkWebView.deleteInputMethodContent(-beforeLength, beforeLength);
        }

        if (afterLength > 0) {
            // Delete characters after cursor
            // WebKit's delete-surrounding signal: offset 0 for at cursor, count is number to delete forward
            wkWebView.deleteInputMethodContent(0, afterLength);
        }

        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        // For now, treat composing text the same as committed text
        // A more sophisticated implementation could track composition state
        // and send it to WebKit as preedit text
        return commitText(text, newCursorPosition);
    }

    @Override
    public boolean finishComposingText() {
        // Composition is finished, nothing special to do
        // since we're treating composing text as committed text
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        // This is similar to deleteSurroundingText but counts code points instead of chars
        // For simplicity, delegate to the regular deleteSurroundingText
        return deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean sendKeyEvent(@NonNull KeyEvent event) {
        // Handle special keys that need to be sent to WebKit
        // Only process ACTION_DOWN to avoid duplicate events (ACTION_DOWN + ACTION_UP)
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
                // Send newline character (Unicode 10 = '\n')
                wkWebView.setInputMethodContent(10);
                return true;

            case KeyEvent.KEYCODE_DEL:
                // Delete one character before cursor
                // This handles Delete/Backspace when sent as a key event
                // (regular backspace during composition goes through deleteSurroundingText)
                wkWebView.deleteInputMethodContent(-1, 1);
                return true;

            default:
                // For other keys, use the default BaseInputConnection behavior
                break;
            }
        }

        // Let BaseInputConnection handle other key events
        return super.sendKeyEvent(event);
    }
}
