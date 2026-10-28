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

import android.os.Build;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Bridges Android IME input to WebKit. Edits go through the WebView's ImeEditor, which turns them into
 * WPEInputMethodContext operations and keeps track of the text they leave; composing text becomes a
 * WebKit preedit. sendKeyEvent goes through the parent WebView so IME-synthesised hardware keys follow
 * the same path as physical key presses.
 */
public class WPEInputConnection extends BaseInputConnection {
    private final WebView webView;
    private final ImeEditor editor;

    public WPEInputConnection(@NonNull WebView webView, boolean fullEditor) {
        super(webView, fullEditor);
        this.webView = webView;
        this.editor = webView.getImeEditor();
    }

    @Override
    public boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
        return editor.commitText(text, newCursorPosition);
    }

    // The text getters answer from what WebKit last reported, with the composing text and the edits WebKit has not
    // reported back yet, so the input method knows what it is editing: for example, that the character before the
    // cursor is an emoji, two Java chars long.

    @Override
    @Nullable
    public CharSequence getTextBeforeCursor(int n, int flags) {
        return n >= 0 ? editor.getTextBeforeCursor(n) : null;
    }

    @Override
    @Nullable
    public CharSequence getTextAfterCursor(int n, int flags) {
        return n >= 0 ? editor.getTextAfterCursor(n) : null;
    }

    @Override
    @Nullable
    public CharSequence getSelectedText(int flags) {
        return editor.getSelectedText();
    }

    @Override
    @Nullable
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        if (beforeLength < 0 || afterLength < 0)
            return null;
        String before = editor.getTextBeforeCursor(beforeLength);
        String selected = editor.getSelectedText();
        String text = before + (selected != null ? selected : "") + editor.getTextAfterCursor(afterLength);
        return new SurroundingText(text, before.length(), before.length() + (selected != null ? selected.length() : 0),
                                   editor.getSelectionStart() - before.length());
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        return TextUtils.getCapsMode(editor.getText(), editor.getSelectionStart(), reqModes);
    }

    @Override
    public boolean setSelection(int start, int end) {
        return editor.setSelection(start, end);
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return editor.deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return editor.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
    }

    @Override
    public boolean setComposingText(@Nullable CharSequence text, int newCursorPosition) {
        return editor.setComposingText(text, newCursorPosition);
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        return editor.setComposingRegion(start, end);
    }

    // BaseInputConnection's would edit its own copy of the text, which WebKit never sees.
    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public boolean replaceText(int start, int end, @NonNull CharSequence text, int newCursorPosition,
                               @Nullable TextAttribute textAttribute) {
        return editor.replaceText(start, end, text);
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
