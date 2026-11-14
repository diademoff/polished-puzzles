#!/bin/sh
# Writes UPSTREAM.lock and the README line from git.
#
# Usage:
#   tools/sync-upstream.sh --write-lock
#
# Definitions:
#   upstream.commit     merge base of simon/main and HEAD; the upstream
#                       revision the vendored C is based on
#   integration.commit  HEAD; the commit that contains the sync
#   integration.merge_base  the same merge base
#   integration.synced_at   the commit date of the last upstream merge
#
# Run this after `git fetch simon` and after the integration merge, and
# review the diff before you commit it.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOCK="$ROOT/UPSTREAM.lock"
README="$ROOT/README.md"
TODAY=$(date -u +%Y-%m-%d)

usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
}

[ $# -eq 1 ] || { usage >&2; exit 2; }
[ "$1" = "--write-lock" ] || { usage >&2; exit 2; }

cd "$ROOT"

git rev-parse --verify --quiet simon/main >/dev/null || {
    echo "sync-upstream.sh: simon/main is missing; run: git fetch simon" >&2
    exit 2
}

MERGE_BASE=$(git merge-base simon/main HEAD)
UPSTREAM_TIP=$(git rev-parse simon/main)
# The last merge that touched the vendored tree is the last upstream
# sync. A later feature merge must not move this value.
SYNC_COMMIT=$(git log -1 --merges --format=%H -- app/src/main/jni)
[ -n "$SYNC_COMMIT" ] || SYNC_COMMIT=$(git log -1 --merges --format=%H)
[ -n "$SYNC_COMMIT" ] || SYNC_COMMIT=$(git rev-parse HEAD)
SYNC_DATE=$(git log -1 --format=%cs "$SYNC_COMMIT")

cat > "$LOCK" <<EOF
# UPSTREAM.lock
#
# The single machine-readable source of truth for the upstream
# revision the project is synced with. The README contains only a
# line generated from this file.
#
# Filled by tools/sync-upstream.sh --write-lock on $TODAY.
# Do not edit by hand during normal work.
#
# CI checks:
#   1. the commit exists in the upstream repository;
#   2. merge_base matches the real merge base;
#   3. the README line matches this file;
#   4. the vendored tree still builds the oracle.

upstream:
  repo: "https://git.tartarus.org/simon/puzzles.git"
  remote: "simon"
  branch: "main"
  commit: "$MERGE_BASE"

integration:
  repo: "https://github.com/diademoff/polished-puzzles"
  branch: "main"
  commit: "$SYNC_COMMIT"
  merge_base: "$MERGE_BASE"
  synced_at: "$SYNC_DATE"

contract:
  oracle_protocol: 1
  trace_schema: 1
  savefile_version: 1
  rng: "random.c-bit-exact"

notes: |
  The GitHub mirror github.com/sgtatham/puzzles returns 404. The
  canonical repository, linked from the project home page, is
  https://git.tartarus.org/simon/puzzles.git.

  upstream.commit is the merge base of simon/main and the integration
  branch. It is the upstream revision the vendored C is based on, not
  the current upstream tip ($UPSTREAM_TIP).

  integration.commit is the last merge that touched the vendored
  tree. This repository has no long-lived "upstream" branch, so the
  field names a commit on the integration branch instead of a branch
  tip. See the branch layout note in docs/UPSTREAM-SYNC.md, step 2.

  The vendored tree is not identical to upstream.commit: the port
  carries an Android patch layer inside upstream files. The hashes in
  app/src/main/jni/UPSTREAM-FILES.txt are the reference, and
  tools/check-upstream-diff.sh --report lists the differences.
EOF

SHORT=$(printf '%s' "$MERGE_BASE" | cut -c1-7)
LINE="Upstream revision: [$SHORT](https://git.tartarus.org/?p=simon/puzzles.git;a=commit;h=$MERGE_BASE), synced $SYNC_DATE."

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
if grep -q '^Upstream revision: ' "$README"; then
    awk -v line="$LINE" '
        /^Upstream revision: / {
            if (!done) { print line; done = 1 }
            next
        }
        { print }
    ' "$README" > "$TMP"
else
    awk -v line="$LINE" '
        { print }
        !inserted && /^ported to Android\.$/ { print ""; print line; inserted = 1 }
        END { if (!inserted) print line }
    ' "$README" > "$TMP"
fi
cat "$TMP" > "$README"

echo "wrote $LOCK"
echo "README: $LINE"
