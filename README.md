# dprint-plugin-ktfmt

A [dprint](https://dprint.dev) plugin that formats Kotlin (`.kt` and `.kts`)
with [ktfmt](https://github.com/Kotlin/ktfmt).

It is a process plugin: a native executable built from ktfmt itself with GraalVM
native-image. dprint starts it once per run and sends it every file, so there is
no JVM startup per file, and the output matches the ktfmt CLI's. (ktfmt is not
idempotent on a few inputs, such as some KDoc tables; dprint formats those again
until the output is stable.)

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

Formatting okhttp's 617 Kotlin files (486 of them changed, google style) with
`dprint fmt --incremental=false` on a 32-core Linux machine:

| Setup                                      | Full run |
| ------------------------------------------ | -------- |
| dprint-plugin-exec + ktfmt on the JVM      | 40.6 s   |
| dprint-plugin-exec + ktfmt's native binary | 1.11 s   |
| this plugin                                | 0.8 s    |

GraalVM Community native images only have a single-threaded, stop-the-world
garbage collector. To keep one file's collection from pausing all the others,
the plugin formats in up to 8 native-image isolates, each with its own heap, the
way dprint-plugin-prettier runs a pool of V8 isolates. That costs memory: about
1.1 GB peak on the run above, against 0.8 GB with a single heap (2.5 s).

## License

MIT OR Apache-2.0. The plugin bundles ktfmt, which is Apache-2.0 (see
`KTFMT_LICENSE`).
