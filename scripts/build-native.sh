#!/usr/bin/env bash
# Builds libptts_ffi.so, Phonon's C library, for Android from gradium-ai/xn-ptts at a pinned
# commit, into app/src/main/jniLibs/{arm64-v8a,x86_64}: arm64 for phones such as the Galaxy S21,
# x86_64 for the emulator.
#
# Needs git, Rust (rustup), cargo-ndk (`cargo install cargo-ndk`) and the Android NDK, found
# through ANDROID_NDK_HOME.
#
#   scripts/build-native.sh               fetch the source if needed, then build
#   scripts/build-native.sh --fetch-only  only fetch it (CI restores its Rust cache in between)
#
# XN_PTTS_DIR overrides where the source goes (default: build/xn-ptts).
set -euo pipefail
cd "$(dirname "$0")/.."

XN_PTTS_REPO=https://github.com/gradium-ai/xn-ptts.git
# The commit the native library is built from. app/src/main/java/io/github/enoky/androidtts/
# PhononTTS.kt declares this commit's C interface: move both together.
XN_PTTS_COMMIT=e5110afd3ead725c940b6411eec4985fb8b111aa
SRC="${XN_PTTS_DIR:-build/xn-ptts}"

if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin "$XN_PTTS_REPO"
fi
if [ "$(git -C "$SRC" rev-parse -q --verify HEAD || true)" != "$XN_PTTS_COMMIT" ]; then
  echo "Fetching xn-ptts $XN_PTTS_COMMIT"
  git -C "$SRC" fetch -q --depth 1 origin "$XN_PTTS_COMMIT"
  git -C "$SRC" checkout -q --detach FETCH_HEAD
fi
if [ "$(git -C "$SRC" rev-parse HEAD)" != "$XN_PTTS_COMMIT" ]; then
  echo "error: $SRC is not at $XN_PTTS_COMMIT" >&2
  exit 1
fi
[ "${1:-}" = "--fetch-only" ] && exit 0

# Upstream's script: release build of the ptts-ffi crate for arm64-v8a and x86_64, with the
# ARMv8.2 dotprod and fp16 kernels (every Galaxy S21, Snapdragon 888 or Exynos 2100, has them)
# and 16 KB page alignment.
"$SRC/android/build.sh"

rm -rf app/src/main/jniLibs
mkdir -p app/src/main/jniLibs
cp -R "$SRC/android/jniLibs/." app/src/main/jniLibs/
ls -l app/src/main/jniLibs/*/libptts_ffi.so
