#!/bin/sh
# Regenerates testdata/golden/ and testdata/manifest.json.
#
# Usage:
#   tools/gen-goldens.sh [ORACLE]
#
# ORACLE defaults to build/oracle/puzzles-oracle. The script builds the
# oracle if it is missing.
#
# A golden change is a reviewed change: it pins the generator output for
# a given params and seed. Never regenerate to make a red test green.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ORACLE=${1:-"$ROOT/build/oracle/puzzles-oracle"}

if [ ! -x "$ORACLE" ]; then
    "$ROOT/tools/oracle/build.sh"
fi

cd "$ROOT"
exec ./gradlew :parity:genGoldens -Poracle="$ORACLE"
