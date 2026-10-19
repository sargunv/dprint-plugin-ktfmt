# dprint-plugin-ktfmt

dprint process plugin that formats Kotlin with ktfmt, compiled to a native
executable with GraalVM native-image.

## Project map

- `src/main/kotlin/dev/sargunv/dprint/ktfmt/`:
  - `Main.kt`: entry point.
  - `KtfmtHandler.kt`: what the plugin answers to dprint (plugin info, license,
    config, formatting).
  - `Config.kt`: config resolution and diagnostics.
  - `Format.kt`: formatting with ktfmt, also as the worker isolates run it.
- `src/main/kotlin/dev/sargunv/dprint/ktfmt/protocol/`: the dprint process
  plugin protocol (schema v5): `PluginServer`, the `PluginHandler` it calls,
  message framing, and the parent-process checker.
- `src/main/java/dev/sargunv/dprint/ktfmt/isolates/IsolatePool.java`: spreads
  native formats across native-image isolates with separate heaps.
- `src/main/java/ktfmt/nativeimage/`, `src/main/native-image/`,
  `src/main/resources/META-INF/native-image/ktfmt/`: native-image configuration
  copied from ktfmt's own native build. `.../native-image/dprint-plugin-ktfmt/`
  registers this plugin's resources (licenses, `plugin.properties`).
- `schema.json`: config JSON schema, shipped as a release asset. `ConfigTest`
  checks its keys against `Config.kt`.
- `src/test/resources/parity/`: golden ktfmt CLI outputs per style (`.txt` so
  this repo's formatter skips them).
- `tests/plugin.bats`: integration tests running real dprint on the packaged
  plugin.
- `scripts/`: packaging (`package.sh`, `plugin-json.sh`), parity against the
  ktfmt CLI (`parity.sh`, `parity-okhttp.sh`), and the Windows CI smoke test
  (`smoke.sh`).
- `.github/workflows/`: `ci.yml` and `release.yml` (bare semver tags matching
  `gradle.properties`) share the native build matrix in `native.yml`.

## Dev tool commands

- `mise run check`: run every formatter and linter in report-only mode.
- `mise run fix`: apply every formatter and auto-fixable lint.
- `mise run build`: build the JVM plugin and run unit tests.
- `mise run native`: compile the native executable (about a minute).
- `mise run package`: native build, then zip it into `dist/`.
- `mise run test:integration`: package, then run the bats tests.
- `mise run parity <corpus-dir> [meta|google|kotlinlang]`: package, then diff
  against the ktfmt CLI.
- `mise run parity:okhttp`: parity in every style on okhttp at a pinned commit.
- `mise run verify`: check, build, and integration tests.

Use `mise tasks ls --all` for the full task list.

## Project invariants

- The plugin version is ktfmt's version (`gradle.properties`); release tags must
  match it.
- Release zips contain only the executable, named `dprint-plugin-ktfmt[.exe]`,
  at the root.
- stdout carries only protocol messages; `main` points `System.out` at stderr.
- Resources read at run time must be registered for native-image; the JVM unit
  tests won't notice a missing one, the bats suite will.
- ktfmt isn't always idempotent, and dprint retries until output is stable, so
  compare single passes (`--skip-stable-format`) against the CLI.
- Native formats run in worker isolates, which share no objects with the main
  one: the config crosses as `ResolvedConfig.workerJson`, so every setting must
  survive a `resolvedJson()` round trip.
