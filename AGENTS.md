# dprint-plugin-ktfmt

dprint process plugin that formats Kotlin with ktfmt, compiled to a native
executable with GraalVM native-image.

## Project map

- `src/main/kotlin/dev/sargunv/dprint/ktfmt/`: protocol server and ktfmt
  adapter.
- `src/main/java/ktfmt/nativeimage/`, `src/main/native-image/`,
  `src/main/resources/META-INF/native-image/`: native-image configuration copied
  from ktfmt's own native build.
- `scripts/package.sh`: zip the native executable into
  `dist/dprint-plugin-ktfmt-<target>.zip`.
- `scripts/plugin-json.sh`: write `plugin.json` for the zips in a directory,
  with relative references, or plugins.dprint.dev asset URLs with `--release`.
- `scripts/parity.sh`: diff plugin output against the ktfmt CLI on a corpus.
- `scripts/smoke.sh`: minimal dprint end-to-end check, for Windows CI.
- `tests/*.bats`: integration tests running real dprint on the packaged plugin.
- `.github/workflows/`: `ci.yml` and `release.yml` (bare semver tags matching
  `gradle.properties`) share the native matrix in `native.yml`.

## Dev tool commands

- `mise run check`: run every formatter and linter in report-only mode.
- `mise run fix`: apply every formatter and auto-fixable lint.
- `mise run build`: build the JVM plugin and run unit tests.
- `mise run native`: compile the native executable (about a minute).
- `mise run package`: native build, then zip it into `dist/`.
- `mise run test:integration`: package, then run the bats tests.
- `mise run parity <corpus-dir> [meta|google|kotlinlang]`: package, then diff
  against the ktfmt CLI.
- `mise run verify`: check, build, and integration tests.

Use `mise tasks ls --all` for the full task list.

## Project invariants

- The plugin version is ktfmt's version (`gradle.properties`); release tags must
  match it.
- Release zips contain only the executable, named `dprint-plugin-ktfmt[.exe]`,
  at the root.
- ktfmt isn't always idempotent, and dprint retries until output is stable, so
  compare single passes (`--skip-stable-format`) against the CLI.
