#!/usr/bin/env bash
# Runs scripts/parity.sh in every style on okhttp at a pinned commit, fetched
# shallowly into build/okhttp.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

commit=aab431214cd0da8b74f6a288b6a2bb137a8d982e
dir="$repo_root/build/okhttp"
if [[ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" != "$commit" ]]; then
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 https://github.com/square/okhttp "$commit"
  git -C "$dir" checkout -q FETCH_HEAD
fi
for style in meta google kotlinlang; do
  "$repo_root/scripts/parity.sh" "$dir" "$style"
done
