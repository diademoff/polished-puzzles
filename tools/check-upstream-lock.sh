#!/bin/sh
# Checks UPSTREAM.lock and the README line.
#
# Usage:
#   tools/check-upstream-lock.sh
#
# Offline checks:
#   - the lock has the required keys and a 40-hex commit;
#   - merge_base equals commit;
#   - the README line names the same commit and date.
#
# With a `simon` remote:
#   - upstream.commit is the real merge base of simon/main and HEAD;
#   - upstream.commit exists in simon.
#
# Without the remote the last two checks are skipped and reported, so
# the script still runs in a plain checkout.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOCK="$ROOT/UPSTREAM.lock"
README="$ROOT/README.md"

fail=0

note() { printf '%s\n' "$*"; }
bad() { printf 'FAIL: %s\n' "$*" >&2; fail=1; }

read_field() {
    # read_field <section> <key>
    awk -v section="$1" -v key="$2" '
        /^[a-z]+:$/ { current = $0; sub(/:$/, "", current) }
        current == section && $1 == key ":" {
            value = $0
            sub(/^[^:]*:[ \t]*/, "", value)
            gsub(/"/, "", value)
            print value
            exit
        }
    ' "$LOCK"
}

COMMIT=$(read_field upstream commit)
INTEGRATION_COMMIT=$(read_field integration commit)
MERGE_BASE=$(read_field integration merge_base)
SYNCED_AT=$(read_field integration synced_at)

[ -n "$COMMIT" ] || bad "upstream.commit is missing"
[ -n "$INTEGRATION_COMMIT" ] || bad "integration.commit is missing"
[ -n "$MERGE_BASE" ] || bad "integration.merge_base is missing"
[ -n "$SYNCED_AT" ] || bad "integration.synced_at is missing"

case "$COMMIT" in
    UNSET|"") bad "upstream.commit is not filled" ;;
    *[!0-9a-f]*) bad "upstream.commit is not a lowercase hex sha" ;;
    *) [ ${#COMMIT} -eq 40 ] || bad "upstream.commit is not 40 characters" ;;
esac

[ "$COMMIT" = "$MERGE_BASE" ] || bad "upstream.commit and integration.merge_base differ"

# The sync commit must be in this history, and synced_at must be its
# date. Otherwise the provenance in the lock is not reproducible.
if git -C "$ROOT" cat-file -e "$INTEGRATION_COMMIT^{commit}" 2>/dev/null; then
    git -C "$ROOT" merge-base --is-ancestor "$INTEGRATION_COMMIT" HEAD ||
        bad "integration.commit is not an ancestor of HEAD"
    ACTUAL_DATE=$(git -C "$ROOT" log -1 --format=%cs "$INTEGRATION_COMMIT")
    [ "$ACTUAL_DATE" = "$SYNCED_AT" ] ||
        bad "synced_at is $SYNCED_AT, commit date is $ACTUAL_DATE"
else
    bad "integration.commit $INTEGRATION_COMMIT is not in this clone"
fi

SHORT=$(printf '%s' "$COMMIT" | cut -c1-7)
if grep -q "^Upstream revision: \[$SHORT\].*$COMMIT.*synced $SYNCED_AT\." "$README"; then
    note "OK: README line matches the lock."
else
    bad "README line is missing or stale; run tools/sync-upstream.sh --write-lock"
fi

if git -C "$ROOT" rev-parse --verify --quiet simon/main >/dev/null; then
    REAL_BASE=$(git -C "$ROOT" merge-base simon/main HEAD)
    [ "$REAL_BASE" = "$COMMIT" ] ||
        bad "real merge base is $REAL_BASE, lock says $COMMIT"
    git -C "$ROOT" cat-file -e "$COMMIT^{commit}" 2>/dev/null ||
        bad "commit $COMMIT is not present in simon"
    note "OK: upstream commit and merge base verified against simon."
else
    note "SKIP: no simon remote; upstream identity not verified."
fi

[ "$fail" -eq 0 ] || exit 1
note "OK: UPSTREAM.lock is consistent."
