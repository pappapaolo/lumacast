#!/usr/bin/env bash
# Builds static libcrypto for each Android ABI into third_party/openssl-build/<abi>.
# Needs ANDROID_NDK_ROOT (or ANDROID_HOME with ndk/28.2.13676358) and perl.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/28.2.13676358}"
HOST="$(uname -s | tr A-Z a-z)-x86_64"
export ANDROID_NDK_ROOT="$NDK" PATH="$NDK/toolchains/llvm/prebuilt/$HOST/bin:$PATH"
for pair in "armeabi-v7a android-arm" "arm64-v8a android-arm64"; do
  set -- $pair; ABI=$1; TARGET=$2
  OUT="$ROOT/third_party/openssl-build/$ABI"
  [ -f "$OUT/lib/libcrypto.a" ] && { echo "$ABI: already built"; continue; }
  WORK="$(mktemp -d)"
  cp -R "$ROOT/third_party/openssl/." "$WORK"
  ( cd "$WORK" && git clean -xfdq 2>/dev/null || true
    ./Configure "$TARGET" -D__ANDROID_API__=26 no-shared no-tests no-apps no-docs no-module no-engine --prefix="$WORK/out" >/dev/null
    make -j"$(getconf _NPROCESSORS_ONLN)" build_libs >/dev/null
    make install_dev >/dev/null )
  mkdir -p "$OUT" && cp -R "$WORK/out/include" "$WORK/out/lib" "$OUT/"
  rm -rf "$WORK"; echo "$ABI: done"
done
