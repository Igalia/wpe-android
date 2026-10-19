#!/bin/bash
# variantcheck.sh <dir> <app> <scenario> <variant>... : late-frame causes (latecause.py) and the first stroke's
# steps (flickstart.py) for each variant, over all seeds.
SP=$(cd "$(dirname "$0")" && pwd)
d=$1; app=$2; sc=$3; shift 3
for v in "$@"; do
  late=0; causes=""
  for t in $d/perf-$app-90-$sc-s*-$v.pftrace; do
    out=$(python3 "$SP/latecause.py" "$t" 2>/dev/null)
    n=$(echo "$out" | grep -c "t=")
    late=$((late + n))
    causes="$causes $(echo "$out" | grep causes | sed 's/  causes: //; s/composite started late/started-late/; s/long composite/long-comp/; s/missed SF latch/missed-latch/; s/UI hand-off/ui/')"
    first=$(python3 "$SP/flickstart.py" "$t" 2>/dev/null | grep "stroke 1:" | awk -F'|' '{s=""; for(i=2;i<=6;i++){split($i,b," "); s=s b[1] " "} print s}')
    echo "  $v $(basename $t .pftrace | sed 's/.*-\(s[0-9]\)-.*/\1/'): late $n | first stroke steps: $first"
  done
  echo "$v: late frames total $late | causes:$causes"
done
