#!/usr/bin/env bash
# build-native.sh — compile the JNI shim (native/corvid_jni.c) against the
# fetched engine artifacts in deps/current for macOS/Linux, into
# build/native/, next to a copy of the cdylib the shim links (rpath
# $ORIGIN / @loader_path). Windows: build-native.ps1.
#
# Requirements: a C compiler (cc/clang/gcc) and a JDK (JAVA_HOME or java
# on PATH — we need its include/ headers). Run ./fetch.sh first.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

if [ ! -f deps/current/corvid.h ]; then
    echo "build-native: deps/current/corvid.h missing — run ./fetch.sh first" >&2
    exit 1
fi

# ---- locate a JDK's include dir -----------------------------------------
JAVA_HOME_DIR="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME_DIR" ]; then
    if command -v java >/dev/null 2>&1; then
        J="$(command -v java)"
        # resolve symlinks then strip bin/java
        J="$(cd "$(dirname "$J")" && cd "$(dirname "$(readlink "$J" || echo "$J")"/..)/.." 2>/dev/null && pwd || echo "$J")"
        JAVA_HOME_DIR="$(dirname "$(dirname "$J")")"
    fi
fi
if [ -n "$JAVA_HOME_DIR" ] && [ -f "$JAVA_HOME_DIR/include/jni.h" ]; then
    JNI_INC="-I$JAVA_HOME_DIR/include"
else
    echo "build-native: cannot locate a JDK include dir (set JAVA_HOME)" >&2
    exit 1
fi

OS="$(uname -s)"
case "$OS" in
    Darwin) PLAT_INC="-I$JAVA_HOME_DIR/include/darwin"; OUT=libcorvidjni.dylib; LIB=libcorvid.dylib; RPATH="-Wl,-rpath,@loader_path" ;;
    Linux)  PLAT_INC="-I$JAVA_HOME_DIR/include/linux";  OUT=libcorvidjni.so;   LIB=libcorvid.so;   RPATH='-Wl,-rpath,$ORIGIN' ;;
    *) echo "build-native: unsupported OS $OS (use build-native.ps1 on Windows)" >&2; exit 1 ;;
esac

mkdir -p build/native
echo "build-native: compiling native/corvid_jni.c -> build/native/$OUT"
# shellcheck disable=SC2086
cc -O2 -fPIC -shared -Wall -Wextra \
    $JNI_INC $PLAT_INC -Ideps/current \
    native/corvid_jni.c \
    "deps/current/$LIB" \
    $RPATH \
    -o "build/native/$OUT"
cp "deps/current/$LIB" "build/native/$LIB"
echo "build-native: done (build/native/$OUT + $LIB)"
