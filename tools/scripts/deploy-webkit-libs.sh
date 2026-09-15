#!/bin/bash
#
# Copyright (C) 2026 Igalia S.L.
#
# Installs the libraries from an incremental Cerbero WebKit build straight into the
# wpe-android tree, for iterating on a recipe patch.
#
# This is not the newcomer path: bootstrap.py --cerbero installs built *packages*, and
# producing those re-runs Cerbero's extract step, which deletes the WebKit source tree and
# its ninja build directory. After an incremental `ninja -C b`, use this instead.
#
# Usage: deploy-webkit-libs.sh [-a arch] [-c cerbero-path]

set -euo pipefail

ARCH=arm64
ABI=arm64-v8a
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CERBERO=$ROOT/build/cerbero

while getopts "a:c:h" opt; do
    case $opt in
        a) ARCH=$OPTARG; [ "$ARCH" = "x86_64" ] && ABI=x86_64 ;;
        c) CERBERO=$OPTARG ;;
        h) sed -n '2,12p' "$0"; exit 0 ;;
        *) exit 1 ;;
    esac
done

BUILD=$CERBERO/build/sources/android_$ARCH/wpewebkit-git/b/lib
# A Cerbero tree keeps the NDKs of earlier configurations around; the newest is the one in use.
NDK=$(ls -d "$CERBERO"/build/android-ndk-*/toolchains/llvm/prebuilt/linux-x86_64/bin 2>/dev/null | sort -V | tail -1)
IMPORTED=$ROOT/wpeview/src/main/cpp/imported/lib/$ABI
BUNDLE=$ROOT/wpeview/src/main/assets/injected-bundles/$ABI

for path in "$BUILD" "$NDK" "$IMPORTED" "$BUNDLE"; do
    if [ ! -d "$path" ]; then
        echo "deploy-webkit-libs: missing $path" >&2
        exit 1
    fi
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

for lib in libWPEWebKit-2.0.so libWPEInjectedBundle.so; do
    echo "== $lib =="
    cp "$BUILD/$lib" "$WORK/$lib"
    "$NDK/llvm-strip" --strip-debug "$WORK/$lib"

    # Android's package manager only unpacks libfoo.so, so versioned SONAME and NEEDED
    # entries have to become Android-legal names. The replacements are equal length and
    # written in place: patchelf rewrites these too but corrupts .gnu.version_r doing it.
    python3 - "$WORK/$lib" <<'PY'
import sys
path = sys.argv[1]
pairs = [
    (b"libexpat.so.1", b"libexpat_1.so"),
    (b"libicudata.so.70", b"libicudata_70.so"),
    (b"libicui18n.so.70", b"libicui18n_70.so"),
    (b"libicuuc.so.70", b"libicuuc_70.so"),
    (b"libnettle.so.8", b"libnettle_8.so"),
]
data = bytes(open(path, "rb").read())
for old, new in pairs:
    assert len(old) == len(new), (old, new)
    count = data.count(old)
    if count:
        data = data.replace(old, new)
        print(f"  {old.decode()} -> {new.decode()} ({count})")
open(path, "wb").write(data)
PY
done

if readelf -d "$WORK/libWPEWebKit-2.0.so" | grep -qE "so\.[0-9]"; then
    echo "deploy-webkit-libs: a versioned SONAME survived, Android will fail to load it" >&2
    readelf -d "$WORK/libWPEWebKit-2.0.so" | grep -E "so\.[0-9]" >&2
    exit 1
fi

cp "$WORK/libWPEWebKit-2.0.so" "$IMPORTED/libWPEWebKit-2.0.so"
cp "$WORK/libWPEInjectedBundle.so" "$BUNDLE/libWPEInjectedBundle.so"
echo "installed into $IMPORTED and $BUNDLE"
echo "now: ANDROID_HOME=... ./gradlew assembleDebug && adb install -r ... && adb shell pm clear <pkg>"
