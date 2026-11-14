#!/bin/sh
# Builds the host oracle from the vendored C tree.
#
# Usage:
#   tools/oracle/build.sh [--src DIR] [--build-dir DIR] [--jobs N] [--with-fuzz]
#
#   --src DIR        source tree; default app/src/main/jni
#   --build-dir DIR  output directory; default build/oracle
#   --with-fuzz      also build the fuzzpuzz stand-alone frontend
#
# Output:
#   build/oracle/puzzles-oracle
#
# The game list and the core source list are read from
# app/src/main/jni/CMakeLists.txt on every run. There is no second copy
# to keep in sync.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$ROOT/app/src/main/jni"
BUILD="$ROOT/build/oracle"
JOBS=0
WITH_FUZZ=OFF

while [ $# -gt 0 ]; do
    case "$1" in
        --src)
            [ $# -ge 2 ] || { echo "build.sh: --src needs a directory" >&2; exit 2; }
            SRC=$2
            shift 2
            ;;
        --build-dir)
            [ $# -ge 2 ] || { echo "build.sh: --build-dir needs a directory" >&2; exit 2; }
            BUILD=$2
            shift 2
            ;;
        --jobs)
            [ $# -ge 2 ] || { echo "build.sh: --jobs needs a number" >&2; exit 2; }
            JOBS=$2
            shift 2
            ;;
        --with-fuzz)
            WITH_FUZZ=ON
            shift
            ;;
        -h|--help)
            sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "build.sh: unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

# cmake must see absolute paths: the binary directory differs from the
# working directory.
case "$SRC" in
    /*) ;;
    *) SRC=$(CDPATH= cd -- "$SRC" && pwd) ;;
esac
mkdir -p "$BUILD"
BUILD=$(CDPATH= cd -- "$BUILD" && pwd)

if [ ! -f "$SRC/CMakeLists.txt" ]; then
    echo "build.sh: no CMakeLists.txt in $SRC" >&2
    exit 2
fi

for tool in cmake perl; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build.sh: $tool is required" >&2
        exit 2
    }
done

# The official game list. The rule is identical to the one in
# GenerateBackendsTask, so the oracle and the app agree on the set of
# games.
GAMES=$(perl -0777 -ne \
    'while (/puzzle\(\s*(\w+)\s+DISPLAYNAME\s+"[^"]+"/g) { print "$1\n" }' \
    "$SRC/CMakeLists.txt")
[ -n "$GAMES" ] || { echo "build.sh: no games found in CMakeLists.txt" >&2; exit 2; }

# The core source list is the 'core_obj' object library. Platform
# sources such as android.c are deliberately not part of it.
CORE=$(perl -0777 -ne \
    'if (/add_library\(core_obj OBJECT(.*?)\)/s) { print $1 }' \
    "$SRC/CMakeLists.txt" | tr ' \t' '\n\n' | grep -E '^[A-Za-z0-9_-]+\.c$' | sort -u)
[ -n "$CORE" ] || { echo "build.sh: no core sources found in CMakeLists.txt" >&2; exit 2; }

# generated-games.h is what list.c includes. Upstream writes it with
# write_generated_games_header(); the format is one GAME(name) per line.
mkdir -p "$BUILD/include"
: > "$BUILD/include/generated-games.h"
printf '%s\n' "$GAMES" | sort | while IFS= read -r game; do
    printf 'GAME(%s)\n' "$game" >> "$BUILD/include/generated-games.h"
done

GAMES_CMAKE=$(printf '%s;' $GAMES | sed 's/;$//')
CORE_CMAKE=$(printf '%s;' $CORE | sed 's/;$//')

cmake -S "$ROOT/tools/oracle" -B "$BUILD" \
    -DORACLE_SRC="$SRC" \
    -DORACLE_GAMES="$GAMES_CMAKE" \
    -DORACLE_CORE="$CORE_CMAKE" \
    -DORACLE_BUILD_FUZZ="$WITH_FUZZ" \
    -DCMAKE_BUILD_TYPE=Release

PARALLEL=
if [ "$JOBS" -gt 0 ]; then
    PARALLEL="--parallel $JOBS"
fi
# shellcheck disable=SC2086
cmake --build "$BUILD" --target puzzles-oracle $PARALLEL
if [ "$WITH_FUZZ" = ON ]; then
    # shellcheck disable=SC2086
    cmake --build "$BUILD" --target fuzzpuzz $PARALLEL
fi
echo "built: $BUILD/puzzles-oracle"
