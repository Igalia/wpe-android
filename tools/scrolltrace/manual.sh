#!/bin/bash
# manual.sh best|noadpf|default [url]: open url in a MiniBrowser build for a hand test, with one of the
# configurations measured on the scroll branch:
#   best    : Chrome-style resampling, 1-euro filter at 30 Hz, Chrome-like ADPF
#   noadpf  : the same without performance hints (no extra power)
#   default : every debug.wpe.* property at its default
# PKG selects the package (default org.wpewebkit.tools.minibrowser); builds installed side by side share the
# launcher icon and name, so open them with this script. Properties are read once per process: every
# MiniBrowser is force-stopped before the start.
# The phone: ANDROID_SERIAL, or the only device attached.
if [ -z "$ANDROID_SERIAL" ]; then
    ANDROID_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [ "$(echo "$ANDROID_SERIAL" | wc -w)" = 1 ] || { echo "$0: set ANDROID_SERIAL (devices: $ANDROID_SERIAL)" >&2; exit 1; }
fi
export ANDROID_SERIAL
D=$(dirname "$0")
cfg=${1:-best}; url=${2:-https://www.igalia.com/}
PKG=${PKG:-org.wpewebkit.tools.minibrowser}
"$D"/setcfg.sh
case $cfg in
best) adb shell setprop debug.wpe.scroll.resamplemode chrome; adb shell setprop debug.wpe.scroll.resamplefilter 30
      adb shell setprop debug.wpe.perf.hints chrome ;;
noadpf) adb shell setprop debug.wpe.scroll.resamplemode chrome; adb shell setprop debug.wpe.scroll.resamplefilter 30
      adb shell setprop debug.wpe.perf.hints 0 ;;
default) ;;
*) echo "usage: $0 best|noadpf|default [url]" >&2; exit 1 ;;
esac
for p in $(adb shell pm list packages org.wpewebkit.tools.minibrowser | tr -d '\r' | sed 's/^package://'); do
    adb shell am force-stop $p
done
adb shell am start -n $PKG/org.wpewebkit.tools.minibrowser.MainActivity -d "$url" >/dev/null
echo "$cfg: $PKG on $url"; adb shell getprop | grep -E 'debug\.wpe\.(scroll\.resample(mode|filter)|perf\.hints)\]'
