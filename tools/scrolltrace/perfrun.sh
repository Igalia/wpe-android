#!/bin/bash
# perfrun.sh <app> <60|90|auto> <scenario> <url> <outdir> [seed] : one finger-gesture run (touchinject +
# gestures.py) under Perfetto: scheduling, CPU frequency, atrace gfx/input/view plus the app's own
# sections (WebKit's debug.wpe.atrace ones, wpeview's WPEFrame/WPETouch), the SurfaceFlinger frame
# timeline and the power rails. framestats.py reads the result.
# app = suffix of the MiniBrowser package to measure (org.wpewebkit.tools.minibrowser.<app>), or - for the plain
# package; install builds side by side with applicationIdSuffix. Needs the instrumented WebKit (wpe.scroll.*
# counters) and wpeview's frame timing (WPEFrame) for framestats.py.
# VIDEO=1 also screen-records the run with the touch dot, to check the trace against a video.
# PROPS="k=v ..." sets debug.wpe.* properties after setcfg.sh. Writes <name>.pftrace, .traj, .start (the
# injector's start, CLOCK_MONOTONIC ns), .hz and, with VIDEO=1, .mp4.
set -e
SP=$(cd "$(dirname "$0")" && pwd)
app=$1; hz=$2; scen=$3; url=$4; out=$5; seed=${6:-1}
# The phone: ANDROID_SERIAL, or the only device attached.
if [ -z "$ANDROID_SERIAL" ]; then
    ANDROID_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [ "$(echo "$ANDROID_SERIAL" | wc -w)" = 1 ] || { echo "$0: set ANDROID_SERIAL (devices: $ANDROID_SERIAL)" >&2; exit 1; }
fi
export ANDROID_SERIAL
S=$ANDROID_SERIAL
A="adb -s $S"
mkdir -p "$out"
name=perf-$app-$hz-$scen-s$seed${TAG:+-$TAG}
PKG=org.wpewebkit.tools.minibrowser; [ "$app" != - ] && PKG=$PKG.$app
DEVTRACE=/data/misc/perfetto-traces/wpe-perfrun.pftrace

$A shell input keyevent KEYCODE_WAKEUP; $A shell svc power stayon usb
$A shell settings put system show_touches 1
$A shell settings put system accelerometer_rotation 0
case $hz in
    auto) $A shell settings put system min_refresh_rate 0; $A shell settings put system peak_refresh_rate 90.0 ;;
    *) $A shell settings put system min_refresh_rate $hz.0; $A shell settings put system peak_refresh_rate $hz.0 ;;
esac
"$SP/setcfg.sh" proto >/dev/null
for kv in $PROPS; do $A shell setprop "debug.wpe.${kv%%=*}" "${kv#*=}"; done
$A shell setprop debug.wpe.atrace scroll
for p in $($A shell pm list packages org.wpewebkit.tools.minibrowser | tr -d '\r' | sed 's/^package://'); do $A shell am force-stop $p; done
$A shell am force-stop com.android.chrome
sleep 1

apps="  atrace_apps: \"$PKG\""
for i in $(seq 0 9); do apps="$apps
  atrace_apps: \"$PKG:WPEWebProcess$i\""; done
cfg=$(cat <<EOF
buffers { size_kb: 196608 fill_policy: DISCARD }
buffers { size_kb: 8192 fill_policy: DISCARD }
data_sources { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "sched/sched_wakeup_new"
  ftrace_events: "sched/sched_process_exit"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  ftrace_events: "power/cpu_frequency"
  ftrace_events: "power/cpu_idle"
  ftrace_events: "power/gpu_frequency"
  atrace_categories: "gfx"
  atrace_categories: "input"
  atrace_categories: "view"
$apps
  compact_sched { enabled: true }
  buffer_size_kb: 32768
  drain_period_ms: 250
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config {
  scan_all_processes_on_start: true
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
data_sources { config { name: "android.power" target_buffer: 1 android_power_config {
  battery_poll_ms: 250
  collect_power_rails: true
  battery_counters: BATTERY_COUNTER_CURRENT
  battery_counters: BATTERY_COUNTER_CHARGE
} } }
duration_ms: 180000
EOF
)
$A shell rm -f $DEVTRACE
pid=$(echo "$cfg" | $A shell perfetto --txt -c - -o $DEVTRACE --background-wait 2>/dev/null | tr -d '\r' | tail -1)
case $pid in ''|*[!0-9]*) echo "perfrun.sh: perfetto did not start ($pid)" >&2; exit 1 ;; esac

$A shell am start -n $PKG/org.wpewebkit.tools.minibrowser.MainActivity -d "$url" >/dev/null
sleep 10
# The 16 KB compatibility dialog dims the app; dismiss it.
if [ "$($A shell dumpsys window windows 2>/dev/null | grep -c 'u0 android}')" != "0" ]; then
    b=$($A exec-out uiautomator dump /dev/tty 2>/dev/null | tr '<' '\n' | grep "Don't Show Again" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | head -1)
    if [ -n "$b" ]; then set -- $b; $A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 3; fi
fi
if [ "$hz" != auto ]; then
    for i in $(seq 1 30); do
        $A shell input keyevent KEYCODE_WAKEUP
        cur=$($A shell dumpsys display | grep -m1 -o 'mActiveRenderFrameRate=[0-9]*' | cut -d= -f2)
        [ "$cur" = "$hz" ] && break
        sleep 1
    done
    [ "$cur" = "$hz" ] || echo "perfrun.sh: WARNING display at ${cur} Hz, not $hz, for $name" >&2
fi
$A exec-out screencap -p > "$out/$name.start.png"
python3 "$SP/gestures.py" "$scen" --seed "$seed" ${RATE:+--rate $RATE} > "$out/$name.traj"
$A push -q "$out/$name.traj" /data/local/tmp/traj.txt
if [ "$VIDEO" = 1 ]; then
    dur=$(awk 'END { printf "%d", $2 / 1000 + 6 }' "$out/$name.traj")
    $A shell rm -f /sdcard/rr.mp4
    $A shell "screenrecord --bit-rate 40000000 --time-limit $dur /sdcard/rr.mp4 & sleep 0.5; /data/local/tmp/touchinject < /data/local/tmp/traj.txt; wait" > "$out/$name.start"
    $A pull -q /sdcard/rr.mp4 "$out/$name.mp4"
else
    $A shell "/data/local/tmp/touchinject < /data/local/tmp/traj.txt" > "$out/$name.start"
    sleep 3
fi
$A shell dumpsys display | grep -m1 -o 'mActiveRenderFrameRate=[0-9.]*' > "$out/$name.hz" || true

$A shell kill -TERM "$pid"
# kill -0 is denied (SELinux, shell -> perfetto), so watch /proc until it has written the trace.
for i in $(seq 1 60); do $A shell "test -d /proc/$pid" || break; sleep 0.5; done
$A pull -q $DEVTRACE "$out/$name.pftrace"
$A shell rm -f $DEVTRACE
$A shell setprop debug.wpe.atrace off
if [ "$($A shell dumpsys window windows 2>/dev/null | grep -c 'u0 android}')" != "0" ]; then
    echo "perfrun.sh: WARNING a system dialog is on top, $name is not valid" >&2
fi
echo "$out/$name.pftrace"
