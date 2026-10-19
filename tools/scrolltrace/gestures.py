#!/usr/bin/env python3
# gestures.py <scenario> [--rate HZ] [--x X] [--seed N] : trajectory for touchinject on stdout.
# Finger-like movement (acceleration, lift-off at speed, jitter, slop), not `input swipe`'s
# constant-velocity line. Positions are device pixels; y decreasing scrolls the page down.
import argparse
import math
import random

ap = argparse.ArgumentParser()
ap.add_argument('scenario')
ap.add_argument('--rate', type=float, default=120.0, help='touch panel report rate')
ap.add_argument('--x', type=int, default=900)
ap.add_argument('--seed', type=int, default=1)
args = ap.parse_args()
rnd = random.Random(args.seed)
dt = 1000.0 / args.rate
out = []
t = 0.0


def stroke(points_fn, duration, y0, x0, lift=True, down=True):
    """points_fn(tau) -> displacement (dx, dy) from the start, tau in [0, 1]."""
    global t
    if down:
        out.append(f'D {t:.2f} {x0} {y0}')
    n = max(1, int(duration / dt))
    for i in range(1, n + 1):
        dx, dy = points_fn(i / n)
        jitter = rnd.uniform(-0.6, 0.6)
        out.append(f'M {t + i * dt:.2f} {round(x0 + dx + jitter)} {round(y0 + dy + jitter)}')
    t += n * dt
    if lift:
        out.append(f'U {t + dt * 0.5:.2f}')
    return x0 + points_fn(1)[0], y0 + points_fn(1)[1]


def velocity_profile(shape):
    """Normalized displacement s(tau) from a velocity curve, integrated numerically."""
    steps = 400
    v = [shape(i / steps) for i in range(steps + 1)]
    acc = [0.0]
    for i in range(1, steps + 1):
        acc.append(acc[-1] + (v[i] + v[i - 1]) / 2)
    total = acc[-1]
    return lambda tau: acc[min(steps, int(tau * steps))] / total


# A flick: the finger accelerates and lifts off near its peak speed.
flick_s = velocity_profile(lambda tau: math.sin(math.pi * 0.75 * tau) + 0.05)
# A reading drag: speeds up, slows down, comes to rest before lifting (no fling).
drag_s = velocity_profile(lambda tau: math.sin(math.pi * tau) ** 1.5 + 0.02 * math.sin(9 * tau))


def flick(dist, dur, y0=1900):
    drift = rnd.uniform(-15, 15)
    return stroke(lambda tau: (drift * tau, -dist * flick_s(tau)), dur, y0, args.x)


def drag(dist, dur, y0=1700, rest=150):
    global t
    drift = rnd.uniform(-10, 10)
    x, y = stroke(lambda tau: (drift * tau, -dist * drag_s(tau)), dur, y0, args.x, lift=False)
    # The finger rests: the panel keeps reporting the same point with tiny noise.
    for i in range(1, int(rest / dt) + 1):
        out.append(f'M {t + i * dt:.2f} {round(x + rnd.uniform(-0.5, 0.5))} {round(y + rnd.uniform(-0.5, 0.5))}')
    t += rest
    out.append(f'U {t + dt * 0.5:.2f}')


def pause(ms):
    global t
    t += ms


s = args.scenario
if s == 'flicks':          # 6 fast finger flicks, alternating so the page doesn't run out
    for i in range(6):
        d = rnd.uniform(850, 1000)
        flick(d if i % 2 == 0 else -d, rnd.uniform(100, 130), y0=1900 if i % 2 == 0 else 750)
        pause(1500)
elif s == 'downflicks':     # 6 fast flicks all down the page: new content all the time (tile pressure)
    for i in range(6):
        flick(rnd.uniform(850, 1000), rnd.uniform(100, 130), y0=1900)
        pause(1500)
elif s == 'medflicks':     # moderate flicks, the common reading case
    for i, sign in enumerate((1, 1, -1, -1, 1, -1)):
        flick(sign * rnd.uniform(350, 500), rnd.uniform(110, 150), y0=1800 if sign > 0 else 900)
        pause(1300)
elif s == 'drags':         # slow reading drags that stop before lifting
    for i in range(5):
        drag(rnd.uniform(350, 550), rnd.uniform(600, 900))
        pause(700)
elif s == 'catch':         # flick, then the finger catches the fling and drags a bit
    for i in range(4):
        flick(900, 110)
        pause(250)
        drag(rnd.uniform(150, 250), 400, y0=1400, rest=200)
        pause(900)
elif s == 'mixed':         # what a person reading a page does
    drag(400, 700)
    pause(600)
    flick(500, 130)
    pause(1100)
    drag(300, 600)
    pause(500)
    flick(950, 110)
    pause(400)
    drag(200, 400, y0=1400, rest=200)
    pause(800)
    flick(450, 140)
    pause(1200)
    for i in range(3):
        drag(-350, 700, y0=900)
        pause(500)  # scroll back up
else:
    raise SystemExit(f'unknown scenario {s}')
print('\n'.join(out))
