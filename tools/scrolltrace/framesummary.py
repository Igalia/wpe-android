#!/usr/bin/env python3
# framesummary.py run.json... : one row per app/scenario from framestats.py --json files named
# perf-<app>-<hz>-<scenario>-s<seed>[-tag].json (the tag names a variant), with the per-run values so the
# spread is visible.
# held/uneven are per 100 frames of that phase; only strokes the page followed count (content moved, finger
# down plus fling, >= 40% of the finger's travel: a fast flick shows less than that before lift-off).
import json
import os
import re
import sys
from collections import defaultdict
import numpy as np

rows = defaultdict(list)
for path in sys.argv[1:]:
    m = re.match(r'perf-([a-z0-9]+)-(\d+|auto)-([a-z]+)-s(\d+)(?:-([\w]+))?\.json$', os.path.basename(path))
    if not m:
        continue
    app, hz, scen, seed, tag = m.groups()
    if tag and tag != 'batch':
        app = f'{app}/{tag}'
    rows[(scen, hz, app)].append((int(seed), json.load(open(path))))

for (scen, hz, app), runs in sorted(rows.items()):
    runs.sort(key=lambda r: r[0])
    lag, fh, fu, lh, lu, pw, cpu = [], [], [], [], [], [], []
    tot = defaultdict(int)
    for seed, d in runs:
        gs = [g for g in d['gestures']
              if abs(g['content_px_during_finger'] + g['fling_px']) >= 0.4 * abs(g['finger_px'])]

        def s(k):
            return sum(g[k] for g in gs)

        lags = [g['lag_ms'] for g in gs if g['lag_ms'] is not None]
        lag.append(np.median(lags) if lags else float('nan'))

        def pct(a, b):
            return 100 * a / b if b else float('nan')

        fh.append(pct(s('finger_held'), s('finger_frames')))
        fu.append(pct(s('finger_uneven'), s('finger_frames')))
        lh.append(pct(s('fling_held'), s('fling_frames')))
        lu.append(pct(s('fling_uneven'), s('fling_frames')))
        for k in ('finger_frames', 'finger_held', 'finger_uneven', 'fling_frames', 'fling_held', 'fling_uneven'):
            tot[k] += s(k)
        p = d.get('power_mw') or {}
        pw.append(sum(p.values()) if p else float('nan'))
        cpu.append(sum(p.get(r, 0) for r in ('cpu.big', 'cpu.mid', 'cpu.little')) if p else float('nan'))

    def f(xs):
        return ' '.join(f'{x:.0f}' if x == x else '-' for x in xs)

    def f1(xs):
        return ' '.join(f'{x:.1f}' if x == x else '-' for x in xs)

    def pct(a, b):
        return f'{100 * tot[a] / tot[b]:.1f}' if tot[b] else '-'

    print(f'{scen} {hz} Hz {app}: {len(runs)} runs | lag ms {f1(lag)} | finger held/uneven % '
          f'{pct("finger_held", "finger_frames")}/{pct("finger_uneven", "finger_frames")} '
          f'(per run {f1(fh)} / {f1(fu)}) | '
          f'fling held/uneven % {pct("fling_held", "fling_frames")}/{pct("fling_uneven", "fling_frames")} '
          f'(per run {f1(lh)} / {f1(lu)}) | power mW {f(pw)} (cpu {f(cpu)})')
