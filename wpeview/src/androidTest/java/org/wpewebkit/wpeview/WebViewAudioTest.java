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

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.wpewebkit.wpeview.WebViewActivityScenarioHelper.silentWavUrl;

/**
 * WebView.isPlayingAudio and WebChromeClient.onPlayingAudioChanged, which tell whether the page plays sound, and
 * WebView.setAudioMuted, which mutes it.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewAudioTest {
    private static final long TIMEOUT_SECONDS = 30;
    // Long enough for a report that should not come to arrive.
    private static final long QUIET_MILLISECONDS = 1000;
    private static final String PLAYING = "\"playing\"";
    private static final String PAUSED = "\"paused\"";

    // audio plays silence in a loop, which WebKit counts as sound: it has an audio track and it is neither muted nor at
    // volume 0. started becomes true when the promise of play() resolves. tone() plays a quiet tone with Web Audio.
    private static final String AUDIO_PAGE = "<html><body><script>var audio = new Audio('" + silentWavUrl() + "');\n"
                                             + "audio.loop = true;\n"
                                             + "var started = false;\n"
                                             + "function play() { audio.play().then(() => started = true); }\n"
                                             + "function state() { return audio.paused ? 'paused' : 'playing'; }\n"
                                             + "var context;\n"
                                             + "function tone() {\n"
                                             + "    context = new AudioContext();\n"
                                             + "    var oscillator = new OscillatorNode(context);\n"
                                             + "    oscillator.connect(new GainNode(context, {gain: 0.01}))\n"
                                             + "        .connect(context.destination);\n"
                                             + "    oscillator.start();\n"
                                             + "}\n"
                                             + "</script></body></html>";

    @Rule
    public ActivityScenarioRule<WebViewTestActivity> webViewActivityTestRule =
        new ActivityScenarioRule<>(WebViewTestActivity.class);

    private ActivityScenario<WebViewTestActivity> scenario;
    // The reports of onPlayingAudioChanged, in order, with what isPlayingAudio returned then.
    private final BlockingQueue<String> playingReports = new LinkedBlockingQueue<>();

    @Before
    public void setUp() {
        scenario = webViewActivityTestRule.getScenario();
        scenario.onActivity(activity -> activity.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPlayingAudioChanged(@NonNull WebView view, boolean isPlayingAudio) {
                playingReports.add(report(isPlayingAudio, view.isPlayingAudio()));
            }
        }));
    }

    private void loadAudioPage() throws Throwable {
        WebViewActivityScenarioHelper.loadHtmlSync(scenario, new TestWebViewClient(), AUDIO_PAGE);
        // WebKit admits a playback some time after play(), and only for media that can already play.
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                 scenario, "audio.readyState >= audio.HAVE_FUTURE_DATA", "true"));
    }

    private String run(String script) throws Throwable {
        return WebViewActivityScenarioHelper.evaluateJavaScriptAndWaitForResult(scenario, new TestWebViewClient(),
                                                                                script);
    }

    private void play() throws Throwable {
        run("play(); 0");
        assertEquals("true", WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "started", "true"));
        assertEquals(PLAYING, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state()", PLAYING));
    }

    private static String report(boolean isPlayingAudio, boolean getter) {
        return "reported " + isPlayingAudio + ", isPlayingAudio " + getter;
    }

    private void assertReported(boolean playing) throws InterruptedException {
        assertEquals(report(playing, playing), playingReports.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertPlaying(playing);
    }

    private void assertNothingReported() throws InterruptedException {
        assertNull(playingReports.poll(QUIET_MILLISECONDS, TimeUnit.MILLISECONDS));
    }

    private void assertPlaying(boolean playing) {
        scenario.onActivity(activity -> assertEquals(playing, activity.getWebView().isPlayingAudio()));
    }

    private void takeOutOfTheWindow(AtomicReference<ViewGroup> parent) {
        scenario.onActivity(activity -> {
            WebView webView = activity.getWebView();
            parent.set((ViewGroup)webView.getParent());
            parent.get().removeView(webView);
        });
    }

    private void assertMuted(boolean muted) {
        scenario.onActivity(activity -> assertEquals(muted, activity.getWebView().isAudioMuted()));
    }

    /** The page plays sound from when its media starts until it pauses. */
    @Test
    public void testPlayingAudio() throws Throwable {
        loadAudioPage();
        assertPlaying(false);

        play();
        assertReported(true);

        run("audio.pause(); 0");
        assertReported(false);
    }

    /** Media at volume 0 makes no sound. */
    @Test
    public void testSilentMediaPlaysNoSound() throws Throwable {
        loadAudioPage();
        run("audio.volume = 0; 0");

        play();
        assertNothingReported();
        assertPlaying(false);
    }

    /** Web Audio plays sound too. */
    @Test
    public void testWebAudio() throws Throwable {
        loadAudioPage();

        run("tone(); 0");
        assertReported(true);

        run("context.close(); 0");
        assertReported(false);
    }

    /** Web Audio is not paused in a hidden page, so a hidden page may play sound. */
    @Test
    public void testHiddenPagePlaysWebAudio() throws Throwable {
        scenario.onActivity(
            activity -> activity.getWebView().getSettings().setAudiblePlaybackRequiresPageVisibility(true));
        loadAudioPage();
        run("tone(); 0");
        assertReported(true);

        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        takeOutOfTheWindow(parent);
        try {
            assertEquals("\"hidden\"", WebViewActivityScenarioHelper.waitForJavaScriptResult(
                                           scenario, "document.visibilityState", "\"hidden\""));
            assertNothingReported();
            assertPlaying(true);
        } finally {
            scenario.onActivity(activity -> parent.get().addView(activity.getWebView()));
        }
    }

    /** Media that the page muted counts as sound, which the user would hear once it is unmuted. */
    @Test
    public void testMediaMutedByThePage() throws Throwable {
        loadAudioPage();
        run("audio.muted = true; 0");

        play();
        assertReported(true);
    }

    /** A muted view still plays: the user does not hear it, and the app can show that. */
    @Test
    public void testMutedViewPlaysAudio() throws Throwable {
        loadAudioPage();
        assertMuted(false);
        scenario.onActivity(activity -> activity.getWebView().setAudioMuted(true));
        assertMuted(true);

        play();
        assertReported(true);
        // The page does not see that it is muted.
        assertEquals("false", run("audio.muted"));

        scenario.onActivity(activity -> activity.getWebView().setAudioMuted(false));
        assertMuted(false);
        assertNothingReported();
        assertPlaying(true);
    }

    /** The view stays muted for the pages it loads later, also in a new web process. */
    @Test
    public void testViewStaysMuted() throws Throwable {
        loadAudioPage();
        scenario.onActivity(activity -> activity.getWebView().setAudioMuted(true));

        loadAudioPage();
        assertMuted(true);

        scenario.onActivity(activity -> activity.getWebView().terminateWebProcess());
        loadAudioPage();
        assertMuted(true);
        play();
        assertReported(true);
    }

    /** The page of a web process that ended plays nothing. */
    @Test
    public void testWebProcessEndStopsTheSound() throws Throwable {
        loadAudioPage();
        play();
        assertReported(true);

        scenario.onActivity(activity -> activity.getWebView().terminateWebProcess());
        assertReported(false);
    }

    /**
     * A hidden page pauses its media when audible playback requires page visibility, and plays nothing until it is
     * shown again.
     */
    @Test
    public void testHiddenPagePausesItsSound() throws Throwable {
        scenario.onActivity(
            activity -> activity.getWebView().getSettings().setAudiblePlaybackRequiresPageVisibility(true));
        loadAudioPage();
        play();
        assertReported(true);

        AtomicReference<ViewGroup> parent = new AtomicReference<>();
        takeOutOfTheWindow(parent);
        try {
            assertEquals(PAUSED, WebViewActivityScenarioHelper.waitForJavaScriptResult(scenario, "state()", PAUSED));
            assertReported(false);
        } finally {
            scenario.onActivity(activity -> parent.get().addView(activity.getWebView()));
        }
        assertReported(true);
    }

    /** A destroyed view plays nothing, and keeps its mute state. */
    @Test
    public void testDestroyedView() throws Throwable {
        loadAudioPage();
        play();
        assertReported(true);
        scenario.onActivity(activity -> activity.getWebView().setAudioMuted(true));

        AtomicBoolean playing = new AtomicBoolean(true);
        AtomicBoolean muted = new AtomicBoolean();
        scenario.onActivity(activity -> {
            WebView webView = activity.getWebView();
            webView.destroy();
            playing.set(webView.isPlayingAudio());
            muted.set(webView.isAudioMuted());
        });
        assertFalse(playing.get());
        assertTrue(muted.get());
        assertNothingReported();
    }
}
