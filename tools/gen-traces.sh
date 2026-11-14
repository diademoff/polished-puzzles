#!/bin/sh
# Regenerates testdata/trace/ for the games whose renderer exists.
#
# Usage:
#   tools/gen-traces.sh [ORACLE]
#
# ORACLE defaults to build/oracle/puzzles-oracle. The script builds the
# oracle if it is missing.
#
# A trace change is a reviewed change: it is the drawing contract in
# docs/adr/0003-strict-draw-traces.md. Never regenerate a trace to make
# a red test green.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ORACLE=${1:-"$ROOT/build/oracle/puzzles-oracle"}

if [ ! -x "$ORACLE" ]; then
    "$ROOT/tools/oracle/build.sh"
fi

cd "$ROOT"
exec ./gradlew :parity:genTraces -Poracle="$ORACLE"
