#!/usr/bin/env bash
# Build the native FM-1 emulator (native/fm1-jni over Joakim Eriksson's fm1-emu, MIT) into
# app/src/main/jniLibs/<abi>/libfm1emu.so. Needs cargo, rustup's Android targets and the NDK
# (ANDROID_NDK_HOME, ANDROID_NDK_LATEST_HOME, or an ndk/ under the SDK).
#   ABIS="arm64-v8a x86_64" scripts/build-fm1-native.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="https://github.com/joakimeriksson/fm1-emu.git"
# v0.5.1: Android AArch64 JIT, P33 power regs, and ISA fixes (Melodee / dual-core firmwares).
COMMIT="${FM1_EMU_COMMIT:-c7e47c4fcbdaafb4beffa40493da1b2b07ffa263}"
SRC="native/fm1-emu-src"
ABIS="${ABIS:-arm64-v8a x86_64}"
API=26
# Optional local patches on top of the pin (none once upstream has the decoder fixes we need).
shopt -s nullglob
PATCHES=(native/patches/*.patch)
shopt -u nullglob
if [[ ${#PATCHES[@]} -eq 0 ]]; then
  PATCH_ID=none
else
  PATCH_ID="$(cat "${PATCHES[@]}" | sha256sum | awk '{print $1}')"
fi

if [[ ! -f "$SRC/.commit" || "$(cat "$SRC/.commit")" != "$COMMIT" || ! -f "$SRC/.patches" || "$(cat "$SRC/.patches")" != "$PATCH_ID" ]]; then
  rm -rf "$SRC"
  git init -q "$SRC"
  git -C "$SRC" remote add origin "$REPO"
  git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
  git -C "$SRC" checkout -q FETCH_HEAD
  for p in "${PATCHES[@]}"; do
    patch -d "$SRC" -p1 --forward --batch < "$p" || {
      echo "patch $p failed against $COMMIT" >&2
      exit 1
    }
  done
  echo "$COMMIT" > "$SRC/.commit"
  echo "$PATCH_ID" > "$SRC/.patches"
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "No Android NDK found (set ANDROID_NDK_HOME)." >&2
  exit 1
fi
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android ;;
    armeabi-v7a) target=armv7-linux-androideabi ;;
    *) echo "Unknown ABI $abi" >&2; exit 1 ;;
  esac
  clang="$BIN/${target/armv7/armv7a}$API-clang"
  rustup target add "$target" >/dev/null 2>&1 || true
  upper="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  env "CARGO_TARGET_${upper}_LINKER=$clang" "CC_${target//-/_}=$clang" "AR_${target//-/_}=$BIN/llvm-ar" \
    cargo build --release --manifest-path native/fm1-jni/Cargo.toml --target "$target" \
      --target-dir native/target
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "native/target/$target/release/libfm1emu.so" "app/src/main/jniLibs/$abi/libfm1emu.so"
  "$BIN/llvm-strip" --strip-unneeded "app/src/main/jniLibs/$abi/libfm1emu.so"
  echo "Built app/src/main/jniLibs/$abi/libfm1emu.so ($(du -h "app/src/main/jniLibs/$abi/libfm1emu.so" | cut -f1))"
done
