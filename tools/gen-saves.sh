#!/bin/sh
# Regenerates testdata/saves/, the cross-compatibility save corpus.
#
# Usage:
#   tools/gen-saves.sh [ORACLE]
#
# ORACLE defaults to build/oracle/puzzles-oracle. The script builds the
# oracle if it is missing.
#
# A save change is a reviewed change: it pins the upstream save format
# for a given game, params, and seed. Never regenerate the corpus to
# make a red test green.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ORACLE=${1:-"$ROOT/build/oracle/puzzles-oracle"}

if [ ! -x "$ORACLE" ]; then
    "$ROOT/tools/oracle/build.sh"
fi

cd "$ROOT"
exec ./gradlew :parity:genSaves -Poracle="$ORACLE"
