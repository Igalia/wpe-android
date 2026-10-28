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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The text of the focused field as the input method sees it, and the edits the input method makes to it.
 * Positions are in Java chars (UTF-16 code units).
 *
 * WebKit reports the text of the field without the composing text (the preedit), with the cursor where the
 * preedit starts. The input method counts the composing text as part of the field, so it is put back in there.
 *
 * Each edit is applied here as soon as it is sent, so that the input method sees it before WebKit reports it back.
 * Several edits can be on their way at once (committing a word, then a space), and WebKit reports the state after
 * each of them. A report that lags behind would take the cursor back, and the input method would take that as the
 * user moving the cursor, and stop composing (LatinIME's RichInputConnection.isBelatedExpectedUpdate). So while an
 * edit is pending, only a report that matches it is applied. If none does within EDIT_CONFIRMATION_TIMEOUT_MS of the
 * last edit, the last report wins. That is also right when an edit was rejected (maxlength, or an input
 * handler that puts the old value back): WebKit doesn't send a report that is the same as the previous one, so no
 * report means the field is as WebKit last reported it.
 *
 * Password and PIN fields don't give out their text: WebKit's report is null. The input method then works blind,
 * as it did before there was any text to go on: edits are sent as asked, and it is told no positions. So it does
 * after a field gets focus, until WebKit reports its text: WebKit drops reports while no field is focused, and
 * doesn't repeat one that hasn't changed, so the text may only come with the next edit.
 *
 * Plain Java, so that it can be tested without a device.
 */
final class ImeEditor {
    static final long EDIT_CONFIRMATION_TIMEOUT_MS = 300;

    /** Sends edits to WebKit. */
    interface Target {
        void commitText(@NonNull String text);
        void deleteSurrounding(int offset, int count);
        void setPreedit(@NonNull String text, int cursorOffset);
        void finishPreedit();
    }

    /** Runs the edit confirmation timeout. */
    interface Scheduler {
        void postDelayed(@NonNull Runnable task, long delayMillis);
        void cancel(@NonNull Runnable task);
    }

    /** Tells the input method where the selection and the composing region are. */
    interface SelectionListener {
        void onSelectionChanged(int selectionStart, int selectionEnd, int composingStart, int composingEnd);
    }

    private final Target target;
    private final Scheduler scheduler;
    private final SelectionListener selectionListener;

    // The field without the preedit, as WebKit reported it, with the edits it has not reported back yet.
    private String text = "";
    private int selectionStart = 0;
    private int selectionEnd = 0;
    // The composing text, or null, and the cursor in it. It is at selectionStart: WebKit replaces the selection with
    // the composition.
    @Nullable
    private String preedit = null;
    private int preeditCursor = 0;
    // What WebKit reported last. Edits don't change it.
    private String reportedText = "";
    private int reportedSelectionStart = 0;
    private int reportedSelectionEnd = 0;
    private boolean editPending = false;
    // The text of the field is not known: it is a password field, or WebKit hasn't reported it yet.
    private boolean hidden = true;
    private final Runnable editConfirmationTimeout = () -> {
        editPending = false;
        applyReport();
    };

    ImeEditor(@NonNull Target target, @NonNull Scheduler scheduler, @NonNull SelectionListener selectionListener) {
        this.target = target;
        this.scheduler = scheduler;
        this.selectionListener = selectionListener;
    }

    /** The text of the field, with the composing text in it. */
    @NonNull
    String getText() {
        return preedit != null ? text.substring(0, selectionStart) + preedit + text.substring(selectionStart) : text;
    }

    int getSelectionStart() { return preedit != null ? selectionStart + preeditCursor : selectionStart; }

    int getSelectionEnd() { return preedit != null ? selectionStart + preeditCursor : selectionEnd; }

    int getComposingStart() { return isComposing() ? selectionStart : -1; }

    int getComposingEnd() { return isComposing() ? selectionStart + preedit.length() : -1; }

    /** Whether the text of the field is known: false for password fields, and until WebKit reports the text. */
    boolean isTextKnown() { return !hidden; }

    /**
     * WebKit reports the text of the focused field, without the preedit. The cursor and the anchor are indices into
     * it. The text is null for password and PIN fields.
     */
    void onSurroundingChanged(@Nullable String text, int cursor, int anchor) {
        if (text == null) {
            forgetText();
            return;
        }
        // WebKit keeps a space typed at the end of a contenteditable element as a no-break space, so the report would
        // never match the space that was typed. LatinIME doesn't take a no-break space for a word separator either.
        text = text.replace('\u00A0', ' ');
        hidden = false;
        reportedText = text;
        reportedSelectionStart = clamp(Math.min(cursor, anchor), 0, text.length());
        reportedSelectionEnd = clamp(Math.max(cursor, anchor), 0, text.length());
        if (editPending) {
            if (!reportedText.equals(this.text) || reportedSelectionStart != selectionStart ||
                reportedSelectionEnd != selectionEnd)
                return;
            // A report can also match an older edit, when the same state comes up twice in a row ("a", "", "a").
            // The reports for the edits after it are then applied as they come, until WebKit catches up.
            editPending = false;
            scheduler.cancel(editConfirmationTimeout);
        }
        applyReport();
    }

    /**
     * A field lost or got focus. Nothing carries over: WebKit forgets what it reported too, and reports the next field
     * from scratch, and WPEInputMethodContextAndroid forgets its preedit, since WebKit ignores a preedit while no
     * field is focused.
     */
    void reset() {
        forgetText();
        clearPreedit();
    }

    /** WebKit ended the composition itself and kept its text, as finishComposingText() does. */
    void onPreeditReset() {
        if (preedit == null)
            return;
        String committed = preedit;
        clearPreedit();
        replaceSelection(committed);
        notifySelectionChanged();
    }

    boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
        String newText = text != null ? text.toString() : "";
        // WebKit's commit replaces the preedit, or the selection when nothing is being composed. It ignores an empty
        // commit then, so a selection is deleted instead.
        if (preedit == null && newText.isEmpty()) {
            if (!hidden && selectionEnd > selectionStart) {
                target.deleteSurrounding(0, selectionEnd - selectionStart);
                replaceSelection("");
                notifySelectionChanged();
            }
            return true;
        }
        target.commitText(newText);
        clearPreedit();
        // newCursorPosition is not followed: WebKit leaves the cursor after the committed text.
        replaceSelection(newText);
        notifySelectionChanged();
        return true;
    }

    boolean setComposingText(@Nullable CharSequence text, int newCursorPosition) {
        String newText = text != null ? text.toString() : "";
        // An empty preedit is only kept while composing, as WPEInputMethodContextAndroid does.
        if (preedit == null && newText.isEmpty())
            return true;
        int cursor = preeditCursorOffset(newText.length(), newCursorPosition);
        target.setPreedit(newText, cursor);
        if (preedit == null)
            replaceSelection("");
        preedit = newText;
        preeditCursor = cursor;
        notifySelectionChanged();
        return true;
    }

    boolean finishComposingText() {
        target.finishPreedit();
        if (preedit == null)
            return true;
        // WebKit commits the preedit text and leaves the cursor after it.
        String committed = preedit;
        clearPreedit();
        replaceSelection(committed);
        notifySelectionChanged();
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

    private boolean isComposing() { return preedit != null && !preedit.isEmpty(); }

    private void clearPreedit() {
        preedit = null;
        preeditCursor = 0;
    }

    // Replaces the selection with text, as WebKit does, and leaves the cursor after it.
    private void replaceSelection(@NonNull String newText) {
        // Nothing changes then, and WebKit wouldn't report anything.
        if (hidden || (newText.isEmpty() && selectionStart == selectionEnd))
            return;
        recordEdit(text.substring(0, selectionStart) + newText + text.substring(selectionEnd),
                   selectionStart + newText.length(), selectionStart + newText.length());
    }

    private void recordEdit(@NonNull String text, int selectionStart, int selectionEnd) {
        setText(text, selectionStart, selectionEnd);
        // Counted from the last edit: WebKit reports the edits in order, so while they keep coming, the report for
        // the last one is still on its way, however slow the page is.
        editPending = true;
        scheduler.cancel(editConfirmationTimeout);
        scheduler.postDelayed(editConfirmationTimeout, EDIT_CONFIRMATION_TIMEOUT_MS);
    }

    private void applyReport() {
        setText(reportedText, reportedSelectionStart, reportedSelectionEnd);
        notifySelectionChanged();
    }

    private void forgetText() {
        hidden = true;
        editPending = false;
        scheduler.cancel(editConfirmationTimeout);
        reportedText = "";
        reportedSelectionStart = reportedSelectionEnd = 0;
        setText("", 0, 0);
    }

    private void setText(@NonNull String text, int selectionStart, int selectionEnd) {
        this.text = text;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
    }

    private void notifySelectionChanged() {
        if (!hidden)
            selectionListener.onSelectionChanged(getSelectionStart(), getSelectionEnd(), getComposingStart(),
                                                 getComposingEnd());
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
}
