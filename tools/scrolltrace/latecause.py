#!/usr/bin/env python3
# latecause.py run.pftrace : every frame that reached the display more than 1.5 refresh periods after the
# previous one while content was moving, with what held it up: the composite starting late (compositor
# busy with something else, or the previous frame not released yet), a long composite (and how many
# tiles it replayed), a slow hand-off to the UI process, or a missed SurfaceFlinger latch.
import csv
import glob
import io
import os
import re
import subprocess
import sys
import numpy as np

TP = os.environ.get('TRACE_PROCESSOR') or sorted(
    glob.glob(os.path.expanduser('~/.local/share/perfetto/prebuilts/trace_processor_shell*')), key=os.path.getmtime)[-1]
trace = sys.argv[1]
base = trace[:-len('.pftrace')]


def tp(sql):
    q = base + '.q.sql'
    open(q, 'w').write(sql)
    out = subprocess.run([TP, '-q', q, trace], capture_output=True, text=True, check=True).stdout
    os.unlink(q)
    return list(csv.reader(io.StringIO(out)))[1:]


rx = re.compile(r'WPEFrame (\d+) apply (-?\d+) acquire (-?\d+) latch (-?\d+) present (-?\d+) clock (-?\d+)')
frames = []
for name, ts in tp("select name, ts from slice where name glob 'WPEFrame [0-9]*'"):
    n, apply, acquire, latch, present, clock = (int(x) for x in rx.match(name).groups())
    frames.append({'n': n, 'apply': apply, 'acquire': acquire, 'latch': latch, 'present': present,
                   'off': int(ts) - clock})
frames.sort(key=lambda f: f['n'])
off = int(np.median([f['off'] for f in frames]))
hz = float(open(base + '.hz').read().split('=')[1]) if os.path.exists(base + '.hz') else 90
period = 1e9 / hz
# Compositor thread slices: composites and what else ran there (pre-rendered tile passes, tile replays).
comp_utid = tp("select utid from slice s join thread_track tt on s.track_id = tt.id "
               "where s.name = 'RenderLayerTree' limit 1")[0][0]
slices = [(int(ts) - off, int(dur), name, int(depth)) for ts, dur, name, depth in tp(
    f"select s.ts, s.dur, s.name, s.depth from slice s join thread_track tt on s.track_id = tt.id "
    f"where tt.utid = {comp_utid} and s.dur >= 0 order by s.ts")]
comps = [(t, t + d) for t, d, nm, dp in slices if nm == 'RenderLayerTree']
drawn = [(int(ts) - off, float(v)) for ts, v in tp(
    "select c.ts, c.value from counter c join counter_track t on c.track_id = t.id "
    "where t.name = 'wpe.scroll.drawn-y' order by c.ts")]
dts = np.array([d[0] for d in drawn])
dys = np.array([d[1] for d in drawn])
ci = 0
for f in frames:
    while ci + 1 < len(comps) and comps[ci + 1][1] <= f['apply']:
        ci += 1
    f['comp'] = comps[ci]
    k = np.searchsorted(dts, f['comp'][1], side='right') - 1
    f['pos'] = dys[k] / 100 if k >= 0 else 0.0
shown = [f for f in frames if f['present'] > 0]


def inside(a, b, pred):
    return [(t, d, nm) for t, d, nm, dp in slices if a <= t < b and pred(nm, dp)]


start = int(open(base + '.start').read().split()[1]) if os.path.exists(base + '.start') else 0
print(f'{os.path.basename(trace)}: late frames while moving (gap > 1.5 periods)')
counts = {}
for j in range(2, len(shown) - 1):
    a, b = shown[j - 1], shown[j]
    gap = b['present'] - a['present']
    moving = abs(b['pos'] - a['pos']) > 0.5 and abs(a['pos'] - shown[j - 2]['pos']) > 0.5
    # Longer gaps are the pause between strokes, not a late frame.
    if gap <= 1.5 * period or gap > 8 * period or not moving:
        continue
    c0, c1 = b['comp']
    prev_c1 = a['comp'][1]
    start_gap = (c0 - a['comp'][0]) / 1e6
    tiles_in = len(inside(c0, c1, lambda nm, dp: nm.startswith('SkiaBackingStoreTileUpdate')))
    between = inside(prev_c1, c0, lambda nm, dp: dp == 0)
    pre = sum(d for t, d, nm in between if nm.startswith('composite: pre-rendered'))
    other = sum(d for t, d, nm in between if not nm.startswith('composite: pre-rendered'))
    stages = {'composite started late': start_gap - period / 1e6,
              'long composite': (c1 - c0) / 1e6 - period / 1e6 * 0.5,
              'UI hand-off': (b['apply'] - c1) / 1e6 - 2,
              'missed SF latch': (b['latch'] - b['apply']) / 1e6 - period / 1e6}
    cause = max(stages, key=stages.get)
    counts[cause] = counts.get(cause, 0) + 1
    print(f"  t={(b['present'] - start) / 1e6:8.1f} gap {gap / period:3.1f} periods | "
          f"composite started {start_gap:5.1f} ms after the previous "
          f"(between: pre-rendered tiles {pre / 1e6:4.1f} ms, other {other / 1e6:4.1f} ms) | "
          f"composite {(c1 - c0) / 1e6:5.1f} ms with {tiles_in:2d} tile replays | "
          f"to apply {(b['apply'] - c1) / 1e6:4.1f} | apply->latch {(b['latch'] - b['apply']) / 1e6:5.1f} => {cause}")
print('  causes: ' + ', '.join(f'{k} {v}' for k, v in sorted(counts.items(), key=lambda x: -x[1])))
