#!/bin/bash
# setcfg.sh up|proto : every prototype property at upstream value, or all at default
if [ "$1" = up ]; then v=(off 1 1 1 1 0 0 0 0 0); else v=("''" "''" "''" "''" "''" "''" "''" "''" "''" "''"); fi
i=0; for p in skia.programcache skia.dfpaths touch.blockingpointer touch.blockingscroll scroll.animationwaitsformain tiles.postframe scroll.samplepercomposite scroll.vsyncsampling scroll.devicepixelsnap scroll.inputresampling; do adb shell setprop debug.wpe.$p "${v[$i]}"; i=$((i+1)); done
# Properties added later (round 8 on): back to the default, or to upstream behaviour with "up". A leftover
# value from an earlier run silently changes the next one.
for p in skia.reducedvariants tiles.budget tiles.margin tiles.prerenderbudget scroll.vsyncwake scroll.resamplelead perf.hints tiles.prerendered egl.blobcache touch.usec tiles.slivers scroll.resamplesmooth scroll.resamplemode scroll.resamplefilter touch.clickafterflingstop; do adb shell setprop debug.wpe.$p "''"; done
if [ "$1" = up ]; then u=1; else u="''"; fi
for p in touch.flingcontinues touch.buffered; do adb shell setprop debug.wpe.$p "$u"; done
adb shell setprop debug.wpe.atrace off
