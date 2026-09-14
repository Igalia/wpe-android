#!/usr/bin/env python3
#
# Copyright (C) 2026 Igalia S.L.
#
# Turns a systrace capture from capture.sh into an answer to "when, and why, did the scroll
# wait for the main thread".
#
# The user-visible symptom is not "the main thread was busy", it is "the finger moved and
# the page did not". So the report is built around the gaps between the scroll updates the
# gesture actually produced, and what was running during each gap.

import argparse
import os
import re
import sys
from collections import defaultdict

# TASK-TID ( PID) [CPU] flags TIMESTAMP: tracing_mark_write: PAYLOAD
TRACE_LINE = re.compile(
    r"^\s*(?P<task>.*?)-(?P<tid>\d+)\s+"
    r"(?:\(\s*(?P<pid>[-\d]+)\)\s+)?"
    r"\[\d+\]\s+\S+\s+"
    r"(?P<ts>\d+\.\d+):\s+tracing_mark_write:\s*(?P<payload>.*)$"
)

WHEEL_MARKER = "UI: drag -> wheel event"
TOUCH_MARKER = "WPEWebView.onTouchEvent"
# SurfaceFlinger applies one of these per frame the compositor hands over.
FRAME_MARKER = "setBuffer WPEWebLayer"


class Slice:
    __slots__ = ("name", "tid", "pid", "task", "start", "end", "kind")

    def __init__(self, name, tid, pid, task, start, kind="B"):
        self.kind = kind
        self.name = name
        self.tid = tid
        self.pid = pid
        self.task = task
        self.start = start
        self.end = None

    @property
    def duration(self):
        return (self.end - self.start) if self.end is not None else 0.0


class Trace:
    def __init__(self):
        self.slices = []
        self.spans = []                     # async S|/F| pairs
        self.counters = defaultdict(list)   # (pid, name) -> [(ts, value)]
        self.task_names = {}                # tid -> comm
        self.first_ts = None
        self.last_ts = None

    def named(self, name):
        return [s for s in self.slices if s.name == name]

    def starting_with(self, prefix, kind=None):
        return [s for s in self.slices
                if s.name.startswith(prefix) and (kind is None or s.kind == kind)]

    def spans_named(self, name):
        return [s for s in self.spans if s.name == name]

    def counter(self, name):
        values = []
        for (_, counter_name), samples in self.counters.items():
            if counter_name == name:
                values.extend(samples)
        values.sort()
        return values


def parse(path):
    trace = Trace()
    stacks = defaultdict(list)
    open_spans = {}

    with open(path, errors="replace") as handle:
        for line in handle:
            match = TRACE_LINE.match(line)
            if not match:
                continue

            ts = float(match.group("ts"))
            tid = int(match.group("tid"))
            task = match.group("task").strip()
            payload = match.group("payload").strip()
            if not payload:
                continue

            trace.first_ts = ts if trace.first_ts is None else min(trace.first_ts, ts)
            trace.last_ts = ts if trace.last_ts is None else max(trace.last_ts, ts)
            trace.task_names.setdefault(tid, task)

            fields = payload.split("|")
            kind = fields[0]

            pid = None
            if len(fields) > 1 and fields[1].lstrip("-").isdigit():
                pid = int(fields[1])
            if pid is None:
                pid = tid

            if kind == "B" and len(fields) >= 3:
                s = Slice("|".join(fields[2:]), tid, pid, task, ts)
                stacks[tid].append(s)
                trace.slices.append(s)
            elif kind == "E":
                if stacks[tid]:
                    stacks[tid].pop().end = ts
            elif kind == "S" and len(fields) >= 4:
                s = Slice(fields[2], tid, pid, task, ts)
                open_spans[(pid, fields[2], fields[3])] = s
                trace.spans.append(s)
            elif kind == "F" and len(fields) >= 4:
                s = open_spans.pop((pid, fields[2], fields[3]), None)
                if s is not None:
                    s.end = ts
            elif kind == "I" and len(fields) >= 3:
                marker = Slice("|".join(fields[2:]), tid, pid, task, ts, kind="I")
                marker.end = ts
                trace.slices.append(marker)
            elif kind == "C" and len(fields) >= 4:
                try:
                    trace.counters[(pid, fields[2])].append((ts, float(fields[3])))
                except ValueError:
                    pass

    # Slices still open when the buffer ended cannot be measured.
    trace.slices = [s for s in trace.slices if s.end is not None]
    trace.spans = [s for s in trace.spans if s.end is not None]
    return trace


def percentile(values, fraction):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def describe(values, scale=1000.0, unit="ms"):
    if not values:
        return "none"
    scaled = [v * scale for v in values]
    return (f"n={len(scaled)}  total={sum(scaled):.1f}{unit}  mean={sum(scaled) / len(scaled):.1f}{unit}  "
            f"p50={percentile(scaled, 0.5):.1f}{unit}  p95={percentile(scaled, 0.95):.1f}{unit}  "
            f"max={max(scaled):.1f}{unit}")


def fraction_set(samples):
    """Fraction of counter samples that are non-zero."""
    if not samples:
        return None
    set_count = sum(1 for _, value in samples if value)
    return set_count, len(samples)


def gestures(trace, idle_gap=0.3):
    """Contiguous runs of MotionEvents, one per finger-down-to-finger-up."""
    touches = sorted(s.start for s in trace.named(TOUCH_MARKER))
    runs = []
    for ts in touches:
        if runs and ts - runs[-1][-1] <= idle_gap:
            runs[-1].append(ts)
        else:
            runs.append([ts])
    return [(run[0], run[-1], len(run)) for run in runs if len(run) > 2]


def overlapping(items, start, end):
    return [i for i in items if i.end is not None and i.end > start and i.start < end]


def section(title):
    print()
    print(title)
    print("-" * len(title))


def engine_main_tids(trace):
    """The WebKit main thread is not the process's Android main thread, so find it by what
    it emits rather than by thread id."""
    return {s.tid for s in trace.slices if s.name == "RenderingUpdate"}


def compositor_tids(trace):
    """The ThreadedCompositor thread, likewise identified by what only it emits."""
    return {s.tid for s in trace.slices if s.name == "RenderLayerTree"}


def report_input_path(trace):
    section("Input path")
    android_touches = trace.named(TOUCH_MARKER)
    round_trips = trace.spans_named("UI: touch event round trip")
    recognitions = trace.named("UI: gesture recognition")
    wheels = trace.starting_with(WHEEL_MARKER)
    coalesced = [v for _, v in trace.counter("wpe.ui.touch-coalesced")]

    print(f"  MotionEvents handed to WPE            {len(android_touches)}")
    print(f"  Touch events forwarded to the engine  {len(round_trips)}")
    print(f"  Gesture recognition runs              {len(recognitions)}")
    print(f"  Scroll updates produced               {len(wheels)}")
    if coalesced:
        print(f"  Touch moves coalesced while waiting   max={int(max(coalesced))} "
              f"(a backlog means the finger outran the replies)")
    if round_trips:
        print(f"  Touch round trip                      {describe([s.duration for s in round_trips])}")


def find_stalls(trace, events, frame_seconds):
    """Stretches during a gesture where `events` went quiet for more than two frames."""
    threshold = 2 * frame_seconds
    stalls = []
    for start, end, _ in gestures(trace):
        previous = start
        for ts in [e for e in events if start <= e <= end] + [end]:
            if ts - previous > threshold:
                stalls.append((previous, ts))
            previous = ts
    return stalls


def blame_stalls(trace, stalls, top, indent="    "):
    """What was running during the stalls, by total overlap."""
    main_tids = engine_main_tids(trace)
    comp_tids = compositor_tids(trace)
    labels = {tid: "main thread" for tid in main_tids}
    labels.update({tid: "compositor" for tid in comp_tids})
    blame = defaultdict(float)
    counts = defaultdict(int)
    candidates = [s for s in trace.slices if s.tid in labels and s.duration > 0] + trace.spans
    for start, end in stalls:
        for s in overlapping(candidates, start, end):
            overlap = min(s.end, end) - max(s.start, start)
            if overlap <= 0:
                continue
            label = f"{s.name} [{labels[s.tid]}]" if s.tid in labels else f"{s.name} (async)"
            blame[label] += overlap
            counts[label] += 1
    if not blame:
        print(f"{indent}Nothing the engine reported: the stall is outside it, in input delivery")
        print(f"{indent}or in the compositor. Check the input and SurfaceFlinger tracks.")
        return
    for name, total in sorted(blame.items(), key=lambda kv: -kv[1])[:top]:
        print(f"{indent}{total * 1000:9.1f}ms  n={counts[name]:<5d} {name}")


def report_stalls(trace, frame_seconds, top):
    """The headline. Two independent things can stop while a finger is down: the engine can
    stop producing scroll updates, or the produced frames can stop reaching the display.
    They have completely different causes, so they are measured separately."""
    section("Stalls while a finger was down")
    threshold = 2 * frame_seconds
    print(f"  Frame budget assumed                  {frame_seconds * 1000:.1f}ms "
          f"(a stall is a gap longer than {threshold * 1000:.0f}ms)")

    wheels = sorted(s.start for s in trace.starting_with(WHEEL_MARKER))
    frames = sorted(s.start for s in trace.starting_with(FRAME_MARKER, kind="B"))

    print()
    if not wheels:
        print("  No scroll updates in this capture; nothing scrolled.")
        scroll_stalls = []
    else:
        scroll_stalls = find_stalls(trace, wheels, frame_seconds)
        print(f"  a) No scroll update produced          {describe([e - s for s, e in scroll_stalls])}")
        if not scroll_stalls:
            print("     The engine never stopped turning the gesture into scroll updates.")

    if not frames:
        print("  b) No frame handed to SurfaceFlinger  not captured (no WPEWebLayer buffers in trace)")
        frame_stalls = []
    else:
        frame_stalls = find_stalls(trace, frames, frame_seconds)
        gaps = [e - s for s, e in frame_stalls]
        dropped = sum(max(0, int(round(g / frame_seconds)) - 1) for g in gaps)
        print(f"  b) No frame handed to SurfaceFlinger  n={len(gaps)} dropped={dropped} frames"
              + (f"  p95={percentile(gaps, 0.95) * 1000:.1f}ms  max={max(gaps) * 1000:.1f}ms" if gaps else ""))
        if not frame_stalls:
            print("     Frames kept reaching the display throughout every gesture.")
        else:
            in_gesture = sum(end - start for start, end, _ in gestures(trace))
            windows = gestures(trace)
            produced = len([f for f in frames if any(a <= f <= b for a, b, _ in windows)])
            if in_gesture:
                print(f"     Effective {produced / in_gesture:.1f} fps while a finger was down "
                      f"(a {1 / frame_seconds:.0f} Hz display would give {1 / frame_seconds:.0f})")

    if scroll_stalls:
        print()
        print("  What was running while no scroll update was produced:")
        blame_stalls(trace, scroll_stalls, top)

    # Frame stalls that are not also scroll stalls are the interesting ones: the engine was
    # keeping up and the picture still stopped.
    unexplained = [(start, end) for start, end in frame_stalls
                   if not any(s < end and e > start for s, e in scroll_stalls)]
    if unexplained:
        print()
        print(f"  {len(unexplained)} frame stall(s) happened while scroll updates were still being")
        print("  produced, so the engine was not the thing that stopped:")
        blame_stalls(trace, unexplained, top)

    return scroll_stalls


def report_main_thread_waits(trace):
    section("Waiting on the main thread")
    touch_blocks = trace.spans_named("touch blocked on web process main thread")
    gesture_waits = trace.starting_with("scroll: gesture start blocked")
    ru_sync = trace.spans_named("ScrollingThreadRenderUpdateSync")

    print(f"  Touch blocked on main thread          {describe([s.duration for s in touch_blocks])}")
    print(f"  Wheel gesture start blocked           {describe([s.duration for s in gesture_waits])}")
    print(f"  Scrolling thread blocked on the main  {describe([s.duration for s in ru_sync])}")
    print( "    thread rendering update")

    desync = fraction_set(trace.counter("wpe.scroll.desynchronized"))
    if desync:
        timeouts, total = desync
        print(f"  Rendering-update syncs timed out      {timeouts}/{total} "
              f"({100.0 * timeouts / total:.0f}%) - the scrolling thread gave up waiting")

    blocking = fraction_set(trace.counter("wpe.touch.blocking"))
    if blocking:
        sync, total = blocking
        print(f"  Touch events classified Synchronous   {sync}/{total} ({100.0 * sync / total:.0f}%)")

    wheel_blocking = fraction_set(trace.counter("wpe.wheel.blocking"))
    if wheel_blocking:
        sync, total = wheel_blocking
        print(f"  Wheel events needing the main thread  {sync}/{total} ({100.0 * sync / total:.0f}%)")

    nodes = [v for _, v in trace.counter("wpe.scroll.nodes-forcing-main-thread")]
    if nodes:
        worst = int(max(nodes))
        note = "the scrolling thread may not move layers at all while set" if worst else "none, layers can move without the main thread"
        print(f"  Scrolling nodes forcing main thread   max={worst} ({note})")


def report_reasons(trace):
    section("Why")
    prefixes = ("touch sync forced by", "touch: ", "wheel processing:", "scroll: node ",
                "scroll: desynchronized", "scroll: gesture start timed out")
    reasons = defaultdict(int)
    for s in trace.slices:
        if s.name.startswith(prefixes) or (s.name.startswith("touch ") and " classified " in s.name):
            reasons[s.name] += 1
    if not reasons:
        print("  No classification markers in this capture.")
    for name, count in sorted(reasons.items(), key=lambda kv: -kv[1]):
        print(f"  {count:6d}  {name}")


def report_thread_work(trace, title, tids, top, note=None):
    section(title)
    if not tids:
        print("  Not captured.")
        return
    totals = defaultdict(float)
    counts = defaultdict(int)
    worst = defaultdict(float)
    for s in trace.slices:
        if s.tid in tids and s.duration > 0:
            totals[s.name] += s.duration
            counts[s.name] += 1
            worst[s.name] = max(worst[s.name], s.duration)
    if note:
        print(f"  {note}")
    print(f"  {'total':>10s} {'n':>7s} {'mean':>9s} {'max':>9s}  slice")
    for name, total in sorted(totals.items(), key=lambda kv: -kv[1])[:top]:
        print(f"  {total * 1000:9.1f}ms {counts[name]:7d} {total / counts[name] * 1000:8.2f}ms "
              f"{worst[name] * 1000:8.2f}ms  {name}")


def report_compositing(trace, frame_seconds, top):
    """A composite that overruns the frame budget drops a frame no main-thread wait
    explains, so it is worth splitting by what the frame was composited for and by where
    the time inside it went."""
    composites = [s for s in trace.slices if s.name == "RenderLayerTree" and s.duration > 0]
    if not composites:
        return

    section("Compositing")
    windows = gestures(trace)

    def during_gesture(ts):
        return any(a <= ts <= b for a, b, _ in windows)

    marks = sorted((s.start, s.name.split(": ", 1)[1])
                   for s in trace.slices if s.name.startswith("DidRenderFrame reasons: "))

    def reason_for(slice_):
        for ts, reason in marks:
            if slice_.start <= ts <= slice_.end:
                return reason
        return "(not recorded)"

    buckets = defaultdict(list)
    for c in composites:
        if during_gesture(c.start):
            buckets[reason_for(c)].append(c.duration)

    if buckets:
        print(f"  Composites during a gesture, by what asked for the frame:")
        print(f"  {'reason':36s} {'n':>5s} {'mean':>9s} {'max':>9s} {'over budget':>12s}")
        for reason, durations in sorted(buckets.items(), key=lambda kv: -sum(kv[1])):
            over = sum(1 for d in durations if d > frame_seconds)
            print(f"  {reason:36s} {len(durations):5d} {sum(durations) / len(durations) * 1000:8.2f}ms "
                  f"{max(durations) * 1000:8.2f}ms {over:12d}")
        print("  A reason that only ever overruns when the main thread asked for the frame")
        print("  points at page repaints, not at scrolling.")

    # Where the time inside a composite goes. The two outer signposts split scene sync from
    # paint; the inner slices split the paint itself, which is where the surprise usually is.
    total = sum(c.duration for c in composites)
    inner = defaultdict(float)
    for span in trace.spans:
        if span.name in ("FlushCompositingState", "PaintToGLContext"):
            inner[span.name] += span.duration
    for slice_ in trace.slices:
        if slice_.name.startswith("composite: ") and slice_.duration > 0:
            inner["  " + slice_.name.removeprefix("composite: ")] += slice_.duration

    if inner and total:
        print()
        print("  Where a composite spends its time (indented rows are inside PaintToGLContext):")
        for name in ("FlushCompositingState", "PaintToGLContext"):
            if name in inner:
                print(f"    {inner[name] * 1000:9.1f}ms  {100.0 * inner[name] / total:4.1f}%  {name}")
            for sub, value in sorted(inner.items(), key=lambda kv: -kv[1]):
                if sub.startswith("  ") and name == "PaintToGLContext":
                    print(f"    {value * 1000:9.1f}ms  {100.0 * value / total:4.1f}%  {sub}")
            inner = {k: v for k, v in inner.items() if not k.startswith("  ")} if name == "PaintToGLContext" else inner
        accounted = inner.get("FlushCompositingState", 0) + inner.get("PaintToGLContext", 0)
        print(f"    {(total - accounted) * 1000:9.1f}ms  {100.0 * (total - accounted) / total:4.1f}%  "
              "(swap buffers, frame submission, unattributed)")

    layers = [v for _, v in trace.counter("wpe.composite.layers-painted")]
    surfaces = [v for _, v in trace.counter("wpe.composite.intermediate-surfaces")]
    if layers:
        print()
        print(f"  Layers painted per composite          max={int(max(layers))}")
    if surfaces:
        worst = int(max(surfaces))
        note = "each one is a GPU render target allocated mid-frame" if worst else "none"
        print(f"  Intermediate surfaces per composite   max={worst} ({note})")


def report_timestats(path, frame_ms=16.7):
    if not os.path.exists(path):
        return
    lines = open(path, errors="replace").read().splitlines()
    layer = next((i for i, line in enumerate(lines)
                  if "layerName" in line and "WPEWebLayer" in line), None)
    if layer is None:
        return
    section("Presented frames (SurfaceFlinger, WPEWebLayer)")
    block = lines[layer:layer + 40]
    for line in block:
        stripped = line.strip()
        if stripped.startswith(("layerName", "totalFrames", "droppedFrames", "jankyFrames", "averageFPS")):
            print("  " + stripped)

    for index, line in enumerate(block):
        if not line.strip().startswith("present2present histogram"):
            continue
        buckets = []
        for entry in block[index + 1].split():
            name, _, count = entry.partition("=")
            if not count.isdigit() or not int(count):
                continue
            buckets.append((int(name.removesuffix("ms")), int(count)))
        late = [(ms, count) for ms, count in buckets if ms >= 2 * frame_ms]
        total = sum(count for _, count in buckets)
        dropped = sum(count for _, count in late)
        print(f"  frame intervals over {2 * frame_ms:.0f}ms: {dropped}/{total} "
              f"({100.0 * dropped / total:.1f}%)" if total else "  no intervals recorded")
        if late:
            print("    " + "  ".join(f"{ms}ms x{count}" for ms, count in late))
            print("    (gaps of several hundred ms between gestures are the idle time")
            print("     between swipes, not stalls)")
        break


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", help="trace.txt produced by capture.sh")
    parser.add_argument("--top", type=int, default=12, help="how many entries to list per table")
    parser.add_argument("--frame-ms", type=float, default=16.7,
                        help="display frame budget, used to decide what counts as a stall")
    args = parser.parse_args()

    trace = parse(args.trace)
    if not trace.slices and not trace.spans:
        print("No app trace slices found.", file=sys.stderr)
        print("Either the build lacks the ATrace backend in WTF, or the app tag was not enabled "
              "for the web process (check capture.sh's -a list).", file=sys.stderr)
        return 1

    duration = (trace.last_ts - trace.first_ts) if trace.first_ts is not None else 0.0
    print(f"Trace: {os.path.abspath(args.trace)}")
    print(f"Window: {duration:.1f}s, {len(trace.slices)} slices, {len(trace.spans)} async spans")

    report_input_path(trace)
    report_stalls(trace, args.frame_ms / 1000.0, args.top)
    report_main_thread_waits(trace)
    report_reasons(trace)
    report_thread_work(trace, "What the web process main thread was busy with",
                       engine_main_tids(trace), args.top)
    report_thread_work(trace, "What the compositor thread was busy with",
                       compositor_tids(trace), args.top,
                       note="A max well over the frame budget here is a dropped frame that no "
                            "main-thread wait explains.")
    report_compositing(trace, args.frame_ms / 1000.0, args.top)
    report_timestats(os.path.join(os.path.dirname(os.path.abspath(args.trace)), "surfaceflinger.timestats"),
                     args.frame_ms)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
