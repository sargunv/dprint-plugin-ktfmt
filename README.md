# dprint-plugin-ktfmt

A [dprint](https://dprint.dev) plugin that formats Kotlin (`.kt` and `.kts`)
with [ktfmt](https://github.com/Kotlin/ktfmt).

It is a process plugin: a native executable built from ktfmt itself with GraalVM
native-image. dprint starts it once per run and sends it every file, so there is
no JVM startup per file, and the output matches the ktfmt CLI's.

## Setup

```sh
dprint add sargunv/dprint-plugin-ktfmt
```

This adds an entry like the following to your dprint config. Process plugins
need the checksum, which `dprint add` fills in for you.

```jsonc
{
  "plugins": ["https://plugins.dprint.dev/sargunv/ktfmt-0.65.0.json@<checksum>"]
}
```

## Configuration

Options go under the `ktfmt` key:

```jsonc
{ "ktfmt": { "style": "google", "lineWidth": 100 } }
```

| Option                 | Values                         | Default                               |
| ---------------------- | ------------------------------ | ------------------------------------- |
| `style`                | `meta`, `google`, `kotlinlang` | `meta`                                |
| `lineWidth`            | integer                        | global `lineWidth`, else 100          |
| `blockIndent`          | integer                        | from `style` (2, 2, 4)                |
| `continuationIndent`   | integer                        | from `style` (4, 2, 4)                |
| `trailingCommas`       | `complete`, `onlyAdd`, `none`  | `onlyAdd` for `meta`, else `complete` |
| `removeUnusedImports`  | boolean                        | `true`                                |
| `preserveLambdaBreaks` | boolean                        | `true`                                |
| `newLineKind`          | `auto`, `lf`, `crlf`, `system` | global `newLineKind`, else `auto`     |

`style` picks one of ktfmt's presets (`--meta-style`, `--google-style`,
`--kotlinlang-style`); the other options override individual settings of it.

`lineWidth` and `newLineKind` follow dprint's global options like other dprint
plugins. Mind this if your global `lineWidth` is not ktfmt's 100: set `"ktfmt":
{ "lineWidth": 100 }` to keep ktfmt's default. The global `indentWidth` is not
used, because each style defines its own block and continuation indents. ktfmt
only indents with spaces, so the global `useTabs` is ignored and `"ktfmt": {
"useTabs": true }` is reported as a config error.

`.editorconfig` files are not read. Range formatting is not supported yet.

## Versioning

The plugin version tracks ktfmt's: plugin `0.65.0` bundles ktfmt `0.65`.
Upgrading the plugin may change formatting the same way upgrading ktfmt would.

## Platforms

Release builds are native executables for Linux (x86_64, aarch64), macOS
(aarch64), and Windows (x86_64).

## Performance

Formatting okhttp's 617 Kotlin files (486 of them changed) on a 32-core Linux
machine:

| Setup                                      | Full run |
| ------------------------------------------ | -------- |
| dprint-plugin-exec + ktfmt on the JVM      | 40.6 s   |
| dprint-plugin-exec + ktfmt's native binary | 1.10 s   |
| this plugin                                | 2.4 s    |

Release builds use GraalVM Community, whose native images only have the serial
garbage collector. On a large run most of the time is single-threaded GC pauses:
about 1.9 s of the 2.4 s above. A G1 build of the same plugin took 0.80 s, but
native-image G1 needs Oracle GraalVM and only works on Linux. For now, running
ktfmt's own native binary through dprint-plugin-exec is faster on large runs.

## License

MIT OR Apache-2.0. The plugin bundles ktfmt, which is Apache-2.0 (see
`KTFMT_LICENSE`).
