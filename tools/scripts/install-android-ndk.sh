#!/bin/bash

# Run the script using:
#    ./tools/scripts/install-android-ndk.sh
# After it finishes define the ANDROID_HOME:
#    export ANDROID_HOME=\$HOME/Android/Sdk
# And with that gradle should work:
#   ./gradlew assembleDebug

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

set -eu

sdk_root="$ANDROID_HOME"
android_ndk_zip="commandlinetools-linux-13114758_latest.zip"
android_ndk_url="https://dl.google.com/android/repository/$android_ndk_zip"
latest_dir="$sdk_root/cmdline-tools/latest"
sdkmanager="$latest_dir/bin/sdkmanager"

if ! command -v apt-get >/dev/null 2>&1; then
    echo "Error: apt-get not found (Debian/Ubuntu only)." >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "Error: curl not found." >&2
    exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
    echo "Error: unzip not found." >&2
    exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
    sudo apt-get install -y openjdk-17-jdk
fi

if [[ -x "$sdkmanager" ]]; then
    echo "Android SDK already installed at $ANDROID_HOME"
    exit 0
fi

mkdir -p "$sdk_root/cmdline-tools"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

zip_path="$tmpdir/$android_ndk_zip"
extract_dir="$tmpdir/extracted"

mkdir -p "$extract_dir"

curl -fL -o "$zip_path" "$android_ndk_url"
unzip -q "$zip_path" -d "$extract_dir"

rm -rf "$latest_dir"
mv "$extract_dir/cmdline-tools" "$latest_dir"

yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null

packages=(
  "platform-tools"
  "platforms;android-35"
  "build-tools;35.0.0"
  "cmake;3.31.1"
)

"$sdkmanager" --sdk_root="$sdk_root" "${packages[@]}"

echo "Done. ANDROID_HOME=$ANDROID_HOME"
echo "Make sure you define ANDROID_HOME in your shell in order to use it with:"
echo " export ANDROID_HOME=\$HOME/Android/Sdk"

