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

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImeEditorTest {
    // What the editor sent to WebKit, as "commit:<text>", "delete:<offset>,<count>", "preedit:<text>,<cursor>" and
    // "finish".
    private final List<String> sent = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private int timeoutsStarted = 0;
    private String lastSelection = "";
    private ImeEditor editor;

    @Before
    public void setUp() {
        editor = new ImeEditor(
            new ImeEditor.Target() {
                @Override
                public void commitText(@NonNull String text) {
                    sent.add("commit:" + text);
                }
                @Override
                public void deleteSurrounding(int offset, int count) {
                    sent.add("delete:" + offset + "," + count);
                }
                @Override
                public void setPreedit(@NonNull String text, int cursorOffset) {
                    sent.add("preedit:" + text + "," + cursorOffset);
                }
                @Override
                public void finishPreedit() {
                    sent.add("finish");
                }
            },
            new ImeEditor.Scheduler() {
                @Override
                public void postDelayed(@NonNull Runnable task, long delayMillis) {
                    assertEquals(ImeEditor.EDIT_CONFIRMATION_TIMEOUT_MS, delayMillis);
                    scheduled.add(task);
                    timeoutsStarted++;
                }
                @Override
                public void cancel(@NonNull Runnable task) {
                    scheduled.remove(task);
                }
            },
            (selectionStart, selectionEnd, composingStart, composingEnd) -> {
                lastSelection =
                    selectionStart + "," + selectionEnd + " composing " + composingStart + "," + composingEnd;
            });
        // A focused empty field.
        editor.onSurroundingChanged("", 0, 0);
        lastSelection = "";
    }

    private void runTimeout() {
        assertEquals("Expected one pending timeout", 1, scheduled.size());
        scheduled.remove(0).run();
    }

    private void assertSent(String... expected) {
        assertEquals(Arrays.asList(expected), sent);
        sent.clear();
    }

    // The field as the input method sees it, with the composing text in it.
    private void assertState(String text, int selectionStart, int selectionEnd) {
        assertEquals(text, editor.getText());
        assertEquals(selectionStart, editor.getSelectionStart());
        assertEquals(selectionEnd, editor.getSelectionEnd());
    }

    private void assertComposing(int start, int end) {
        assertEquals(start, editor.getComposingStart());
        assertEquals(end, editor.getComposingEnd());
    }

    @Test
    public void testComposingTextIsAPreedit() {
        editor.onSurroundingChanged("ab", 2, 2);
        editor.setComposingText("h", 1);
        editor.setComposingText("he", 1);
        assertSent("preedit:h,1", "preedit:he,2");
        assertState("abhe", 4, 4);
        assertComposing(2, 4);
        assertEquals("4,4 composing 2,4", lastSelection);
    }

    @Test
    public void testReportLeavesOutThePreedit() {
        // WebKit reports the field without the composition, with the cursor at its start.
        editor.setComposingText("xy", 1);
        editor.onSurroundingChanged("ab", 1, 1);
        assertState("axyb", 3, 3);
        assertComposing(1, 3);
    }

    @Test
    public void testPreeditCursor() {
        editor.setComposingText("abc", 0);
        assertSent("preedit:abc,0");
        assertState("abc", 0, 0);
        editor.setComposingText("abc", -5);
        editor.setComposingText("abc", 5);
        assertSent("preedit:abc,0", "preedit:abc,3");
    }

    @Test
    public void testCommitWhileComposingReplacesThePreedit() {
        editor.onSurroundingChanged("ab", 2, 2);
        editor.setComposingText("hel", 1);
        editor.commitText("hello", 1);
        assertSent("preedit:hel,3", "commit:hello");
        assertState("abhello", 7, 7);
        assertComposing(-1, -1);

        editor.setComposingText("x", 1);
        editor.commitText("", 1);
        assertSent("preedit:x,1", "commit:");
        assertState("abhello", 7, 7);
    }

    @Test
    public void testFinishComposingTextKeepsTheText() {
        editor.setComposingText("hi", 0);
        editor.finishComposingText();
        assertSent("preedit:hi,0", "finish");
        // WebKit leaves the cursor after the committed text.
        assertState("hi", 2, 2);
        assertEquals("2,2 composing -1,-1", lastSelection);
    }

    @Test
    public void testEmptyComposingText() {
        // Like WPEInputMethodContextAndroid: an empty preedit doesn't start a composition, but it is kept during one.
        editor.setComposingText("", 1);
        assertSent();
        editor.setComposingText("a", 1);
        editor.setComposingText("", 1);
        assertSent("preedit:a,1", "preedit:,0");
        assertComposing(-1, -1);
        editor.finishComposingText();
        assertSent("finish");
        assertState("", 0, 0);
    }

    @Test
    public void testPreeditReplacesTheSelection() {
        editor.onSurroundingChanged("hello", 1, 4);
        editor.setComposingText("i", 1);
        assertState("hio", 2, 2);
        assertComposing(1, 2);
        // What WebKit reports then.
        editor.onSurroundingChanged("ho", 1, 1);
        assertState("hio", 2, 2);
    }

    @Test
    public void testCommitReplacesTheSelection() {
        editor.onSurroundingChanged("hello", 1, 4);
        editor.commitText("i", 1);
        assertSent("commit:i");
        assertState("hio", 2, 2);

        editor.onSurroundingChanged("hio", 2, 2);
        editor.onSurroundingChanged("hio", 0, 3);
        editor.commitText("", 1);
        assertSent("delete:0,3");
        assertState("", 0, 0);
    }

    @Test
    public void testEditIsSeenBeforeWebKitReportsIt() {
        editor.commitText("a", 1);
        editor.commitText("b", 1);
        assertState("ab", 2, 2);
        assertEquals("2,2 composing -1,-1", lastSelection);
    }

    @Test
    public void testReportsInBetweenAreNotAppliedWhileAnEditIsPending() {
        // LatinIME commits a word, then a space, and starts the next word. Applying the report for the word would
        // take the cursor back, and LatinIME would stop composing: on a device, "world" was then typed letter by
        // letter.
        editor.setComposingText("hello", 1);
        editor.commitText("hello", 1);
        editor.commitText(" ", 1);
        editor.setComposingText("w", 1);
        editor.onSurroundingChanged("hello", 5, 5);
        assertState("hello w", 7, 7);
        assertEquals("7,7 composing 6,7", lastSelection);

        editor.onSurroundingChanged("hello ", 6, 6);
        assertTrue("A matching report confirms the edit", scheduled.isEmpty());
        editor.onSurroundingChanged("hello x", 7, 7);
        assertState("hello xw", 8, 8);
    }

    @Test
    public void testTimeoutGoesBackToTheLastReportWhenAnEditIsRejected() {
        // <input maxlength=3>: WebKit rejects the "d", and sends no report, since the field is the same as it last
        // reported.
        editor.onSurroundingChanged("abc", 3, 3);
        editor.commitText("d", 1);
        assertState("abcd", 4, 4);
        runTimeout();
        assertState("abc", 3, 3);
        assertEquals("3,3 composing -1,-1", lastSelection);
    }

    @Test
    public void testTimeoutCountsFromTheLastEdit() {
        editor.commitText("a", 1);
        editor.commitText("b", 1);
        assertEquals("The second edit starts the timeout again", 2, timeoutsStarted);
        assertEquals(1, scheduled.size());
        editor.onSurroundingChanged("a", 1, 1);
        runTimeout();
        assertState("a", 1, 1);
    }

    @Test
    public void testNoBreakSpaceIsReportedAsASpace() {
        // A space typed at the end of a contenteditable element.
        editor.commitText("hello ", 1);
        editor.onSurroundingChanged("hello\u00A0", 6, 6);
        assertTrue("The report confirms the edit", scheduled.isEmpty());
        assertState("hello ", 6, 6);
    }

    @Test
    public void testComposingIsNotAnEdit() {
        // WebKit's report leaves the composition out, so composing changes nothing it reports.
        editor.setComposingText("a", 1);
        editor.setComposingText("ab", 1);
        assertTrue(scheduled.isEmpty());
    }

    @Test
    public void testResetForgetsTheField() {
        editor.onSurroundingChanged("abc", 3, 3);
        editor.commitText("d", 1);
        editor.setComposingText("e", 1);
        editor.reset();
        assertState("", 0, 0);
        assertComposing(-1, -1);
        assertFalse(editor.isTextKnown());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    public void testPreeditResetKeepsTheText() {
        // WebKit confirmed the composition, so the field keeps its text, and the report for it confirms the edit.
        editor.onSurroundingChanged("abc", 3, 3);
        editor.setComposingText("d", 1);
        editor.onPreeditReset();
        assertSent("preedit:d,1");
        assertComposing(-1, -1);
        assertState("abcd", 4, 4);
        assertEquals("4,4 composing -1,-1", lastSelection);
        editor.onSurroundingChanged("abcd", 4, 4);
        assertTrue(scheduled.isEmpty());
    }

    @Test
    public void testTextIsUnknownUntilWebKitReportsIt() {
        // After a focus change, WebKit may not report the text until it changes: it drops reports while no field
        // is focused, and doesn't send one that is the same as the last. Until then, edits are sent as they come.
        editor.reset();
        editor.commitText("a", 1);
        assertSent("commit:a");
        assertState("", 0, 0);
        assertEquals("", lastSelection);
        assertTrue("Nothing is predicted", scheduled.isEmpty());

        editor.onSurroundingChanged("xa", 2, 2);
        assertState("xa", 2, 2);
    }

    @Test
    public void testHiddenFieldSendsEditsAsTheyCome() {
        // A password field: the reports are all null, and the input method is told nothing.
        editor.onSurroundingChanged(null, 0, 0);
        editor.setComposingText("p", 1);
        editor.commitText("pa", 1);
        editor.setComposingText("s", 1);
        editor.finishComposingText();
        assertSent("preedit:p,1", "commit:pa", "preedit:s,1", "finish");
        assertState("", 0, 0);
        assertEquals("", lastSelection);
        assertTrue("Nothing is predicted", scheduled.isEmpty());
    }

    @Test
    public void testFieldThatStopsHidingItsTextIsTrackedAgain() {
        editor.onSurroundingChanged("abc", 3, 3);
        editor.onSurroundingChanged(null, 0, 0);
        assertState("", 0, 0);

        editor.onSurroundingChanged("abc", 3, 3);
        editor.commitText("d", 1);
        assertState("abcd", 4, 4);
    }
}
