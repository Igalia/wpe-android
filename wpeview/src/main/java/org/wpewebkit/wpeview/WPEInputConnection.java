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

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WPEInputMethodContext;

/**
 * Bridges Android IME input to WebKit by translating BaseInputConnection callbacks into
 * WPEInputMethodContext operations. deleteSurroundingText is split into before/after halves
 * because WPE's "delete-surrounding" signal is single-sided; sendKeyEvent goes through the parent
 * WebView so IME-synthesised hardware keys follow the same path as physical key presses. Composing
 * text becomes a WebKit preedit, so the page shows it as a composition rather than as final text.
 */
public class WPEInputConnection extends BaseInputConnection {
    private final WebView webView;
    private final WPEInputMethodContext imContext;

    public WPEInputConnection(@NonNull WebView webView, boolean fullEditor, @NonNull WPEInputMethodContext imContext) {
        super(webView, fullEditor);
        this.webView = webView;
        this.imContext = imContext;
    }

    @Override
    public boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
        // Empty text still matters while composing: it replaces the composing text with nothing.
        imContext.commitText(text != null ? text.toString() : "");
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        // Java char units per BaseInputConnection contract: supplementary chars count as 2.
        if (beforeLength > 0)
            imContext.deleteSurrounding(-beforeLength, beforeLength);
        if (afterLength > 0)
            imContext.deleteSurrounding(0, afterLength);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        // We don't track the local text buffer, so codepoint counts cannot be translated to char
        // counts accurately; for BMP-only text the two are equivalent.
        return deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean setComposingText(@Nullable CharSequence text, int newCursorPosition) {
        String composing = text != null ? text.toString() : "";
        imContext.setPreedit(composing, preeditCursorOffset(composing.length(), newCursorPosition));
        return true;
    }

    @Override
    public boolean finishComposingText() {
        imContext.finishPreedit();
        return true;
    }

    /**
     * Turns InputConnection's newCursorPosition into an offset in the preedit. A positive one is at or after the end
     * of the composing text, any other at or before its start, and WebKit keeps the caret inside the preedit, so the
     * caret ends up at the nearest end.
     */
    private static int preeditCursorOffset(int length, int newCursorPosition) {
        return newCursorPosition > 0 ? length : 0;
    }

    @Override
    public boolean sendKeyEvent(@NonNull KeyEvent event) {
        return webView.dispatchKeyEvent(event);
    }
}
