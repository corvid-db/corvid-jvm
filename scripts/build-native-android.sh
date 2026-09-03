#!/usr/bin/env bash
# build-native-android.sh — fetch the pinned engine release's ANDROID cdylib
# artifact sets (both ABIs), verify them the way fetch.sh verifies the
# desktop ones, compile the C-only JNI shim for each ABI with the NDK, and
# lay the pairs out as the android Gradle build's jniLibs source:
#
#   build/natives-android-jnilibs/arm64-v8a/{libcorvid.so,libcorvidjni.so}
#   build/natives-android-jnilibs/x86_64/{libcorvid.so,libcorvidjni.so}
#
# The pin is fetch.sh's CORVID_VERSION (the one variable that owns it).
# Requirements: an NDK (ANDROID_NDK_HOME) whose clang wrappers target API
# 24 — the engine's android cdylibs are built at API 24 (a superset of the
# AAR's minSdk 26); r28b is the proven version.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

CORVID_VERSION="$(grep -oE '^CORVID_VERSION="v[^"]+"' fetch.sh | head -1 | cut -d'"' -f2)"
[ -n "$CORVID_VERSION" ] || { echo "build-native-android: cannot read CORVID_VERSION from fetch.sh" >&2; exit 1; }
REPO="corvid-db/corvid"
BASE_URL="https://github.com/${REPO}/releases/download/${CORVID_VERSION}"
DL="$ROOT/deps/dl"
mkdir -p "$DL"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK/toolchains" ]; then
    echo "build-native-android: ANDROID_NDK_HOME must point at an NDK root (one with toolchains/)" >&2
    exit 1
fi
PREBUILT="$NDK/toolchains/llvm/prebuilt"
case "$(uname -s)" in
    Darwin) HOST_WANT=darwin ;;
    Linux)  HOST_WANT=linux ;;
    *) echo "build-native-android: unsupported OS $(uname -s) (the AAR assembly runs on macOS/Linux)" >&2; exit 1 ;;
esac
if [ -d "$PREBUILT/$HOST_WANT-arm64" ]; then
    HOST_TAG="$HOST_WANT-arm64"
elif [ -d "$PREBUILT/$HOST_WANT-x86_64" ]; then
    HOST_TAG="$HOST_WANT-x86_64"
else
    HOST_TAG="$(ls "$PREBUILT" 2>/dev/null | grep "^$HOST_WANT" | head -1 || true)"
    [ -n "$HOST_TAG" ] || { echo "build-native-android: $PREBUILT has no ${HOST_WANT}-* toolchain" >&2; exit 1; }
fi
TC="$PREBUILT/$HOST_TAG/bin"
API=24

# ---- fetch + verify both ABIs (fetch.sh's discipline) --------------------
curl -fsSL -o "$DL/checksums.txt" "$BASE_URL/checksums.txt"

stage_for() { # TARGET ABI
    local T="$1" ABI="$2"
    local ARCHIVE="corvid-ffi-${CORVID_VERSION}-${T}.tar.gz"
    curl -fsSL -o "$DL/$ARCHIVE" "$BASE_URL/$ARCHIVE"
    local EXPECTED ACTUAL
    EXPECTED="$(awk -v f="$ARCHIVE" '$2 == f { print $1 }' "$DL/checksums.txt")"
    [ -n "$EXPECTED" ] || { echo "build-native-android: $ARCHIVE not in the release checksums.txt (no android artifacts on $CORVID_VERSION?)" >&2; exit 1; }
    if command -v shasum >/dev/null 2>&1; then
        ACTUAL="$(shasum -a 256 "$DL/$ARCHIVE" | awk '{ print $1 }')"
    else
        ACTUAL="$(sha256sum "$DL/$ARCHIVE" | awk '{ print $1 }')"
    fi
    [ "$ACTUAL" = "$EXPECTED" ] || { echo "build-native-android: sha256 MISMATCH for $ARCHIVE" >&2; exit 1; }
    rm -rf "deps/android-$T"; mkdir -p "deps/android-$T"
    tar xzf "$DL/$ARCHIVE" -C "deps/android-$T" --strip-components=1
    # the release's golden fixtures must match the vendored set byte for byte
    local f name
    for f in "$ROOT"/golden/*.txt; do
        name="$(basename "$f")"
        cmp -s "$f" "deps/android-$T/golden/$name" || {
            echo "build-native-android: vendored golden/$name differs from the release's copy" >&2; exit 1;
        }
    done
    echo "build-native-android: $ARCHIVE verified (sha256 ok, golden byte-identical)"

    local OUT="build/natives-android-jnilibs/$ABI"
    rm -rf "$OUT"; mkdir -p "$OUT"
    cp "deps/android-$T/libcorvid.so" "$OUT/"
    "$TC/$T$API-clang" -O2 -fPIC -shared -Wall -Wextra \
        -I"deps/android-$T" \
        native/corvid_jni.c "$OUT/libcorvid.so" \
        -o "$OUT/libcorvidjni.so"
    ls -l "$OUT"
}

stage_for aarch64-linux-android arm64-v8a
stage_for x86_64-linux-android x86_64

echo "build-native-android: done — jniLibs pairs staged under build/natives-android-jnilibs/ (pin $CORVID_VERSION, NDK $(basename "$NDK"))"
