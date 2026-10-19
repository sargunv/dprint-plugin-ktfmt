#!/usr/bin/env bash
# Writes plugin.json for the platform zips in a directory and prints its dprint
# config reference (path@checksum).
# usage: scripts/plugin-json.sh [--release] [dir]   (dir defaults to dist/)
#   --release: reference zips by their plugins.dprint.dev release asset URL
#   instead of relative to plugin.json.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

release=false
if [[ "${1:-}" == --release ]]; then
  release=true
  shift
fi
dir="$(cd "${1:-$repo_root/dist}" && pwd)"
version="$(plugin_version)"

base="./"
if $release; then
  base="https://plugins.dprint.dev/sargunv/$plugin_name/$version/asset/"
fi

entries=""
for zip_path in "$dir/$plugin_name"-*.zip; do
  [[ -e "$zip_path" ]] || continue
  zip_name="$(basename "$zip_path")"
  target="${zip_name#"$plugin_name-"}"
  target="${target%.zip}"
  entries+="$(
    cat <<JSON
,
  "$(platform_key "$target")": {
    "reference": "$base$zip_name",
    "checksum": "$(sha256 "$zip_path")"
  }
JSON
  )"
done
[[ -n "$entries" ]] || {
  echo "no $plugin_name-*.zip files in $dir" >&2
  exit 1
}

cat >"$dir/plugin.json" <<JSON
{
  "schemaVersion": 2,
  "kind": "process",
  "name": "$plugin_name",
  "version": "$version"$entries
}
JSON
echo "$dir/plugin.json@$(sha256 "$dir/plugin.json")"
