#!/usr/bin/env bash
# Zips the native executable into dist/dprint-plugin-ktfmt-<target>.zip.
# usage: scripts/package.sh [target]   (defaults to the host target)
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

target="${1:-$(host_target)}"
platform_key "$target" >/dev/null

exe="$plugin_name"
[[ "$target" == *-windows-* ]] && exe="$exe.exe"
src="$repo_root/build/native/nativeCompile/$exe"
[[ -f "$src" ]] || {
  echo "missing $src; run 'mise run native' first" >&2
  exit 1
}

dist="$repo_root/dist"
zip_path="$dist/$plugin_name-$target.zip"
mkdir -p "$dist"
rm -f "$zip_path"
# Only the executable: the AWT libraries native-image emits next to it are never loaded.
if command -v zip >/dev/null; then
  (cd "$(dirname "$src")" && zip -q -X -9 "$zip_path" "$exe")
else
  (cd "$(dirname "$src")" && 7z a -tzip -mx=9 -bso0 "$zip_path" "$exe")
fi
echo "$zip_path"
