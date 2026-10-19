# dprint-plugin-ktfmt

A [dprint](https://dprint.dev) plugin that formats Kotlin (`.kt` and `.kts`)
with [ktfmt](https://github.com/Kotlin/ktfmt). Its output matches the ktfmt
CLI's.

## Setup

```sh
dprint add sargunv/dprint-plugin-ktfmt
```

This adds an entry like the following to your dprint config:

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
`--kotlinlang-style`), and the other options override its settings.

Like other dprint plugins, `lineWidth` and `newLineKind` default to dprint's
global options. If your global `lineWidth` isn't 100, set `"ktfmt": {
"lineWidth": 100 }` to keep ktfmt's default. The global `indentWidth` and
`useTabs` don't apply: each style sets its own indents, and ktfmt only indents
with spaces.

`.editorconfig` files are not read, and range formatting is not supported.

## Versioning

The plugin version tracks ktfmt's: plugin `0.65.0` bundles ktfmt `0.65`.
Upgrading the plugin may change formatting the same way upgrading ktfmt would.

## Platforms

Linux (x86_64, aarch64), macOS (aarch64), and Windows (x86_64).

## Performance

Formatting okhttp's 617 Kotlin files with `dprint fmt` on a 32-core Linux
machine:

| Setup                                      | Time   |
| ------------------------------------------ | ------ |
| dprint-plugin-exec + ktfmt on the JVM      | 40.6 s |
| dprint-plugin-exec + ktfmt's native binary | 1.11 s |
| dprint-plugin-ktfmt                        | 0.8 s  |

## License

MIT OR Apache-2.0. The plugin bundles ktfmt, which is Apache-2.0 (see
`KTFMT_LICENSE`).
