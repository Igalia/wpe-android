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
 * WPEInputMethodContext operations. Commits and composing text go through the WebView's ImeEditor,
 * which keeps track of the text they leave; composing text becomes a WebKit preedit.
 * deleteSurroundingText is split into before/after halves because WPE's "delete-surrounding" signal
 * is single-sided; sendKeyEvent goes through the parent WebView so IME-synthesised hardware keys
 * follow the same path as physical key presses.
 */
public class WPEInputConnection extends BaseInputConnection {
    private final WebView webView;
    private final WPEInputMethodContext imContext;
    private final ImeEditor editor;

    public WPEInputConnection(@NonNull WebView webView, boolean fullEditor, @NonNull WPEInputMethodContext imContext) {
        super(webView, fullEditor);
        this.webView = webView;
        this.imContext = imContext;
        this.editor = webView.getImeEditor();
    }

    @Override
    public boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
        return editor.commitText(text, newCursorPosition);
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
        return editor.setComposingText(text, newCursorPosition);
    }

    @Override
    public boolean finishComposingText() {
        return editor.finishComposingText();
    }

    @Override
    public boolean sendKeyEvent(@NonNull KeyEvent event) {
        return webView.dispatchKeyEvent(event);
    }
}
