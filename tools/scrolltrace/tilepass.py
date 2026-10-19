#!/usr/bin/env python3
# tilepass.py trace.pftrace... : what the between-frame tile pass ("composite: pre-rendered tiles") and the tile
# uploads did in each run. Passes: count, total/p95/max ms, and how many ran back to back (< 0.5 ms after the
# previous one ended: a re-dispatch loop). Uploads (SkiaBackingStoreTileUpdate): pre-rendered ones happen in
# that pass, visible ones inside a frame. A tile uploaded more than 100 times in a run is a layer the page
# repaints on every frame (igalia's header: 1079x184 at 0,0) and is counted apart. Compositor CPU = scheduled
# time of the thread that runs the passes, while the run lasted.
import re
import subprocess
import sys
import os
import glob

TP = os.environ.get('TRACE_PROCESSOR') or sorted(
    glob.glob(os.path.expanduser('~/.local/share/perfetto/prebuilts/trace_processor_shell-*')))[0]


def q(trace, sql):
    out = subprocess.run([TP, '-q', '/dev/stdin', trace], input=sql, capture_output=True, text=True).stdout
    rows = [line for line in out.splitlines()[1:] if line.strip()]
    return [[c.strip('"') for c in r.split(',')] for r in rows]


print('run | passes | pass ms total/p95/max | back-to-back | pre-rendered uploads | visible uploads (in frame) | '
      'compositor CPU ms')
for t in sys.argv[1:]:
    passes = q(t, "select s.ts, s.dur, tt.utid from slice s join thread_track tt on s.track_id = tt.id "
                  "where s.name = 'composite: pre-rendered tiles' order by s.ts;")
    if not passes:
        print(f'{os.path.basename(t)}: no passes')
        continue
    durs = sorted(int(p[1]) / 1e6 for p in passes)
    ends = [(int(p[0]), int(p[0]) + int(p[1])) for p in passes]
    b2b = sum(1 for a, b in zip(ends, ends[1:]) if b[0] - a[1] < 500_000)
    utid = passes[0][2]
    ups = q(t, "select name, count(*) from slice where name glob 'SkiaBackingStoreTileUpdate rect*' group by name;")
    pre = vis = perframe = 0
    for name, count in ups:
        m = re.search(r'rect \S+ (visible|pre-rendered)', name)
        if not m:
            continue
        count = int(count)
        if count > 100:
            perframe += count
        elif m.group(1) == 'visible':
            vis += count
        else:
            pre += count
    cpu = q(t, f"select sum(dur) from sched where utid = {utid};")
    cpu = int(cpu[0][0] or 0) / 1e6 if cpu else 0
    p95 = durs[int(0.95 * (len(durs) - 1))]
    print(f'{os.path.basename(t)} | {len(durs)} | {sum(durs):.1f}/{p95:.2f}/{durs[-1]:.2f} | {b2b} | {pre} | '
          f'{vis} (+{perframe} per-frame layer) | {cpu:.0f}')
