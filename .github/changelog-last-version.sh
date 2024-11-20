#! /usr/bin/env bash
set -eu -o pipefail
version_string=$(awk -e '/^##\s+/ { gsub(/##\s+/, ""); print; exit; }' "$1")
read -r wpe_android_ver wpe_webkit_ver date <<< "${version_string/ - / }"
if [[ -n $wpe_webkit_ver && $wpe_webkit_ver = \(* ]] ; then
    wpe_webkit_ver=${wpe_webkit_ver:1:-1}
fi
echo "RELEASE_WPE_ANDROID_VERSION=$wpe_android_ver"
echo "RELEASE_WPE_WEBKIT_VERSION=$wpe_webkit_ver"
echo "RELEASE_DATE=$date"
