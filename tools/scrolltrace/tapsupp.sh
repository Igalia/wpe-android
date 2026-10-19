#!/bin/bash
# tapsupp.sh <on|off> <outdir> [repeats] : tap-after-fling test on the MiniBrowser package PKG (tapsupp.py phases).
#   on  : default behaviour (the tap that stops a fling does not click)
#   off : debug.wpe.touch.clickafterflingstop=1 (the previous behaviour)
# The page (tapsupp/tap.html) reports clicks, mouse events, touches and the
# scroll position to the HTTP server; its log, cut per phase, is the result.
set -e
# The phone: ANDROID_SERIAL, or the only device attached.
if [ -z "$ANDROID_SERIAL" ]; then
    ANDROID_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [ "$(echo "$ANDROID_SERIAL" | wc -w)" = 1 ] || { echo "$0: set ANDROID_SERIAL (devices: $ANDROID_SERIAL)" >&2; exit 1; }
fi
export ANDROID_SERIAL
D=$(cd "$(dirname "$0")" && pwd)
mode=$1; out=$2; reps=${3:-3}
PKG=${PKG:-org.wpewebkit.tools.minibrowser}
PORT=8124
mkdir -p "$out"
python3 -m http.server $PORT --bind 127.0.0.1 --directory "$D/tapsupp" > "$out/server-$mode.log" 2>&1 &
srv=$!
trap 'kill $srv 2>/dev/null; adb reverse --remove tcp:$PORT 2>/dev/null || true' EXIT
adb reverse tcp:$PORT tcp:$PORT > /dev/null
"$D"/setcfg.sh
[ "$mode" = off ] && adb shell setprop debug.wpe.touch.clickafterflingstop 1 || adb shell setprop debug.wpe.touch.clickafterflingstop "''"
adb shell input keyevent KEYCODE_WAKEUP
for r in $(seq 1 "$reps"); do
    for phase in tap flingtap flingwait flingdrag; do
        adb shell am force-stop $PKG
        adb shell am start -n $PKG/org.wpewebkit.tools.minibrowser.MainActivity -d "http://localhost:$PORT/tap.html" > /dev/null
        sleep 4
        python3 "$D/tapsupp.py" $phase > "$out/$phase.traj"
        adb push -q "$out/$phase.traj" /data/local/tmp/traj.txt
        before=$(wc -l < "$out/server-$mode.log")
        adb shell "/data/local/tmp/touchinject < /data/local/tmp/traj.txt" > /dev/null
        sleep 3
        tail -n +$((before + 1)) "$out/server-$mode.log" | grep -o 'GET /log?[^ ]*' | sed 's|GET /log?||' > "$out/$mode-$phase-$r.events"
        printf '%s %-9s %s: clicks %s, mousedown %s, mouseover %s, touchstart %s, y at last touchend/500 ms later: %s / %s\n' \
            "$mode" $phase $r \
            "$(grep -c '^ev=click' "$out/$mode-$phase-$r.events" || true)" \
            "$(grep -c '^ev=mousedown' "$out/$mode-$phase-$r.events" || true)" \
            "$(grep -c '^ev=mouseover' "$out/$mode-$phase-$r.events" || true)" \
            "$(grep -c '^ev=touchstart' "$out/$mode-$phase-$r.events" || true)" \
            "$(grep '^ev=touchend' "$out/$mode-$phase-$r.events" | tail -1 | sed 's/.*&y=\([0-9]*\).*/\1/')" \
            "$(grep '^ev=after500' "$out/$mode-$phase-$r.events" | tail -1 | sed 's/.*&y=\([0-9]*\).*/\1/')"
    done
done | tee "$out/summary-$mode.txt"
adb shell am force-stop $PKG
adb shell setprop debug.wpe.touch.clickafterflingstop "''"
