#!/usr/bin/env python3
# tapsupp.py <phase> : touchinject trajectory (device pixels, ms) for the tap-after-fling test.
#   tap        : a tap with nothing moving (must click)
#   flingtap   : a fast flick up, then 150 ms after lift-off a tap (must not click; the fling stops)
#   flingwait  : a flick, then a tap 6 s later, after the fling ended (must click)
#   flingdrag  : a flick, then 150 ms later a finger that drags (scrolls; no click)
import math
import sys

phase = sys.argv[1]
dt = 1000.0 / 120  # touch panel report rate
out = []
t = 0.0


def flick(x=540, y0=1900, dist=1000, dur=100):
    global t
    out.append(f'D {t:.2f} {x} {y0}')
    n = int(dur / dt)
    # Accelerating finger that lifts off near its peak speed.
    for i in range(1, n + 1):
        tau = i / n
        s = (1 - math.cos(math.pi * 0.75 * tau)) / (1 - math.cos(math.pi * 0.75))
        out.append(f'M {t + i * dt:.2f} {x} {round(y0 - dist * s)}')
    t += n * dt
    out.append(f'U {t + dt / 2:.2f}')
    t += dt / 2


def tap(x=540, y=1200, hold=70):
    global t
    out.append(f'D {t:.2f} {x} {y}')
    out.append(f'U {t + hold:.2f}')
    t += hold


def drag(x=540, y0=1700, dist=500, dur=400):
    global t
    out.append(f'D {t:.2f} {x} {y0}')
    n = int(dur / dt)
    for i in range(1, n + 1):
        out.append(f'M {t + i * dt:.2f} {x} {round(y0 - dist * i / n)}')
    t += n * dt
    # Comes to rest before lifting, so no new fling.
    for i in range(1, 13):
        out.append(f'M {t + i * dt:.2f} {x} {round(y0 - dist)}')
    t += 12 * dt
    out.append(f'U {t:.2f}')


if phase == 'tap':
    tap()
elif phase == 'flingtap':
    flick()
    t += 150
    tap()
elif phase == 'flingwait':
    flick()
    t += 6000
    tap()
elif phase == 'flingdrag':
    flick()
    t += 150
    drag()
else:
    sys.exit(f'unknown phase {phase}')
print('\n'.join(out))
