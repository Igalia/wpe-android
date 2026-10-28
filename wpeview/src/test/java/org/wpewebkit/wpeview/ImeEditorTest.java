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

    @Test
    public void testDeleteAroundTheCursor() {
        editor.onSurroundingChanged("abcd", 2, 2);
        editor.deleteSurroundingText(1, 1);
        assertSent("delete:0,1", "delete:-1,1");
        assertState("ad", 1, 1);
    }

    @Test
    public void testDeleteIsClampedToTheText() {
        // WebKit would ignore the first delete, and report nothing.
        editor.onSurroundingChanged("ab", 1, 1);
        editor.deleteSurroundingText(5, 5);
        assertSent("delete:0,1", "delete:-1,1");
        assertState("", 0, 0);
    }

    @Test
    public void testDeleteWithNothingToDeleteSendsNothing() {
        editor.onSurroundingChanged("ab", 0, 0);
        editor.deleteSurroundingText(1, 0);
        assertSent();
        assertTrue(scheduled.isEmpty());
    }

    @Test
    public void testOlderReportCanConfirmAnEditThatLooksTheSame() {
        // Known limit: "a", deleted, "a" again, with the report of the first "a" arriving late. It confirms the last
        // edit, and the report for the delete is then applied as it comes, until WebKit catches up.
        editor.commitText("a", 1);
        editor.deleteSurroundingText(1, 0);
        editor.commitText("a", 1);
        editor.onSurroundingChanged("a", 1, 1);
        assertTrue(scheduled.isEmpty());
        editor.onSurroundingChanged("", 0, 0);
        assertState("", 0, 0);
    }

    @Test
    public void testDeleteAroundASelection() {
        // The text after the selection goes first, at an offset from the start of the selection. That collapses
        // the selection at its end, so the text before it is then deleted from there.
        editor.onSurroundingChanged("abcdef", 2, 4);
        editor.deleteSurroundingText(1, 1);
        assertSent("delete:2,1", "delete:-3,1");
        assertState("acdf", 1, 1);

        editor.onSurroundingChanged("acdf", 1, 1);
        editor.onSurroundingChanged("acdf", 1, 3);
        editor.deleteSurroundingText(1, 0);
        assertSent("delete:-1,1");
        assertState("cdf", 0, 0);

        editor.onSurroundingChanged("cdf", 0, 0);
        editor.onSurroundingChanged("cdf", 0, 2);
        editor.deleteSurroundingText(0, 1);
        assertSent("delete:2,1");
        assertState("cd", 2, 2);
    }

    @Test
    public void testDeleteInCodePoints() {
        // "a" followed by U+1F600, two Java chars.
        editor.onSurroundingChanged("a😀b", 3, 3);
        editor.deleteSurroundingTextInCodePoints(1, 5);
        assertSent("delete:0,1", "delete:-2,2");
        assertState("a", 1, 1);
    }

    @Test
    public void testDeleteAfterTheComposingTextWasEmptied() {
        // Backspace over the last letter of the word, then over the space before it. WebKit ended the composition
        // when it got an empty one.
        editor.onSurroundingChanged("hola ", 5, 5);
        editor.setComposingText("m", 1);
        editor.setComposingText("", 1);
        sent.clear();
        editor.deleteSurroundingText(1, 0);
        assertSent("delete:-1,1");
        assertState("hola", 4, 4);
    }

    @Test
    public void testDeleteWhileComposingIsSentAsAsked() {
        editor.onSurroundingChanged("ab", 2, 2);
        editor.setComposingText("c", 1);
        sent.clear();
        editor.deleteSurroundingText(5, 1);
        assertSent("delete:-5,5", "delete:0,1");
        // Counted around the cursor in the composing text.
        editor.onSurroundingChanged("😀", 2, 2);
        editor.deleteSurroundingTextInCodePoints(2, 0);
        assertSent("delete:-3,3");
    }

    @Test
    public void testHiddenFieldDeletesAsAsked() {
        // Nothing to clamp to: WebKit gets what the input method asked for.
        editor.onSurroundingChanged(null, 0, 0);
        editor.deleteSurroundingText(1, 0);
        assertSent("delete:-1,1");
        editor.deleteSurroundingTextInCodePoints(2, 1);
        assertSent("delete:-2,2", "delete:0,1");
    }

    @Test
    public void testTextAroundTheSelection() {
        editor.onSurroundingChanged("hello world", 8, 6);
        assertEquals("hello ", editor.getTextBeforeCursor(10));
        assertEquals("lo ", editor.getTextBeforeCursor(3));
        assertEquals("wo", editor.getSelectedText());
        assertEquals("rl", editor.getTextAfterCursor(2));
        assertEquals("rld", editor.getTextAfterCursor(10));

        editor.onSurroundingChanged("hello world", 5, 5);
        assertEquals(null, editor.getSelectedText());
        assertEquals("", editor.getTextBeforeCursor(0));
    }

    @Test
    public void testTextAroundTheCursorHasTheComposingText() {
        editor.onSurroundingChanged("hello ", 6, 6);
        editor.setComposingText("wor", 1);
        assertEquals("hello wor", editor.getTextBeforeCursor(20));
        editor.setComposingText("wor", 0);
        assertEquals("hello ", editor.getTextBeforeCursor(20));
        assertEquals("wor", editor.getTextAfterCursor(20));
    }

    @Test
    public void testComposingRegionOverAnExistingWord() {
        // The cursor is in "world", and the input method resumes composing it.
        editor.onSurroundingChanged("hello world", 8, 8);
        assertTrue(editor.setComposingRegion(6, 11));
        assertSent("delete:-2,5", "preedit:world,2");
        assertState("hello world", 8, 8);
        assertComposing(6, 11);
        assertEquals("8,8 composing 6,11", lastSelection);

        // What WebKit reports then: the word is in the composition.
        editor.onSurroundingChanged("hello ", 6, 6);
        assertState("hello world", 8, 8);
        assertTrue("The report confirms the delete", scheduled.isEmpty());

        editor.setComposingText("worlds", 1);
        editor.finishComposingText();
        assertSent("preedit:worlds,6", "finish");
        assertState("hello worlds", 12, 12);
    }

    @Test
    public void testComposingRegionWaitsForACommitToBeConfirmed() {
        editor.onSurroundingChanged("hello ", 6, 6);
        editor.commitText("w", 1);
        assertFalse(editor.setComposingRegion(6, 7));
        assertSent("commit:w");
        editor.onSurroundingChanged("hello w", 7, 7);
        assertTrue(editor.setComposingRegion(6, 7));
        assertSent("delete:-1,1", "preedit:w,1");
    }

    @Test
    public void testComposingRegionAfterTheComposingTextWasEmptied() {
        editor.onSurroundingChanged("hola ", 5, 5);
        editor.setComposingText("m", 1);
        editor.setComposingText("", 1);
        sent.clear();
        assertTrue(editor.setComposingRegion(0, 4));
        assertSent("delete:-5,4", "preedit:hola,4");
        assertState("hola ", 4, 4);
        assertComposing(0, 4);
    }

    @Test
    public void testReplaceText() {
        // A correction of an earlier word: composed over and replaced.
        editor.onSurroundingChanged("teh x", 5, 5);
        assertTrue(editor.replaceText(0, 3, "the"));
        assertSent("delete:-5,3", "preedit:teh,3", "commit:the");
        assertState("the x", 3, 3);
        assertComposing(-1, -1);
    }

    @Test
    public void testReplaceTextInsertsOnlyAtTheCursor() {
        editor.onSurroundingChanged("ab", 2, 2);
        assertTrue(editor.replaceText(2, 2, "c"));
        assertSent("commit:c");
        assertState("abc", 3, 3);
        assertFalse(editor.replaceText(1, 1, "x"));
        assertSent();
    }

    @Test
    public void testComposingRegionWhileComposing() {
        editor.onSurroundingChanged("ab ", 3, 3);
        editor.setComposingText("cd", 1);
        sent.clear();
        assertTrue("The region it is composing", editor.setComposingRegion(3, 5));
        assertFalse("Any other region", editor.setComposingRegion(0, 2));
        assertSent();
    }

    @Test
    public void testEmptyComposingRegionFinishesComposing() {
        editor.setComposingText("ab", 1);
        editor.setComposingRegion(1, 1);
        assertSent("preedit:ab,2", "finish");
        assertState("ab", 2, 2);
        assertComposing(-1, -1);
    }

    @Test
    public void testComposingRegionThatCannotBeComposed() {
        editor.onSurroundingChanged("hello", 5, 5);
        assertFalse("Out of the text", editor.setComposingRegion(2, 9));
        editor.onSurroundingChanged("hello", 1, 3);
        assertFalse("Over a selection", editor.setComposingRegion(0, 5));
        editor.onSurroundingChanged(null, 0, 0);
        assertFalse("Without the text", editor.setComposingRegion(0, 1));
        assertSent();
    }

    @Test
    public void testSetSelectionMovesTheCursor() {
        editor.onSurroundingChanged("hello", 5, 5);
        assertTrue(editor.setSelection(2, 2));
        assertSent("delete:-3,0");
        assertState("hello", 2, 2);
        assertEquals("Until WebKit reports it", 1, scheduled.size());
    }

    @Test
    public void testBackspaceWithASelection() {
        // What LatinIME does: move the cursor to the end of the selection, then delete the selection's length
        // before it.
        editor.onSurroundingChanged("hello", 1, 4);
        editor.setSelection(4, 4);
        editor.onSurroundingChanged("hello", 4, 4);
        editor.deleteSurroundingText(3, 0);
        assertSent("delete:3,0", "delete:-3,3");
        assertState("ho", 1, 1);
    }

    @Test
    public void testSetSelectionCannotSelect() {
        editor.onSurroundingChanged("hello", 5, 5);
        assertFalse(editor.setSelection(1, 3));
        assertTrue("Out of range is ignored", editor.setSelection(1, 9));
        assertTrue("Already there", editor.setSelection(5, 5));
        assertSent();
    }

    @Test
    public void testSetSelectionWhileComposing() {
        editor.onSurroundingChanged("ab", 2, 2);
        editor.setComposingText("cd", 1);
        sent.clear();
        assertTrue("Already there", editor.setSelection(4, 4));
        assertFalse(editor.setSelection(3, 3));
        assertFalse(editor.setSelection(0, 0));
        assertSent();
    }

    @Test
    public void testSetSelectionWaitsForACommitToBeConfirmed() {
        editor.onSurroundingChanged("hello", 5, 5);
        editor.commitText("!", 1);
        assertFalse(editor.setSelection(0, 0));
        editor.onSurroundingChanged("hello!", 6, 6);
        assertTrue(editor.setSelection(0, 0));
    }

    @Test
    public void testHiddenFieldIgnoresSetSelection() {
        // Nothing to move the cursor relative to.
        editor.onSurroundingChanged(null, 0, 0);
        assertTrue(editor.setSelection(0, 0));
        assertSent();
    }
}
