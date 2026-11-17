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

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebPageTestHelper.TIMEOUT_SECONDS;
import static org.wpewebkit.wpeview.WebPageTestHelper.evaluate;
import static org.wpewebkit.wpeview.WebPageTestHelper.loadHtml;
import static org.wpewebkit.wpeview.WebPageTestHelper.newPage;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMain;
import static org.wpewebkit.wpeview.WebPageTestHelper.onMainGet;
import static org.wpewebkit.wpeview.WebPageTestHelper.waitForResult;

/**
 * Find in page: a search highlights the matches of a text and selects one, and its listener gets the number of matches
 * for the search and for each step to the next or the previous match, until another search starts or the search ends.
 */
@RunWith(AndroidJUnit4.class)
public class WebPageFindTest {
    // Three matches of "foo": one split by a tag, one far below, and one in hidden text, which is not found.
    private static final String MATCHES =
        "<meta name=viewport content='width=device-width'><p id=a>Foo one</p><p id=b>two f<b>o</b>o</p>"
        + "<p style='display:none'>foo hidden</p><p id=c style='margin-top:3000px'>three FOO</p>";
    // The paragraph of the selected match, which is the current one.
    private static final String CURRENT = "getSelection().anchorNode?.parentElement.closest('p')?.id ?? null";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> activityRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private WebContext context;
    private final List<WebPage> pages = new ArrayList<>();
    private final BlockingQueue<String> results = new LinkedBlockingQueue<>();
    // The number of matches, with a + when there are more. WebKit does not report the selected match yet, so a known
    // one shows as "#n " in front, which no expected result has.
    private final WebPage.FindListener listener =
        (ordinal, count, more) -> results.add((ordinal == -1 ? "" : "#" + ordinal + " ") + count + (more ? "+" : ""));

    @Before
    public void setUp() {
        activityRule.getScenario().onActivity(activity -> context = new WebContext(activity, false, true));
    }

    @After
    public void tearDown() {
        onMain(() -> {
            for (WebPage page : pages)
                page.destroy();
            pages.clear();
            context.destroy();
        });
    }

    private WebPage loadedPage(WebPageTestHelper.RecordingClient client, String html) throws InterruptedException {
        WebPage page = newPage(context, client);
        pages.add(page);
        loadHtml(page, client, html);
        return page;
    }

    private WebPage loadedPage(String html) throws InterruptedException {
        return loadedPage(new WebPageTestHelper.RecordingClient(), html);
    }

    private String nextResult() throws InterruptedException {
        String result = results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("No find result", result);
        return result;
    }

    // WebKit answers in a few milliseconds, so a result that has not come in a second does not come.
    private void assertNoMoreResults() throws InterruptedException {
        assertNull("An unexpected find result", results.poll(1, TimeUnit.SECONDS));
    }

    private void assertCurrentMatch(@NonNull WebPage page, @NonNull String id) throws InterruptedException {
        assertEquals("\"" + id + "\"", waitForResult(page, CURRENT, "\"" + id + "\""));
    }

    @Test
    public void testFindsTheMatchesAndGoesRoundThem() throws Exception {
        WebPage page = loadedPage(MATCHES);
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
        assertCurrentMatch(page, "a");
        onMain(() -> page.findNext(true));
        assertEquals("3", nextResult());
        assertCurrentMatch(page, "b");
        onMain(() -> page.findNext(true));
        assertEquals("3", nextResult());
        assertCurrentMatch(page, "c");
        onMain(() -> page.findNext(true));
        assertEquals("3", nextResult());
        assertCurrentMatch(page, "a");
        onMain(() -> page.findNext(false));
        assertEquals("3", nextResult());
        assertCurrentMatch(page, "c");
        assertNoMoreResults();
    }

    @Test
    public void testOptions() throws Exception {
        WebPage page = loadedPage("<p>Find</p><p>find</p><p>unfind</p>");
        onMain(() -> page.findAllAsync("find", 0, listener));
        assertEquals("3", nextResult());
        onMain(() -> page.findAllAsync("find", WebPage.FIND_CASE_SENSITIVE, listener));
        assertEquals("2", nextResult());
        onMain(() -> page.findAllAsync("find", WebPage.FIND_AT_WORD_STARTS, listener));
        assertEquals("2", nextResult());
        onMain(() -> page.findAllAsync("find", WebPage.FIND_CASE_SENSITIVE | WebPage.FIND_AT_WORD_STARTS, listener));
        assertEquals("1", nextResult());
        assertThrows(IllegalArgumentException.class, () -> onMain(() -> page.findAllAsync("find", 1 << 5, listener)));
    }

    @Test
    public void testCountsTheMatchesInFramesButNotInHiddenText() throws Exception {
        WebPage page = loadedPage("<p>foo</p><iframe srcdoc='<p>foo</p><p>foo</p>'></iframe>"
                                  + "<p style='visibility:hidden'>foo</p><p hidden>foo</p>");
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
    }

    @Test
    public void testSaysWhenThePageHasMoreMatchesThanItCounts() throws Exception {
        StringBuilder html = new StringBuilder("<p>");
        for (int i = 0; i < WebPage.MAX_FIND_MATCHES + 100; i++)
            html.append("many ");
        WebPage page = loadedPage(html.toString());
        onMain(() -> page.findAllAsync("many", 0, listener));
        assertEquals(WebPage.MAX_FIND_MATCHES + "+", nextResult());
        onMain(() -> page.findNext(true));
        assertEquals(WebPage.MAX_FIND_MATCHES + "+", nextResult());
    }

    @Test
    public void testNoMatchAndEmptyText() throws Exception {
        WebPage page = loadedPage(MATCHES);
        onMain(() -> page.findAllAsync("nothing like this", 0, listener));
        assertEquals("0", nextResult());
        // A step searches again, and finds nothing either.
        onMain(() -> page.findNext(true));
        assertEquals("0", nextResult());

        // An empty text ends the search, and has no match.
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
        onMain(() -> page.findAllAsync("", 0, listener));
        assertEquals("0", nextResult());
        onMain(() -> page.findNext(true));
        assertNoMoreResults();
    }

    /** Only the last search is answered: its listener hears neither the searches before nor their steps. */
    @Test
    public void testOnlyTheLastSearchIsAnswered() throws Exception {
        WebPage page = loadedPage(MATCHES);
        BlockingQueue<String> earlier = new LinkedBlockingQueue<>();
        onMain(() -> {
            page.findAllAsync("t", 0, (ordinal, count, more) -> earlier.add("t " + count));
            page.findNext(true);
            page.findAllAsync("tw", 0, (ordinal, count, more) -> earlier.add("tw " + count));
            page.findAllAsync("two", 0, listener);
        });
        assertEquals("1", nextResult());
        assertNoMoreResults();
        assertTrue("Answers of earlier searches: " + earlier, earlier.isEmpty());
        assertCurrentMatch(page, "b");
    }

    /** Clearing the matches ends the search: its listener hears nothing more, and steps do nothing until another. */
    @Test
    public void testClearMatchesEndsTheSearch() throws Exception {
        WebPage page = loadedPage(MATCHES);
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
        onMain(() -> {
            page.findNext(true);
            page.clearMatches();
        });
        assertNoMoreResults();
        assertCurrentMatch(page, "b");
        onMain(() -> page.findNext(true));
        assertNoMoreResults();
        // The selection of the last match stays, and a new search goes on from it.
        assertCurrentMatch(page, "b");
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
    }

    @Test
    public void testStepsDoNothingBeforeASearch() throws Exception {
        WebPage page = loadedPage(MATCHES);
        onMain(() -> {
            page.findNext(true);
            page.findNext(false);
            page.clearMatches();
        });
        assertNoMoreResults();
    }

    /** A page that loads another document has none of the matches of the last one: a step searches the new one. */
    @Test
    public void testSearchesTheNewDocumentAfterALoad() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, MATCHES);
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
        loadHtml(page, client, "<p id=x>foo</p><p id=y>foo</p>");
        onMain(() -> page.findNext(true));
        assertEquals("2", nextResult());
        assertCurrentMatch(page, "x");
    }

    /** Each step searches the page again, so that the number of matches follows its changes. */
    @Test
    public void testStepsFollowTheChangesOfThePage() throws Exception {
        WebPage page = loadedPage(MATCHES);
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
        evaluate(page, "document.body.insertAdjacentHTML('beforeend', '<p id=d>foo</p>')");
        onMain(() -> page.findNext(false));
        assertEquals("4", nextResult());
        assertCurrentMatch(page, "d");
    }

    /** A step highlights all the matches of a new document, not only the one it selects. */
    @Test
    public void testStepsHighlightTheMatchesOfANewDocument() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        String twoMatches = "<meta name=viewport content='width=device-width'><body style='font:40px sans-serif'>"
                            + "<p>foo</p><p style='margin-top:100px'>foo</p>";
        WebPage page = loadedPage(client, twoMatches);
        onMain(() -> page.findAllAsync("foo", 0, listener));
        assertEquals("2", nextResult());
        assertEquals(2, highlightedLines(page));
        loadHtml(page, client, twoMatches);
        assertEquals(0, highlightedLines(page));
        onMain(() -> page.findNext(true));
        assertEquals("2", nextResult());
        assertEquals(2, highlightedLines(page));
    }

    // The bands of lines of the visible part of the page that have yellow, the color of the matches of a search.
    private static int highlightedLines(@NonNull WebPage page) throws InterruptedException {
        Bitmap bitmap = WebPageTestHelper.snapshot(page::captureSnapshot);
        assertNotNull("No snapshot", bitmap);
        int bands = 0;
        boolean inBand = false;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            boolean yellow = false;
            for (int x = 0; x < bitmap.getWidth() && !yellow; x++) {
                int pixel = bitmap.getPixel(x, y);
                yellow = Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) < 80;
            }
            if (yellow && !inBand)
                bands++;
            inBand = yellow;
        }
        return bands;
    }

    /**
     * WebKit answers a search also when the web process ends meanwhile, and a step with no web process finds nothing.
     * A step after the next load searches the new document.
     */
    @Test
    public void testWebProcessEndsDuringASearch() throws Exception {
        BlockingQueue<Integer> ended = new LinkedBlockingQueue<>();
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient() {
            @Override
            public void onWebProcessTerminated(@NonNull WebPage page, int reason) {
                ended.add(reason);
            }
        };
        WebPage page = loadedPage(client, MATCHES);
        onMain(() -> {
            page.findAllAsync("foo", 0, listener);
            page.terminateWebProcess();
        });
        nextResult();
        assertNotNull("The web process did not end", ended.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        onMain(() -> page.findNext(true));
        assertEquals("0", nextResult());
        loadHtml(page, client, MATCHES);
        onMain(() -> page.findNext(true));
        assertEquals("3", nextResult());
        onMain(() -> page.findAllAsync("two", 0, listener));
        assertEquals("1", nextResult());
        assertNoMoreResults();
    }

    @Test
    public void testDestroyedPageFindsNothing() throws Exception {
        WebPage page = loadedPage(MATCHES);
        onMain(() -> {
            page.findAllAsync("foo", 0, listener);
            page.destroy();
            page.findAllAsync("foo", 0, listener);
            page.findNext(true);
            page.clearMatches();
        });
        assertNoMoreResults();
    }

    /** A destroyed page lets go of the listener of its search, which may hold a view or an activity. */
    @Test
    public void testDestroyedPageLetsGoOfTheListener() throws Exception {
        WebPage page = loadedPage(MATCHES);
        WeakReference<WebPage.FindListener> weakListener = searchWithOwnListener(page);
        assertEquals("3", nextResult());
        onMain(page::destroy);
        for (int i = 0; i < 20 && weakListener.get() != null; i++) {
            Runtime.getRuntime().gc();
            Thread.sleep(100);
        }
        assertNull("The destroyed page keeps the listener", weakListener.get());
    }

    // Starts a search with a listener that only the page holds, so that no register of the test keeps it.
    private WeakReference<WebPage.FindListener> searchWithOwnListener(@NonNull WebPage page) {
        WebPage.FindListener ownListener = (ordinal, count, more) -> results.add(String.valueOf(count));
        onMain(() -> page.findAllAsync("foo", 0, ownListener));
        return new WeakReference<>(ownListener);
    }

    @Test
    public void testFindOffTheMainThreadThrows() throws Exception {
        WebPage page = loadedPage(MATCHES);
        assertThrows(IllegalStateException.class, () -> page.findAllAsync("foo", 0, listener));
        assertThrows(IllegalStateException.class, () -> page.findNext(true));
        assertThrows(IllegalStateException.class, page::clearMatches);
    }

    /** A view finds in the page it shows, and scrolls the current match into view. */
    @Test
    public void testViewFindsInItsPageAndScrollsToTheMatch() throws Exception {
        WebPageTestHelper.RecordingClient client = new WebPageTestHelper.RecordingClient();
        WebPage page = loadedPage(client, MATCHES);
        WebViewTestActivity activity = activity();
        WebView view = onMainGet(() -> {
            activity.getWebView().setPage(page);
            return activity.getWebView();
        });
        onMain(() -> view.findAllAsync("foo", 0, listener));
        assertEquals("3", nextResult());
        assertEquals("0", evaluate(page, "scrollY"));
        onMain(() -> view.findNext(false));
        assertEquals("3", nextResult());
        assertCurrentMatch(page, "c");
        assertTrue("The match far below is not scrolled into view", Integer.parseInt(evaluate(page, "scrollY")) > 0);

        // The search belongs to the page, which answers it also once it left the view.
        onMain(() -> {
            view.findNext(true);
            view.setPage(null);
        });
        assertEquals("3", nextResult());
        onMain(() -> view.findNext(true));
        assertNoMoreResults();
    }

    @Test
    public void testViewWithoutPageFindsNothing() throws Exception {
        WebViewTestActivity activity = activity();
        onMain(() -> {
            WebView view = WebView.createEmpty(activity);
            view.findAllAsync("foo", 0, listener);
            view.findNext(true);
            view.clearMatches();
            view.destroy();
        });
        assertNoMoreResults();
    }

    private WebViewTestActivity activity() {
        WebViewTestActivity[] activity = new WebViewTestActivity[1];
        activityRule.getScenario().onActivity(current -> activity[0] = current);
        return activity[0];
    }
}
