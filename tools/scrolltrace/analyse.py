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


class Slice:
    __slots__ = ("name", "tid", "pid", "task", "start", "end")

    def __init__(self, name, tid, pid, task, start):
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

    def starting_with(self, prefix):
        return [s for s in self.slices if s.name.startswith(prefix)]

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


def report_stalls(trace, frame_seconds, top):
    """The headline: gaps in scroll production while a finger was down, and what ran then."""
    section("Scroll stalls (finger down, no scroll update produced)")
    wheels = sorted(s.start for s in trace.starting_with(WHEEL_MARKER))
    if not wheels:
        print("  No scroll updates in this capture; nothing scrolled.")
        return []

    threshold = 2 * frame_seconds
    stalls = []
    for start, end, _ in gestures(trace):
        within = [w for w in wheels if start <= w <= end]
        previous = start
        for w in within + [end]:
            if w - previous > threshold:
                stalls.append((previous, w))
            previous = w

    gaps = [end - start for start, end in stalls]
    print(f"  Frame budget assumed                  {frame_seconds * 1000:.1f}ms "
          f"(stall = no scroll update for {threshold * 1000:.0f}ms)")
    print(f"  Stalls                                {describe(gaps)}")
    if not stalls:
        print("  Scroll production never paused for longer than two frames.")
        return stalls

    # What was running during the stalls, by total overlap. This is the attribution: a name
    # near the top of this list is what the scroll was waiting behind.
    main_tids = engine_main_tids(trace)
    blame = defaultdict(float)
    blame_counts = defaultdict(int)
    candidates = [s for s in trace.slices if s.tid in main_tids] + trace.spans
    for start, end in stalls:
        for s in overlapping(candidates, start, end):
            overlap = min(s.end, end) - max(s.start, start)
            if overlap <= 0:
                continue
            label = s.name if s.tid in main_tids else f"{s.name} (async)"
            blame[label] += overlap
            blame_counts[label] += 1

    print()
    print("  What was running during those stalls (total overlap):")
    if not blame:
        print("    Nothing the engine reported: the stall is upstream of the engine, in input")
        print("    delivery or in the compositor. Check the input and SurfaceFlinger tracks.")
    for name, total in sorted(blame.items(), key=lambda kv: -kv[1])[:top]:
        print(f"    {total * 1000:9.1f}ms  n={blame_counts[name]:<5d} {name}")
    return stalls


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


def report_main_thread_work(trace, top):
    section("What the web process main thread was busy with")
    main_tids = engine_main_tids(trace)
    if not main_tids:
        print("  No engine main-thread slices captured.")
        return
    totals = defaultdict(float)
    counts = defaultdict(int)
    for s in trace.slices:
        if s.tid in main_tids:
            totals[s.name] += s.duration
            counts[s.name] += 1
    for name, total in sorted(totals.items(), key=lambda kv: -kv[1])[:top]:
        print(f"  {total * 1000:9.1f}ms  n={counts[name]:<5d} {name}")


def report_timestats(path):
    if not os.path.exists(path):
        return
    lines = open(path, errors="replace").read().splitlines()
    layer = next((i for i, line in enumerate(lines)
                  if "layerName" in line and "WPEWebLayer" in line), None)
    if layer is None:
        return
    section("Presented frames (SurfaceFlinger, WPEWebLayer)")
    for line in lines[layer:layer + 30]:
        stripped = line.strip()
        if stripped.startswith(("layerName", "totalFrames", "droppedFrames", "averageFPS",
                                "present2present", "post2present")):
            print("  " + stripped)


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
    report_main_thread_work(trace, args.top)
    report_timestats(os.path.join(os.path.dirname(os.path.abspath(args.trace)), "surfaceflinger.timestats"))
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
