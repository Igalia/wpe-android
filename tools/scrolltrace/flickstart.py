#!/usr/bin/env python3
# flickstart.py run.pftrace... : the first frames of every stroke in perfrun.sh traces. For each touch-down:
# how long since the previous frame (idle), the first frames' composite length, apply -> latch, the gap
# between composites, the step each frame showed (device px) and the finger travel per frame at that
# point, to see stalls and catch-up jumps at drag start.
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
DPR = 2.625


def tp(trace, sql):
    q = trace + '.q.sql'
    open(q, 'w').write(sql)
    out = subprocess.run([TP, '-q', q, trace], capture_output=True, text=True, check=True).stdout
    os.unlink(q)
    return list(csv.reader(io.StringIO(out)))[1:]


rx = re.compile(r'WPEFrame (\d+) apply (-?\d+) acquire (-?\d+) latch (-?\d+) present (-?\d+) clock (-?\d+)')
for trace in sys.argv[1:]:
    base = trace[:-len('.pftrace')]
    frames = []
    for name, ts in tp(trace, "select name, ts from slice where name glob 'WPEFrame [0-9]*'"):
        n, apply, acquire, latch, present, clock = (int(x) for x in rx.match(name).groups())
        frames.append((n, apply, latch, present, int(ts) - clock))
    frames.sort()
    off = int(np.median([f[4] for f in frames]))
    comps = [(int(ts) - off, int(ts) + int(dur) - off) for ts, dur in tp(
        trace, "select ts, dur from slice where name = 'RenderLayerTree' and dur > 0 order by ts")]
    drawn = [(int(ts) - off, float(v) / 100 * DPR) for ts, v in tp(
        trace, "select c.ts, c.value from counter c join counter_track t on c.track_id = t.id "
        "where t.name = 'wpe.scroll.drawn-y' order by c.ts")]
    dts = np.array([d[0] for d in drawn])
    dys = np.array([d[1] for d in drawn])
    start = int(open(base + '.start').read().split()[1])
    downs, pts = [], []
    for line in open(base + '.traj'):
        p = line.split()
        if p[0] == 'D':
            downs.append(start + float(p[1]) * 1e6)
            pts.append([])
        if p[0] in 'DM':
            pts[-1].append((start + float(p[1]) * 1e6, int(p[3])))
    print(os.path.basename(trace))
    ci = 0
    rows = []
    for f in frames:
        while ci + 1 < len(comps) and comps[ci + 1][1] <= f[1]:
            ci += 1
        c = comps[ci]
        k = np.searchsorted(dts, c[1], side='right') - 1
        rows.append((f, c, dys[k] if k >= 0 else 0.0))
    for si, d in enumerate(downs):
        after = [r for r in rows if r[0][3] > d][:5]
        before = [r for r in rows if r[0][3] <= d]
        if not after:
            continue
        idle = (after[0][1][0] - before[-1][0][3]) / 1e6 if before else float('nan')
        prev_pos = before[-1][2] if before else 0.0
        prev_comp = before[-1][1][0] if before else after[0][1][0]
        cells = []
        for f, c, pos in after:
            cells.append(f'{(pos - prev_pos):+6.0f}px comp {(c[1] - c[0]) / 1e6:4.1f} '
                         f'gap {(c[0] - prev_comp) / 1e6:5.1f} '
                         f'ap->latch {(f[2] - f[1]) / 1e6:4.1f}')
            prev_pos, prev_comp = pos, c[0]
        ft = np.array([q[0] for q in pts[si]])
        fy = np.array([q[1] for q in pts[si]])
        speed = (fy[0] - fy[-1]) / ((ft[-1] - ft[0]) / 1e6) * 11.1 if len(ft) > 1 else 0
        print(f'  stroke {si + 1}: idle before {idle:6.0f} ms, finger avg {speed:5.0f} px/frame | ' + ' | '.join(cells))
