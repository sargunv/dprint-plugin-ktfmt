# Runs real dprint against the packaged plugin (mise run package).

setup_file() {
  bats_require_minimum_version 1.5.0
  # shellcheck source=SCRIPTDIR/../scripts/lib.sh
  source "$BATS_TEST_DIRNAME/../scripts/lib.sh"
  local plugin_dir="$BATS_FILE_TMPDIR/plugin"
  mkdir -p "$plugin_dir"
  cp "$repo_root/dist/$plugin_name-$(host_target).zip" "$plugin_dir/"
  PLUGIN_REF="$("$repo_root/scripts/plugin-json.sh" "$plugin_dir")"
  export PLUGIN_REF
  # Keep the user's plugin cache out of it; dprint wants an absolute path.
  export DPRINT_CACHE_DIR="$BATS_FILE_TMPDIR/dprint-cache"
}

setup() {
  # shellcheck source=SCRIPTDIR/../scripts/lib.sh
  source "$BATS_TEST_DIRNAME/../scripts/lib.sh"
  ws="$BATS_TEST_TMPDIR/ws"
  mkdir -p "$ws"
  cd "$ws" || exit 1
}

# write_config '<extra top-level JSON members, each followed by a comma>'
write_config() {
  cat >"$ws/dprint.json" <<JSON
{
  ${1:-}
  "plugins": ["$PLUGIN_REF"]
}
JSON
}

dprint_ws() {
  dprint "$@" --config "$ws/dprint.json" --config-discovery=false
}

write_sources() {
  cat >"$ws/Example.kt" <<'KT'
package example
import kotlin.collections.List
class Example(val items: List<String>, val name: String, val description: String, val count: Int) {
  fun describe(): String { return items.joinToString(separator = ", ", prefix = name + ": ", postfix = " (" + description + ")") }
}
KT
  cat >"$ws/build.gradle.kts" <<'KTS'
plugins { kotlin("jvm") version "2.0.0"; application }
dependencies { implementation("org.example:library:1.0.0"); testImplementation(kotlin("test")) }
application { mainClass.set("example.MainKt") }
KTS
}

# Keeps the unformatted sources outside the workspace for expect_ktfmt.
save_sources() {
  orig="$BATS_TEST_TMPDIR/orig"
  mkdir -p "$orig"
  cp "$ws/Example.kt" "$ws/build.gradle.kts" "$orig/"
}

restore_sources() {
  cp "$orig/Example.kt" "$orig/build.gradle.kts" "$ws/"
}

# expect_ktfmt <file> [ktfmt CLI style flag]: compares the workspace's <file>
# to the ktfmt CLI's output for the saved original.
expect_ktfmt() {
  local file="$1"
  shift
  run --separate-stderr ktfmt_cli "$@" - "--stdin-name=$file" <"$orig/$file"
  [ "$status" -eq 0 ]
  diff -u <(printf '%s\n' "$output") "$ws/$file"
}

@test "fmt matches the ktfmt CLI for .kt and .kts with the default style" {
  write_config
  write_sources
  save_sources

  run dprint_ws fmt
  [ "$status" -eq 0 ]

  run ! cmp -s "$ws/Example.kt" "$orig/Example.kt"
  expect_ktfmt Example.kt
  expect_ktfmt build.gradle.kts
}

@test "fmt matches the ktfmt CLI with the google and kotlinlang styles" {
  write_sources
  save_sources

  for style in google kotlinlang; do
    restore_sources
    write_config "\"ktfmt\": { \"style\": \"$style\" },"
    run dprint_ws fmt
    [ "$status" -eq 0 ]
    expect_ktfmt Example.kt "--$style-style"
    expect_ktfmt build.gradle.kts "--$style-style"
  done
}

@test "fmt formats stdin" {
  write_config
  write_sources

  run --separate-stderr dprint_ws fmt --stdin Example.kt <"$ws/Example.kt"
  [ "$status" -eq 0 ]
  [[ "$output" == *$'fun describe(): String {\n'* ]]
}

@test "check reports unformatted files and passes once formatted" {
  write_config
  write_sources

  run dprint_ws check
  [ "$status" -ne 0 ]
  [[ "$output" == *"Example.kt"* ]]
  [[ "$output" == *"build.gradle.kts"* ]]

  run dprint_ws fmt
  [ "$status" -eq 0 ]
  run dprint_ws check
  [ "$status" -eq 0 ]
}

@test "a syntax error is reported per file without stopping the run" {
  write_config
  write_sources
  save_sources
  printf 'class Broken {\n  fun oops( {\n' >"$ws/Broken.kt"
  cp "$ws/Broken.kt" "$orig/"

  run dprint_ws fmt
  [ "$status" -ne 0 ]
  [[ "$output" == *"Broken.kt"* ]]
  [[ "$output" != *"Example.kt"* ]]
  cmp -s "$ws/Broken.kt" "$orig/Broken.kt"
  expect_ktfmt Example.kt
  expect_ktfmt build.gradle.kts
}

@test "an unknown config property is a diagnostic" {
  write_config '"ktfmt": { "definitelyUnknownOption": true },'

  run dprint_ws output-resolved-config
  [ "$status" -ne 0 ]
  [[ "$output" == *"definitelyUnknownOption"* ]]
  [[ "$output" == *"Unknown property"* ]]
}

@test "lineWidth comes from the style, the global config, then the plugin config" {
  write_config '"ktfmt": {},'
  run dprint_ws output-resolved-config
  [ "$status" -eq 0 ]
  [[ "$output" == *'"lineWidth": 100'* ]]

  write_config '"lineWidth": 80, "ktfmt": {},'
  run dprint_ws output-resolved-config
  [ "$status" -eq 0 ]
  [[ "$output" == *'"lineWidth": 80'* ]]

  write_config '"lineWidth": 80, "ktfmt": { "lineWidth": 120 },'
  run dprint_ws output-resolved-config
  [ "$status" -eq 0 ]
  [[ "$output" == *'"lineWidth": 120'* ]]
}
