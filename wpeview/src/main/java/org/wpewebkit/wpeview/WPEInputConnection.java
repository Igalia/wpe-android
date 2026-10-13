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
        return replaceComposingText(text, newCursorPosition, false);
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

        String text = webView.getImeSurroundingText();
        int start = webView.getImeSelectionStart();
        int end = webView.getImeSelectionEnd();
        int deleteStart = Math.max(0, start - Math.max(0, beforeLength));
        int deleteEnd = Math.min(text.length(), end + Math.max(0, afterLength));
        String remaining = text.substring(0, deleteStart) + text.substring(start, end) + text.substring(deleteEnd);
        int selectionStart = deleteStart;
        int selectionEnd = deleteStart + (end - start);
        webView.applyImeEdit(remaining, selectionStart, selectionEnd, -1, -1);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        String text = webView.getImeSurroundingText();
        int start = webView.getImeSelectionStart();
        int end = webView.getImeSelectionEnd();
        int before = Math.min(Math.max(0, beforeLength), text.codePointCount(0, start));
        int after = Math.min(Math.max(0, afterLength), text.codePointCount(end, text.length()));
        return deleteSurroundingText(start - text.offsetByCodePoints(start, -before),
                                     text.offsetByCodePoints(end, after) - end);
    }

    @Override
    public boolean setComposingText(@Nullable CharSequence text, int newCursorPosition) {
        return replaceComposingText(text, newCursorPosition, true);
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        int length = webView.getImeSurroundingText().length();
        int regionStart = Math.max(0, Math.min(Math.min(start, end), length));
        int regionEnd = Math.max(0, Math.min(Math.max(start, end), length));
        if (regionStart == regionEnd)
            webView.setImeComposingRegion(-1, -1);
        else
            webView.setImeComposingRegion(regionStart, regionEnd);
        return true;
    }

    @Override
    public boolean finishComposingText() {
        webView.setImeComposingRegion(-1, -1);
        return true;
    }

    // WPE has no preedit on Android yet, so composing text is written into the field as it changes: the
    // previous composing text (or the selection, when nothing is being composed) is deleted and the new text
    // committed in its place. WebKit's delete-surrounding counts Java chars (UTF-16 code units) from the start
    // of the selection, and a count of 0 only moves the cursor.
    private boolean replaceComposingText(@Nullable CharSequence text, int newCursorPosition, boolean keepComposing) {
        String newText = text != null ? text.toString() : "";
        String current = webView.getImeSurroundingText();
        int selectionStart = webView.getImeSelectionStart();
        int selectionEnd = webView.getImeSelectionEnd();
        int start = selectionStart;
        int end = selectionEnd;
        if (webView.getImeComposingStart() >= 0) {
            start = webView.getImeComposingStart();
            end = webView.getImeComposingEnd();
            imContext.deleteSurrounding(start - selectionStart, end - start);
        } else if (newText.isEmpty() && end > start) {
            imContext.deleteSurrounding(0, end - start);
        }
        // Without a composing region, WebKit's commit replaces the selection.
        if (!newText.isEmpty())
            imContext.commitText(newText);

        String updated = current.substring(0, start) + newText + current.substring(end);
        int cursor = start + newText.length();
        int target = newCursorPosition > 0 ? cursor + newCursorPosition - 1 : start + newCursorPosition;
        target = Math.max(0, Math.min(target, updated.length()));
        if (target != cursor)
            imContext.deleteSurrounding(target - cursor, 0);

        boolean composing = keepComposing && !newText.isEmpty();
        webView.applyImeEdit(updated, target, target, composing ? start : -1, composing ? cursor : -1);
        return true;
    }

    @Override
    public boolean sendKeyEvent(@NonNull KeyEvent event) {
        return webView.dispatchKeyEvent(event);
    }
}
