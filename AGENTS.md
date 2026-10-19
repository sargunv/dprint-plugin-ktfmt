# dprint-plugin-ktfmt

dprint process plugin that formats Kotlin with ktfmt, compiled to a native
executable with GraalVM native-image.

## Project map

- `src/main/kotlin/dev/sargunv/dprint/ktfmt/protocol/`: dprint process plugin
  protocol (schema v5) server and parent-process checker.
- `src/main/kotlin/dev/sargunv/dprint/ktfmt/`: `Main.kt` (entry point and
  `KtfmtHandler`), `Config.kt` (config resolution and diagnostics), `Plugin.kt`
  (plugin info, version, formatting), `Worker.kt` (formatting inside a worker
  isolate).
- `src/main/java/dev/sargunv/dprint/ktfmt/isolates/`: `IsolatePool`, which
  spreads native formats across native-image isolates with separate heaps.
- `schema.json`: config JSON schema, shipped as a release asset and referenced
  by the plugin's `configSchemaUrl`. Keep it in sync with `Config.kt`
  (`ConfigTest` checks the keys).
- `src/test/resources/parity/`: golden ktfmt CLI outputs per style (`.txt` so
  the repo's own formatter skips them).
- `src/main/java/ktfmt/nativeimage/`, `src/main/native-image/`,
  `src/main/resources/META-INF/native-image/ktfmt/`: native-image configuration
  copied from ktfmt's own native build. `.../native-image/dprint-plugin-ktfmt/`
  registers this plugin's own resources (licenses, `plugin.properties`).
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
- Native formatting runs in worker isolates (`IsolatePool`), which share no
  objects with the main isolate: anything a format needs must cross as bytes,
  and the config crosses as `ResolvedConfig.workerJson`, so every setting must
  survive `resolvedJson()` round-tripping.
