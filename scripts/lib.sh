# shellcheck shell=bash disable=SC2034
# Shared helpers for the scripts in this directory. Source, don't execute.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plugin_name="dprint-plugin-ktfmt"

# The bare semver tag on HEAD, else 0.0.0, as build.gradle.kts computes it.
plugin_version() {
  git -C "$repo_root" describe --tags --exact-match --match '[0-9]*.[0-9]*.[0-9]*' 2>/dev/null ||
    echo 0.0.0
}

# Rust-style target triple of the host, as used in release asset names.
host_target() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    arm64) arch=aarch64 ;;
    amd64) arch=x86_64 ;;
  esac
  case "$(uname -s)" in
    Linux) echo "$arch-unknown-linux-gnu" ;;
    Darwin) echo "$arch-apple-darwin" ;;
    MINGW* | MSYS* | CYGWIN*) echo "$arch-pc-windows-msvc" ;;
    *)
      echo "unsupported host: $(uname -s) $arch" >&2
      return 1
      ;;
  esac
}

# dprint plugin.json platform key for a target triple.
platform_key() {
  case "$1" in
    x86_64-unknown-linux-gnu) echo linux-x86_64 ;;
    aarch64-unknown-linux-gnu) echo linux-aarch64 ;;
    x86_64-apple-darwin) echo darwin-x86_64 ;;
    aarch64-apple-darwin) echo darwin-aarch64 ;;
    x86_64-pc-windows-msvc) echo windows-x86_64 ;;
    aarch64-pc-windows-msvc) echo windows-aarch64 ;;
    *)
      echo "unknown target: $1" >&2
      return 1
      ;;
  esac
}

sha256() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# The official ktfmt CLI, the reference output. Formats stdin to stdout with "-".
ktfmt_cli() {
  mise --cd "$repo_root" run --quiet _ktfmt "$@"
}
