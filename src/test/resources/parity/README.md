# Parity fixtures

`ParityTest` formats each file in `input/` with the plugin and compares the
result to the ktfmt CLI's output in `meta/`, `google/`, and `kotlinlang/`. Files
end in `.txt` so this repo's own formatter leaves them alone.

The inputs are four files from [okhttp](https://github.com/square/okhttp)
(Apache-2.0, commit `aab431214cd0da8b74f6a288b6a2bb137a8d982e`) and a
hand-written `Messy.kt`.

The expected outputs come from the ktfmt CLI pinned in `mise.toml`, formatting
copies of the inputs in place with their real names, so `.kts` files are parsed
as scripts. From this directory:

```sh
for style in meta google kotlinlang; do
  tmp=$(mktemp -d)
  for f in input/*.txt; do cp "$f" "$tmp/$(basename "$f" .txt)"; done
  mise run --quiet _ktfmt "--$style-style" "$tmp"/*
  for f in "$tmp"/*; do cp "$f" "$style/$(basename "$f").txt"; done
done
```

Regenerate them when bumping ktfmt.
