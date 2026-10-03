#!/bin/sh
# Print the host's memory, its biggest processes and any new kernel OOM
# messages every 30 seconds, so a run that gets killed from outside
# still leaves a trace in the job log.

kernel_messages() {
    sudo dmesg -T 2>/dev/null | grep -iE 'out of memory|killed process|oom-kill'
}

trap 'kill $sleeper 2>/dev/null; exit 0' TERM

seen=$(kernel_messages | wc -l)
while :; do
    memory=$(free -m | awk '/^Mem:/ { printf "used %d available %d MB", $3, $7 } /^Swap:/ { printf ", swap used %d MB", $3 }')
    top=$(ps -eo rss=,comm= --sort=-rss | head -n 3 | awk '{ printf "%s %d MB; ", $2, $1 / 1024 }')
    echo "[host-monitor] $(date -u +%T) $memory; $top"
    count=$(kernel_messages | wc -l)
    if [ "$count" -gt "$seen" ]; then
        kernel_messages | tail -n $((count - seen)) | sed 's/^/[host-monitor] kernel: /'
        seen=$count
    fi
    sleep 30 &
    sleeper=$!
    wait $sleeper
done
