#!/usr/bin/env bash
# Minimal end-to-end check of the packaged plugin with real dprint, for
# platforms without bats (Windows). The bats suite in tests/ is the real test.
# usage: scripts/smoke.sh [target]   (defaults to the host target)
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ws="$(mktemp -d)"
trap 'rm -rf "$ws"' EXIT
cp "$repo_root/dist/$plugin_name-${1:-$(host_target)}.zip" "$ws/"
ref="$("$repo_root/scripts/plugin-json.sh" "$ws")"
# Relative to the config file, so no native Windows path ends up in the JSON.
printf '{ "plugins": ["./plugin.json@%s"] }\n' "${ref##*@}" >"$ws/dprint.json"
printf 'class A{fun f( ){}}\n' >"$ws/A.kt"

cd "$ws"
dprint license --config dprint.json | grep -q "Apache License"
dprint check --config dprint.json >/dev/null && {
  echo "check passed on an unformatted file" >&2
  exit 1
}
dprint fmt --config dprint.json
dprint check --config dprint.json
diff -u <(printf 'class A {\n  fun f() {}\n}\n') A.kt
echo "smoke test passed"
