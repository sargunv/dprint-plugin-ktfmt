# dprint-plugin-ktfmt

dprint process plugin that formats Kotlin with ktfmt, compiled to a native
executable with GraalVM native-image.

## Project map

- `src/main/kotlin/dev/sargunv/dprint/ktfmt/`: protocol server and ktfmt
  adapter.
- `src/main/java/ktfmt/nativeimage/`, `src/main/native-image/`,
  `src/main/resources/META-INF/native-image/`: native-image configuration copied
  from ktfmt's own native build.

## Dev tool commands

- `mise run check`: run every formatter and linter in report-only mode.
- `mise run fix`: apply every formatter and auto-fixable lint.

Use `mise tasks ls --all` for the full task list.

## Project invariants
