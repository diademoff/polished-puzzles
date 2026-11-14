#!/bin/sh
# Checks that the vendored allowlist is complete and unchanged.
#
# Usage:
#   tools/check-upstream-diff.sh            # hash and completeness checks
#   tools/check-upstream-diff.sh --strict   # plus the recorded patch layer
#   tools/check-upstream-diff.sh --report   # print the difference from upstream
#
# Checks:
#   1. every file in UPSTREAM-FILES.txt has the recorded sha256;
#   2. every tracked file in app/src/main/jni is either listed in
#      UPSTREAM-FILES.txt, project code per tools/vendored-project-files.txt,
#      or one of the two generated manifests.
#
# Check 2 catches a new vendored file and a missing manifest line. It
# needs a git checkout; without one it is skipped with a note.
#
# The strict mode also compares the current set of files that differ
# from upstream, with line counts, against UPSTREAM-PATCHES.txt. The
# patch layer is part of the baseline (docs/DEVIATIONS.md, D-001).
#
# The report mode prints the difference from the pinned upstream
# commit. It does not fail on expected differences.

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JNI="$ROOT/app/src/main/jni"
MANIFEST="$JNI/UPSTREAM-FILES.txt"
PATCHES="$JNI/UPSTREAM-PATCHES.txt"
EXCLUDES="$ROOT/tools/vendored-project-files.txt"
LOCK="$ROOT/UPSTREAM.lock"
REPORT=0
STRICT=0

while [ $# -gt 0 ]; do
    case "$1" in
        --report) REPORT=1; shift ;;
        --strict) STRICT=1; shift ;;
        -h|--help)
            sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "check-upstream-diff.sh: unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[ -f "$MANIFEST" ] || {
    echo "check-upstream-diff.sh: missing $MANIFEST" >&2
    echo "Run tools/gen-vendored-manifest.sh first." >&2
    exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
    SHA256="sha256sum"
elif command -v shasum >/dev/null 2>&1; then
    SHA256="shasum -a 256"
else
    echo "check-upstream-diff.sh: need sha256sum or shasum" >&2
    exit 2
fi

mkdir -p "$ROOT/build"
status=0

# Check 1: content.
while read -r hash path; do
    case "$hash" in
        ''|'#'*) continue ;;
    esac
    if [ ! -f "$JNI/$path" ]; then
        echo "missing: $path" >&2
        status=1
        continue
    fi
    actual=$(cd "$JNI" && $SHA256 "$path" | cut -d' ' -f1)
    if [ "$actual" != "$hash" ]; then
        echo "changed: $path" >&2
        status=1
    fi
done < "$MANIFEST"

if [ "$status" -eq 0 ]; then
    echo "OK: vendored files match the manifest."
else
    echo "FAIL: a vendored file changed without a manifest update." >&2
    echo "If the change is intentional, run tools/gen-vendored-manifest.sh" >&2
    echo "and review the manifest diff." >&2
fi

# Check 2: completeness. Every tracked file in jni must be accounted for.
if git -C "$JNI" rev-parse --git-dir >/dev/null 2>&1; then
    git -C "$JNI" ls-files \
        | grep -vxF -f "$EXCLUDES" \
        | grep -vx 'UPSTREAM-FILES.txt' \
        | grep -vx 'UPSTREAM-PATCHES.txt' \
        | sort > "$ROOT/build/manifest-expected.txt"
    awk '/^[0-9a-f]/ { print $2 }' "$MANIFEST" | sort > "$ROOT/build/manifest-actual.txt"
    if ! diff -u "$ROOT/build/manifest-actual.txt" "$ROOT/build/manifest-expected.txt" > /dev/null; then
        echo "FAIL: the manifest does not cover the tracked files in jni/." >&2
        diff -u "$ROOT/build/manifest-actual.txt" "$ROOT/build/manifest-expected.txt" \
            | sed 's/^/  /' >&2
        echo "Run tools/gen-vendored-manifest.sh and review the diff." >&2
        status=1
    else
        echo "OK: the manifest covers every tracked file."
    fi
else
    echo "SKIP: jni/ is not a git checkout; completeness not checked."
fi

# The pinned upstream commit, read from the upstream section only.
read_commit() {
    awk '
        /^upstream:$/ { section = 1; next }
        /^[a-z]+:$/ { section = 0 }
        section && /^  commit: "/ {
            value = $0
            sub(/^  commit: "/, "", value)
            sub(/".*$/, "", value)
            print value
            exit
        }
    ' "$LOCK"
}

# Check 3: the recorded patch layer.
if [ "$STRICT" -eq 1 ]; then
    commit=$(read_commit)
    if [ -z "$commit" ] || ! git -C "$ROOT" cat-file -e "$commit^{commit}" 2>/dev/null; then
        echo "SKIP: the pinned upstream commit is not in this clone; patch layer not checked."
    else
        [ -f "$PATCHES" ] || {
            echo "FAIL: missing $PATCHES; run tools/gen-upstream-patches.sh" >&2
            status=1
        }

        awk -F '\t' '/^[^#]/ && NF >= 3 { print $1 "\t" $2 "\t" $3 }' "$PATCHES" \
            | sort > "$ROOT/build/patches-expected.txt"

        awk '/^[0-9a-f]/ { print $2 }' "$MANIFEST" | while IFS= read -r path; do
            upstream=$(git -C "$ROOT" rev-parse "$commit:$path" 2>/dev/null) || continue
            current=$(git -C "$ROOT" hash-object -- "$JNI/$path")
            if [ "$upstream" != "$current" ]; then
                stats=$(git -C "$ROOT" diff --numstat "$upstream" "$current" | cut -f1,2)
                printf '%s\t%s\n' "$path" "$stats"
            fi
        done | sort > "$ROOT/build/patches-actual.txt"

        if ! diff -u "$ROOT/build/patches-expected.txt" "$ROOT/build/patches-actual.txt" > /dev/null; then
            echo "FAIL: the Android patch layer changed." >&2
            diff -u "$ROOT/build/patches-expected.txt" "$ROOT/build/patches-actual.txt" \
                | sed 's/^/  /' >&2
            echo "Review the difference. If it is intended, run" >&2
            echo "tools/gen-upstream-patches.sh and update docs/DEVIATIONS.md." >&2
            status=1
        else
            echo "OK: the patch layer matches UPSTREAM-PATCHES.txt."
        fi
    fi
fi

if [ "$REPORT" -eq 0 ]; then
    [ "$status" -eq 0 ] && echo "OK: the vendored allowlist is complete and unchanged."
    exit "$status"
fi

commit=$(read_commit)
if [ -z "$commit" ] || ! git -C "$ROOT" cat-file -e "$commit^{commit}" 2>/dev/null; then
    echo "report: the pinned upstream commit is not in this clone; run: git fetch simon" >&2
    exit "$status"
fi

echo
echo "Files that differ from upstream $commit:"
echo
awk '/^[0-9a-f]/ { print $2 }' "$MANIFEST" | while IFS= read -r path; do
    upstream=$(git -C "$ROOT" rev-parse "$commit:$path" 2>/dev/null) || {
        printf '  not in upstream: %s\n' "$path"
        continue
    }
    current=$(git -C "$ROOT" hash-object -- "$JNI/$path")
    if [ "$upstream" != "$current" ]; then
        stats=$(git -C "$ROOT" diff --numstat "$upstream" "$current" | cut -f1,2)
        printf '  patched: %s (+%s)\n' "$path" "$stats"
    fi
done

echo
echo "The patch layer is recorded in app/src/main/jni/UPSTREAM-PATCHES.txt."
echo "A new entry needs a reason in docs/DEVIATIONS.md."
exit "$status"
