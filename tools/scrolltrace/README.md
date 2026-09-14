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
