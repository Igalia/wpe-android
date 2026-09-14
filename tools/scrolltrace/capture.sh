#!/bin/bash
#
# Copyright (C) 2026 Igalia S.L.
#
# Records a systrace capture of a scroll gesture and pulls it for analyse.py.
#
# The engine-side slices only appear in builds that carry the Android tracing backend in
# WTF (wtf/android/ATraceAnnotator.cpp); without it the capture still shows the Android
# input, SurfaceFlinger and scheduler tracks, but nothing about why the engine waited.
#
# Usage: capture.sh [-s serial] [-u url] [-o outdir] [-n gestures] [-d swipe-ms] [-c coverage] [-m secs]
#
# -m records for the given number of seconds while you scroll with a real finger, which is
#    the only way to reproduce what a finger actually does; without it the script drives
#    synthetic swipes, which are reproducible but evenly paced in a way a finger is not.

set -e

SERIAL=${ANDROID_SERIAL:-}
URL=""
OUTDIR=""
GESTURES=6
SWIPE_MS=700
COVERAGE=scroll
MANUAL=0
PKG=org.wpewebkit.tools.minibrowser
ADB=${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")}

while getopts "s:u:o:n:d:c:m:h" opt; do
    case $opt in
        s) SERIAL=$OPTARG ;;
        u) URL=$OPTARG ;;
        o) OUTDIR=$OPTARG ;;
        n) GESTURES=$OPTARG ;;
        d) SWIPE_MS=$OPTARG ;;
        c) COVERAGE=$OPTARG ;;
        m) MANUAL=$OPTARG ;;
        h) sed -n '2,17p' "$0"; exit 0 ;;
        *) exit 1 ;;
    esac
done

if [ -z "$SERIAL" ]; then
    SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
    echo "capture.sh: no device connected" >&2
    exit 1
fi
A="$ADB -s $SERIAL"

if [ -z "$OUTDIR" ]; then
    OUTDIR=$(mktemp -d "${TMPDIR:-/tmp}/scrolltrace-XXXXXX")
fi
mkdir -p "$OUTDIR"

echo "scrolltrace: device $SERIAL, output $OUTDIR"

# A dozing or locked phone reports a 0x0 viewport and silently invalidates the run.
$A shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
$A shell wm dismiss-keyguard >/dev/null 2>&1 || true
$A shell svc power stayon usb >/dev/null 2>&1 || true

# Thermal drift and refresh-rate switching both show up as scroll stalls that have nothing
# to do with the main thread, so pin the refresh rate and the rotation.
$A shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
$A shell settings put system min_refresh_rate 60.0 >/dev/null 2>&1 || true
$A shell settings put system peak_refresh_rate 60.0 >/dev/null 2>&1 || true

$A shell setprop debug.wpe.atrace "$COVERAGE"
$A shell am force-stop $PKG
sleep 1

# A reinstall brings back the 16 KB-alignment compatibility dialog, which overlays AND dims
# the app, so every gesture after it lands on nothing. Its button moves with the library
# list, so look the coordinates up rather than hardcoding them.
dismiss_compat_dialog() {
    [ "$($A shell dumpsys window windows 2>/dev/null | grep -c 'u0 android}')" != "0" ] || return 0
    local bounds
    bounds=$($A exec-out uiautomator dump /dev/tty 2>/dev/null \
        | tr '<' '\n' \
        | grep "Don't Show Again" \
        | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p' \
        | head -1)
    [ -n "$bounds" ] || return 0
    set -- $bounds
    echo "scrolltrace: dismissing the 16 KB compatibility dialog"
    $A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    sleep 3
}

# The app tag is sampled when a process starts tracing, so arm the capture buffer before
# launching: the web process then has it enabled from its first frame.
APPS="$PKG"
for i in $(seq 0 9); do
    APPS="$APPS,$PKG:WPEWebProcess$i,$PKG:WPENetworkProcess$i"
done

$A shell atrace --async_stop >/dev/null 2>&1 || true
$A shell atrace --async_start -b 65536 -a "$APPS" gfx view input sched freq idle >/dev/null

if [ -n "$URL" ]; then
    $A shell am start -n $PKG/.MainActivity -e url "$URL" >/dev/null
else
    $A shell am start -n $PKG/.MainActivity >/dev/null
fi

echo "scrolltrace: waiting for the page to settle"
sleep 12
dismiss_compat_dialog

# Presented-frame ground truth for the same window: the engine can report frames the
# display never got.
$A shell dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1 || true
$A shell dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1 || true
$A shell dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1 || true

SIZE=$($A shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
W=$(echo "$SIZE" | cut -d' ' -f1)
H=$(echo "$SIZE" | cut -d' ' -f2)
X=$((W / 2))
Y1=$((H * 4 / 5))
Y2=$((H / 5))

if [ "$MANUAL" != "0" ]; then
    echo
    echo "  >>> Scroll the page with your finger now, for ${MANUAL}s. <<<"
    echo
    for remaining in $(seq "$MANUAL" -1 1); do
        printf "\r  recording, %3ds left " "$remaining"
        sleep 1
    done
    printf "\r                        \r"
else
    echo "scrolltrace: $GESTURES swipes of ${SWIPE_MS}ms"
    for i in $(seq 1 "$GESTURES"); do
        $A shell input swipe $X $Y1 $X $Y2 "$SWIPE_MS"
        sleep 1
    done
fi

$A shell dumpsys SurfaceFlinger --timestats -dump > "$OUTDIR/surfaceflinger.timestats" 2>&1 || true
$A shell dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1 || true

# Plain text, not -z: atrace's compressed stream is raw deflate, and a failed decompression
# here costs the whole capture because the buffer is drained either way.
$A shell atrace --async_stop > "$OUTDIR/trace.txt"

# A system dialog on top dims the app and drops its input; the run is not comparable.
if [ "$($A shell dumpsys window windows 2>/dev/null | grep -c 'u0 android}')" != "0" ]; then
    echo "scrolltrace: WARNING a system dialog is on top, this run is not valid" >&2
fi

$A shell setprop debug.wpe.atrace off

echo "scrolltrace: captured $(wc -l < "$OUTDIR/trace.txt") trace lines"
echo "scrolltrace: analyse with  tools/scrolltrace/analyse.py $OUTDIR/trace.txt"
echo "$OUTDIR"
