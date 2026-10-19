# scrolltrace

Answers one question: when a scroll stalls on the phone, was the engine waiting for the web
process main thread, and if so, why.

Touch-driven scrolling in WPE is asynchronous since the coordinated touch-event work
(`ENABLE(COORDINATED_TOUCH_EVENTS)`), but three things can still put the main thread back on
the critical path, and until now none of them were observable on Android:

* a touch sequence classified `Synchronous`, because the page has a non-passive touch or
  pointer listener under the finger — the reply, and with it gesture recognition, waits for
  the main thread;
* a wheel event that needs blocking DOM dispatch, or a scrolling node with synchronous
  scrolling reasons;
* the per-frame handshake in `ThreadedScrollingTree`, where the scrolling thread blocks for
  up to half a frame while the main thread runs its rendering update.

WebKit already has tracepoints at all of these, but the Android build sets
`-DUSE_SYSPROF_CAPTURE=OFF`, so `WTF::tracePoint()` compiled to nothing and the whole
timeline was invisible. The engine side of this tool is a WTF tracing backend that routes
tracepoints to Android's ATrace, plus annotations at the decision points that name the
reason in the slice itself.

## Requirements

A WebKit build carrying `Source/WTF/wtf/android/ATraceAnnotator.cpp` and the annotations at
the scrolling and touch decision points (recipe patch
`0002-Android-Instrument-main-thread-waits-during-scrolling.patch`). Without it the capture
still records Android's input, SurfaceFlinger and scheduler tracks, but nothing about the
engine.

## Capture

    tools/scrolltrace/capture.sh -u https://www.eldiario.es

    # or, with a real finger, which is what actually reproduces the stalls:
    tools/scrolltrace/capture.sh -u https://www.eldiario.es -m 20

The script wakes the phone, pins 60 Hz and the rotation, dismisses the 16 KB compatibility
dialog, arms `atrace` *before* launching the app (the app tag is sampled at process start),
drives the gesture, and pulls both the trace and SurfaceFlinger's presented-frame stats.

Options: `-s serial`, `-u url`, `-o outdir`, `-n gestures`, `-d swipe-ms`, `-m seconds`
(record while you scroll by hand), `-c scroll|all|off` (how many tracepoints to emit).

## Analyse

    tools/scrolltrace/analyse.py <outdir>/trace.txt

The report is built around stalls while a finger was down, and it measures the two
independent ways a scroll can stop:

* **no scroll update produced** — the engine stopped turning the gesture into scroll
  updates, which is what a `Synchronous` touch classification does;
* **no frame handed to SurfaceFlinger** — the engine kept up and the picture still stopped,
  which is a compositor or presentation problem and no main-thread wait explains it.

Reading only the first will tell you a page is fine when it visibly is not. Both are
reported, and a frame stall that happens while scroll updates were still being produced is
called out separately, because that one is never the touch path.

## Reading the output

| Line | What it means |
| --- | --- |
| `Touch events classified Synchronous` | Fraction of touches that had to wait for the main thread. Anything above 0 means the page has a non-passive listener under the finger. |
| `touch sync forced by non-passive <event> listener` | Exactly which listener did it. This is what a page has to drop or make passive. |
| `Touch blocked on main thread` | How long each of those waits actually took. The `max` is the length of the worst lock. |
| `Touch moves coalesced while waiting` | How many moves piled up behind one reply. They are all delivered at once afterwards, which is the jump you see after the stall. |
| `Rendering-update syncs timed out` | How often the scrolling thread gave up waiting for the main thread's rendering update. This is a designed fallback, not a bug, but a high rate means the main thread is missing its frame budget. |
| `Scrolling nodes forcing main thread` | If non-zero the scrolling thread may not move layers at all, and every scrolled frame needs the main thread. |
| `wheel processing:` | Whether the synthesized scroll needed blocking DOM dispatch. |
| `Composites during a gesture, by what asked for the frame` | If only the `RenderingUpdate` rows overrun the frame budget, the page's own repaints are dropping the frames, not scrolling. |
| `Where a composite spends its time` | Splits a composite into scene sync (`FlushCompositingState`, where main-thread state shows up) and paint (`PaintToGLContext`), and the paint into the damage walk, the draw walk and the GPU submit. |
| `Intermediate surfaces per composite` | A filter, mask, opacity group, replica or blend mode makes the layer paint into its own GPU render target, which is flushed and snapshotted mid-frame. |

## Emitting more

`debug.wpe.atrace` picks the coverage: unset or `scroll` emits the scrolling, touch,
rendering-update and compositing tracepoints, `layers` adds a slice per layer painted,
`all` emits every tracepoint WebKit has, `off` emits nothing. `capture.sh -c` sets it.

Use `-c layers` when the Compositing section says a composite overran the frame and you need
to know whether it was the number of layers, one expensive layer, an intermediate surface,
or the GPU submit. There are hundreds of layer slices per frame, so it is not the default.

The capture also loads fine in <https://ui.perfetto.dev> if you want the timeline rather
than the summary; the engine slices sit on the web process threads next to SurfaceFlinger
and input dispatch.

## Finger-gesture runs and frame metrics

`capture.sh` answers "was the main thread in the way". The scripts below answer "how does scrolling look":
how far the content trails the finger, and how many frames are held or uneven while the finger moves and
during the fling, from a Perfetto trace alone, plus the power drawn.

Requirements:

* a WebKit build with the scroll instrumentation (the `wpe.scroll.*` counters, `WPE_TRACE_*` slices) and
  wpeview's frame timing (`WPEFrame` sections: when each frame reached the display);
* `touchinject` on the phone. It replays a finger trajectory through a uinput touchscreen, so events take
  the same path as a real finger (kernel timestamps, InputReader, batching), unlike `input swipe`:

      $ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang++ \
          -O2 -Wall -o touchinject tools/scrolltrace/TouchInject.cpp
      adb push touchinject /data/local/tmp/

* Perfetto's `trace_processor` (run `trace_processor` once to download the prebuilt into
  `~/.local/share/perfetto/prebuilts/`, or point `TRACE_PROCESSOR` at a binary);
* `ANDROID_SERIAL` when more than one device is attached (an emulator counts).

The gestures and the screen geometry are tuned for a Pixel 7 (1080x2400, scale 2.625: `framestats.py --dpr`).

One run, then the numbers:

    # install the build to measure as a separate package (applicationIdSuffix ".mine"), then:
    PROPS="scroll.resamplemode=chrome" TAG=chrome tools/scrolltrace/perfrun.sh mine 90 drags <url> runs 1
    tools/scrolltrace/framestats.py runs/perf-mine-90-drags-s1-chrome.pftrace --json runs/perf-mine-90-drags-s1-chrome.json
    tools/scrolltrace/framesummary.py runs/perf-*.json

`perfrun.sh <app> <60|90|auto> <scenario> <url> <outdir> [seed]` pins the refresh rate, resets every
`debug.wpe.*` property (`setcfg.sh`) and applies `PROPS`, records Perfetto (scheduling, frequencies,
atrace, SurfaceFlinger frame timeline, power rails) while `touchinject` plays `gestures.py <scenario>`
(`drags`: reading drags that come to rest; `downflicks`: flicks that lift off at speed; others in the
script). The other tools read its output:

| Script | What it gives |
| --- | --- |
| `framestats.py` | Per gesture: lag (finger to photon), held and uneven frames with the finger down and in the fling, where composite-to-display time goes, power. |
| `framesummary.py` | One row per app/scenario/variant over all seeds, with the per-run values, so the spread is visible. |
| `latecause.py` | Every late frame while moving, with its cause (composite started late, long composite, missed latch...). |
| `flickstart.py` | The first frames of each stroke: stalls and catch-up jumps at the start of a drag or fling. |
| `tilepass.py` | The between-frame tile pass: passes, their length, tiles uploaded between frames and inside frames, compositor CPU. |
| `variantcheck.sh` | `latecause.py` + `flickstart.py` over all seeds of each variant. |

`manual.sh best|noadpf|default [url]` opens a build with one of the measured configurations for a hand
test; `tapsupp.sh on|off <outdir>` checks that a tap which stops a fling does not click (it serves
`tapsupp/tap.html`, which reports clicks and touches back to the host).

Traps, learned the hard way:

* Use 3 runs per variant, and compare variants within one batch: absolute lag moves by several ms from
  day to day, and a single flick can swing 15-60 ms.
* A dozing or locked phone gives a 0x0 viewport; `perfrun.sh` wakes it, but keep it on the charger.
* The first run after `adb install` may run at 60 Hz although 90 was asked; `perfrun.sh` warns. Warm up
  each build with one throw-away run.
* A property left from an earlier run silently changes the next one; `setcfg.sh` resets all of them,
  so add new ones there.
* Properties are read once per process: every MiniBrowser is force-stopped before a run.
* Pages that load content while you scroll (ads, consent banners) give strokes the page doesn't follow;
  `framesummary.py` only counts strokes the content followed. A local copy of the page served with
  `python3 -m http.server` and `adb reverse` gives repeatable runs.
