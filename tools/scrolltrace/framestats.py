#!/usr/bin/env python3
# framestats.py run.pftrace [--json out.json] [--frames out.tsv] : on-screen truth from a perfrun.sh trace,
# without a screen recording. wpeview's WPEFrame sections say when every frame reached the display
# (present fence), WebKit's wpe.scroll.drawn-y counter what scroll position each composite drew, and
# the touchinject trajectory (.traj + .start next to the trace) where the finger was at any instant.
# Per gesture it prints what a screen recording would show (lag, held and uneven frames while the finger
# is down and during the fling) and where the time between a composite and the display goes. Lag is finger
# to photon (from the injected event), so it is larger than lag measured against the system touch dot,
# which itself trails the finger.
import argparse
import csv
import glob
import io
import json
import os
import re
import subprocess
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument('trace')
ap.add_argument('--dpr', type=float, default=2.625)
ap.add_argument('--json')
ap.add_argument('--frames', help='write one row per presented frame (TSV)')
args = ap.parse_args()
base = args.trace[:-len('.pftrace')] if args.trace.endswith('.pftrace') else args.trace

TP = os.environ.get('TRACE_PROCESSOR') or sorted(
    glob.glob(os.path.expanduser('~/.local/share/perfetto/prebuilts/trace_processor_shell*')),
    key=os.path.getmtime)[-1]


def tp(sql):
    q = base + '.q.sql'
    with open(q, 'w') as f:
        f.write(sql)
    out = subprocess.run([TP, '-q', q, args.trace], capture_output=True, text=True, check=True).stdout
    os.unlink(q)
    rows = list(csv.reader(io.StringIO(out)))
    return [[None if v == '[NULL]' else v for v in r] for r in rows[1:]]


# Frames as wpeview saw them, CLOCK_MONOTONIC ns. "clock" is the section's own time, which maps the
# trace clock (boot time) to the monotonic clock.
frames = []
rx = re.compile(r'WPEFrame (\d+) apply (-?\d+) acquire (-?\d+) latch (-?\d+) present (-?\d+) clock (-?\d+)')
for name, ts in tp("select name, ts from slice where name glob 'WPEFrame [0-9]*'"):
    m = rx.match(name)
    n, apply, acquire, latch, present, clock = (int(x) for x in m.groups())
    frames.append({'n': n, 'apply': apply, 'acquire': acquire, 'latch': latch, 'present': present,
                   'off': int(ts) - clock})
frames.sort(key=lambda f: f['n'])
if not frames:
    raise SystemExit(f'{args.trace}: no WPEFrame sections (needs a wpeview built with WPEFrameTimingAndroid)')
off = int(np.median([f['off'] for f in frames]))  # trace ts - monotonic

# Composites on the web process compositor thread and the scroll position each one drew.
comps = [(int(ts) - off, int(ts) + int(dur) - off) for ts, dur in tp(
    "select s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id join thread using(utid) "
    "where s.name = 'RenderLayerTree' and s.dur > 0 order by s.ts")]
drawn = [(int(ts) - off, float(v) / 100) for ts, v in tp(
    "select c.ts, c.value from counter c join counter_track t on c.track_id = t.id "
    "where t.name = 'wpe.scroll.drawn-y' order by c.ts")]
dts = np.array([d[0] for d in drawn])
dys = np.array([d[1] for d in drawn])

# One frame in flight: each applied buffer is the last composite that ended before it.
ci = 0
for f in frames:
    while ci + 1 < len(comps) and comps[ci + 1][1] <= f['apply']:
        ci += 1
    c = comps[ci] if comps and comps[ci][1] <= f['apply'] else None
    f['comp'] = c
    # Positions are only traced when they change: before the first one the page is at the top.
    k = np.searchsorted(dts, c[1], side='right') - 1 if c is not None else -1
    f['pos'] = (dys[k] if k >= 0 else 0.0) * args.dpr if c is not None else None

hz_file = base + '.hz'
hz = float(open(hz_file).read().split('=')[1]) if os.path.exists(hz_file) else None
shown = [f for f in frames if f['present'] > 0 and f['pos'] is not None]
pt = np.array([f['present'] for f in shown]) / 1e6  # ms
if hz is None:
    hz = 1000 / np.median(np.diff(pt))
period = 1000 / hz
pos = np.array([f['pos'] for f in shown])
n = len(shown)
dy = np.concatenate([[0], np.diff(pos)])
dropped = sum(1 for f in frames if f['present'] <= 0)

# The finger: touchinject replays the trajectory from start_ns (CLOCK_MONOTONIC).
start_ns = int(open(base + '.start').read().split()[1])
strokes = []
for line in open(base + '.traj'):
    p = line.split()
    t = start_ns / 1e6 + float(p[1])
    if p[0] == 'D':
        strokes.append({'down': t, 'pts': [(t, int(p[3]))]})
    elif p[0] == 'M':
        strokes[-1]['pts'].append((t, int(p[3])))
    elif p[0] == 'U':
        strokes[-1]['up'] = t


def per_vsync(j):
    return dy[j] / max(1, round((pt[j] - pt[j - 1]) / period))


def quality(a, b, moving_min=2):
    """held: vsyncs with no new frame or no movement while moving; uneven: steps off by >25%."""
    held = unev = cnt = 0
    for j in range(max(a, 1), min(b, n)):
        dt = pt[j] - pt[j - 1] if j > a else period
        around = (abs(per_vsync(j - 1)) + abs(per_vsync(j + 1 if j + 1 < n else j - 1))) / 2
        if around > moving_min:
            if dt > 1.5 * period:
                held += round(dt / period) - 1
            if abs(dy[j]) < 0.3:
                held += 1
        cnt += 1
    for j in range(max(a, 1) + 1, min(b, n) - 1):
        pv, c, q = per_vsync(j - 1), per_vsync(j), per_vsync(j + 1)
        e = (pv + q) / 2
        if abs(e) > 6 and abs(c - e) > 0.25 * abs(e):
            unev += 1
    return cnt, held, unev


def stages(idx):
    """Where the time goes for these presented frames, ms."""
    rows = []
    for j in idx:
        f = shown[j]
        c = f['comp']
        if c is None:
            continue
        rows.append(((c[1] - c[0]) / 1e6, (f['apply'] - c[1]) / 1e6, (f['latch'] - f['apply']) / 1e6,
                     (f['present'] - f['latch']) / 1e6, (f['present'] - c[0]) / 1e6))
    return np.array(rows)


out = []
for si, st in enumerate(strokes):
    d0, u0 = st['down'], st['up']
    nxt = strokes[si + 1]['down'] if si + 1 < len(strokes) else pt[-1] + 1
    fin = [k for k in range(n) if d0 <= pt[k] <= u0]
    post = [k for k in range(n) if u0 < pt[k] < nxt]
    xs = np.array([q[0] for q in st['pts']])
    ys = np.array([q[1] for q in st['pts']], float)
    travel = ys[0] - ys
    lag = None
    if len(fin) >= 4 and abs(travel[-1]) > 40:
        ft = pt[fin]
        cp = pos[fin] - pos[max(fin[0] - 1, 0)]
        best = None
        for L in np.arange(-10, 150, 0.5):
            f_at = np.interp(ft - L, xs, travel)
            moving = np.abs(f_at) > 25
            if moving.sum() < 3:
                continue
            o = np.median(cp[moving] - f_at[moving])
            err = np.mean(np.abs(cp[moving] - f_at[moving] - o))
            if best is None or err < best[0]:
                best = (err, L)
        if best:
            lag = (round(float(best[1]), 1), round(float(best[0]), 1))
    fq = quality(fin[0], fin[-1] + 1) if fin else (0, 0, 0)
    end = post[0] if post else None
    still = 0
    for k in post:
        still = still + 1 if abs(dy[k]) < 0.5 else 0
        end = k
        if still >= 6:
            end = k - 6
            break
    flq = quality(post[0], end + 1) if post else (0, 0, 0)
    st_f, st_l = stages(fin), stages(post[:(end - post[0] + 1) if post else 0])

    def med(a, i):
        return round(float(np.median(a[:, i])), 1) if len(a) else None
    out.append({'down_ms': round(d0 - start_ns / 1e6), 'finger_px': round(float(travel[-1])),
                'content_px_during_finger': round(float(pos[fin[-1]] - pos[max(fin[0] - 1, 0)])) if fin else 0,
                'lag_ms': lag[0] if lag else None, 'lag_fit_err_px': lag[1] if lag else None,
                'finger_frames': fq[0], 'finger_held': fq[1], 'finger_uneven': fq[2],
                'fling_frames': flq[0], 'fling_held': flq[1], 'fling_uneven': flq[2],
                'fling_px': round(float(pos[end] - pos[max(post[0] - 1, 0)])) if post else 0,
                'finger_composite_to_present_ms': med(st_f, 4), 'fling_composite_to_present_ms': med(st_l, 4)})

print(f'{os.path.basename(args.trace)}: {len(frames)} frames, {n} presented with a position, '
      f'{dropped} never presented, '
      f'{hz:.0f} Hz, {len(strokes)} strokes')
for g in out:
    print(f"  t={g['down_ms']:6d} finger {g['finger_px']:5d}px content {g['content_px_during_finger']:5d}px "
          f"lag {g['lag_ms']}ms(err {g['lag_fit_err_px']}) | fin fr {g['finger_frames']:3d} held {g['finger_held']:2d} "
          f"unev {g['finger_uneven']:2d} | fling fr {g['fling_frames']:3d} held {g['fling_held']:2d} "
          f"unev {g['fling_uneven']:2d} {g['fling_px']}px | comp->present "
          f"{g['finger_composite_to_present_ms']}/{g['fling_composite_to_present_ms']} ms")


def tot(k):
    return sum(g[k] for g in out)


lags = [g['lag_ms'] for g in out if g['lag_ms'] is not None]
print(f"  TOTAL finger frames {tot('finger_frames')} held {tot('finger_held')} uneven {tot('finger_uneven')} | "
      f"fling frames {tot('fling_frames')} held {tot('fling_held')} uneven {tot('fling_uneven')} | "
      f"lag median {np.median(lags) if lags else float('nan'):.1f}ms")

# Where the time goes, over every presented frame while a finger is down or a fling runs.
active = sorted({k for st in strokes for k in range(n) if st['down'] <= pt[k] <= st['up'] + 1500})
s = stages(active)
if len(s):
    print('  stages over active frames, ms (p50 / p90 / max):')
    for i, label in enumerate(['composite', 'composite end -> UI apply', 'apply -> SF latch', 'latch -> present',
                               'composite start -> present']):
        col = s[:, i]
        print(f'    {label:28s} {np.percentile(col, 50):6.1f} {np.percentile(col, 90):6.1f} {col.max():6.1f}')

# Where finger-to-photon lag goes while a finger is down: Android's batching (MotionEvent delivered
# after its event time), the sample's age when the scrolling thread gets it, how old the finger
# position a frame shows is when the scrolling thread picks it (resampling), then the frame pipeline.


def in_fingers(t_ms):
    return any(st['down'] <= t_ms <= st['up'] for st in strokes)


def counter(name):
    return [((int(ts) - off) / 1e6, float(v)) for ts, v in tp(
        "select c.ts, c.value from counter c join counter_track t on c.track_id = t.id "
        f"where t.name = '{name}' order by c.ts")]


delivery = []
for name, ts in tp("select name, ts from slice where name glob 'WPETouch action 2 *'"):
    ev = int(name.split()[4])
    if in_fingers(ev / 1e6):
        delivery.append((int(ts) - off - ev) / 1e6)
input_age = [v / 1000 for t, v in counter('wpe.scroll.input-age-us') if in_fingers(t)]
target_age = [v / 1000 for t, v in counter('wpe.scroll.resample-target-age-us') if in_fingers(t)]
sf = stages([k for k in range(n) if in_fingers(pt[k])])


def med(a):
    return f'{np.median(a):5.1f}' if len(a) else '    -'


if len(sf):
    print(f'  finger-down lag split, medians ms: Android delivery after event time {med(delivery)} | '
          f'sample age at scrolling thread {med(input_age)} | position age when a frame samples it {med(target_age)} | '
          f'composite {med(sf[:, 0])} + to UI apply {med(sf[:, 1])} + to SF latch {med(sf[:, 2])} '
          f'+ to present {med(sf[:, 3])}')

# Energy from the power rails (cumulative uJ, sampled every 250 ms) over the gestures and flings.
w0, w1 = strokes[0]['down'], strokes[-1]['up'] + 1500
rails = {}
for name, ts, v in tp("select t.name, c.ts, c.value from counter c join counter_track t on c.track_id = t.id "
                      "where t.name glob 'power.rails.*' order by c.ts"):
    rails.setdefault(name[len('power.rails.'):], []).append(((int(ts) - off) / 1e6, float(v)))
power = {}
for rail, samples in rails.items():
    t = np.array([x[0] for x in samples])
    e = np.array([x[1] for x in samples])
    if len(t) > 1 and t[0] <= w0 and t[-1] >= w1:
        power[rail] = (np.interp(w1, t, e) - np.interp(w0, t, e)) / (w1 - w0)  # uJ/ms = mW
if power:
    groups = {'cpu': ['cpu.big', 'cpu.mid', 'cpu.little'], 'gpu': ['gpu', 'gpu.l2'], 'display': ['display'],
              'memory': ['memory.interface', 'ddr.a', 'ddr.c', 'system.fabric']}
    parts = {g: sum(power.get(r, 0) for r in rs) for g, rs in groups.items()}
    print(f'  power over the gestures, mW: ' + ' '.join(f'{g} {v:.0f}' for g, v in parts.items()) +
          f" (cpu big/mid/little {power.get('cpu.big', 0):.0f}/{power.get('cpu.mid', 0):.0f}/"
          f"{power.get('cpu.little', 0):.0f}), "
          f'all rails {sum(power.values()):.0f}')

if args.frames:
    with open(args.frames, 'w') as fo:
        fo.write('n\tcomp_start\tcomp_end\tapply\tacquire\tlatch\tpresent\tpos_px\n')
        for f in frames:
            c = f['comp'] or (0, 0)
            row = (f['n'], c[0], c[1], f['apply'], f['acquire'], f['latch'], f['present'], f['pos'])
            fo.write('\t'.join(str(v) for v in row) + '\n')
if args.json:
    json.dump({'gestures': out, 'hz': hz, 'frames': len(frames), 'dropped': dropped,
               'lag_split_ms': {'android_delivery': float(np.median(delivery)) if delivery else None,
                                'input_age': float(np.median(input_age)) if input_age else None,
                                'position_age': float(np.median(target_age)) if target_age else None,
                                'composite': float(np.median(sf[:, 0])) if len(sf) else None,
                                'to_apply': float(np.median(sf[:, 1])) if len(sf) else None,
                                'to_latch': float(np.median(sf[:, 2])) if len(sf) else None,
                                'to_present': float(np.median(sf[:, 3])) if len(sf) else None},
               'power_mw': power}, open(args.json, 'w'), indent=1)
