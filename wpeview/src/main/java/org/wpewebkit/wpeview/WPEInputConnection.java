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

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.SurroundingText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WPEInputMethodContext;

/**
 * Bridges Android IME input to WebKit by translating BaseInputConnection callbacks into
 * WPEInputMethodContext operations. deleteSurroundingText is split into before/after halves
 * because WPE's "delete-surrounding" signal is single-sided; sendKeyEvent goes through the parent
 * WebView so IME-synthesised hardware keys follow the same path as physical key presses.
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
        if (text == null || text.length() == 0)
            return true;
        imContext.commitText(text.toString());
        return true;
    }

    // The text getters answer from what WebKit last reported, so the input method knows what it is editing:
    // for example, that the character before the cursor is an emoji, two Java chars long.

    @Override
    @Nullable
    public CharSequence getTextBeforeCursor(int n, int flags) {
        if (n < 0)
            return null;
        String text = webView.getImeSurroundingText();
        int start = webView.getImeSelectionStart();
        return text.substring(Math.max(0, start - n), start);
    }

    @Override
    @Nullable
    public CharSequence getTextAfterCursor(int n, int flags) {
        if (n < 0)
            return null;
        String text = webView.getImeSurroundingText();
        int end = webView.getImeSelectionEnd();
        return text.substring(end, end + Math.min(n, text.length() - end));
    }

    @Override
    @Nullable
    public CharSequence getSelectedText(int flags) {
        int start = webView.getImeSelectionStart();
        int end = webView.getImeSelectionEnd();
        if (start == end)
            return null;
        return webView.getImeSurroundingText().substring(start, end);
    }

    @Override
    @Nullable
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        if (beforeLength < 0 || afterLength < 0)
            return null;
        String text = webView.getImeSurroundingText();
        int start = webView.getImeSelectionStart();
        int end = webView.getImeSelectionEnd();
        int offset = Math.max(0, start - beforeLength);
        int limit = end + Math.min(afterLength, text.length() - end);
        return new SurroundingText(text.substring(offset, limit), start - offset, end - offset, offset);
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        return TextUtils.getCapsMode(webView.getImeSurroundingText(), webView.getImeSelectionStart(), reqModes);
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
        // Preedit is not wired through WPE yet; commit as final text.
        return commitText(text, newCursorPosition);
    }

    @Override
    public boolean finishComposingText() {
        return true;
    }

    @Override
    public boolean sendKeyEvent(@NonNull KeyEvent event) {
        return webView.dispatchKeyEvent(event);
    }
}
