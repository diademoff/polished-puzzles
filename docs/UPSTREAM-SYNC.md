# Upstream sync

This document describes how to update the C code from upstream without
losing control over parity and without mixing other people's changes
with ours.

## 1. Terms

- **Simon upstream** — Simon Tatham's repository, remote `simon`,
  branch `main`. The canonical source of C.
- **Integration branch** — the `upstream` branch in our repository.
  A mirror of Simon upstream, without Android changes.
- **`main`** — our branch: Android frontend, UI, docs.
- **Merge base** — the commit used by `idForSimon()` in
  `app/build.gradle.kts`.

If the `simon` remote is not configured:

```bash
git remote add simon https://git.tartarus.org/simon/puzzles.git
git fetch simon
```

The GitHub mirror `github.com/sgtatham/puzzles` no longer exists. The
canonical repository is `git.tartarus.org`; the project home page links
to it.

The exact URL and commit are recorded in `UPSTREAM.lock`.

## 2. UPSTREAM.lock

The only source of truth. The README contains only a link generated
from it.

Fields:

```yaml
upstream:
  repo: "https://git.tartarus.org/simon/puzzles.git"
  remote: "simon"
  branch: "main"
  commit: "<sha>"
integration:
  repo: "https://github.com/chrisboyle/sgtpuzzles"
  branch: "upstream"
  commit: "<sha>"
  merge_base: "<sha>"
  synced_at: "YYYY-MM-DD"
contract:
  oracle_protocol: 1
  trace_schema: 1
  savefile_version: 1
  rng: "random.c-bit-exact"
```

CI checks:

1. `commit` exists in upstream;
2. `merge_base` matches the real merge base;
3. the README line matches the lock;
4. the vendored tree still builds the oracle.

Check 4 is about this tree, not about a clean upstream checkout: the
oracle frontend is written against the vendored `drawing_api`, which
already differs from the pinned upstream revision.

## 3. Procedure

1. **Prepare**
   ```bash
   git fetch simon
   git fetch origin
   git switch main
   git status   # must be clean
   ```

2. **Merge upstream into main**
   ```bash
   git switch main
   git merge simon/main
   ```
   This repository has no long-lived `upstream` branch. If one is
   added later, update it with `--ff-only` first and merge it instead,
   so the lock keeps naming a single sync commit.

3. **Resolve conflicts**
   - Conflicts in vendored files: do not "fix" them by hand. Take the
     upstream version, then add a patch in a separate commit only if it
     is truly needed.
   - Conflicts in `android.c`, `android.h`, `android-gen.c`,
     `cmake/platforms/android.cmake`, Kotlin, and res are ours.
   - Conflicts in `CMakeLists.txt`: ours only in the Android parts;
     take the rest from upstream.

5. **Update the lock**
   ```bash
   tools/sync-upstream.sh --write-lock
   ```
   The script writes `upstream.commit`, `integration.commit`,
   `merge_base`, `synced_at`, and generates the README line.

6. **Check the game list**
   - `GenerateBackendsTask` parses `CMakeLists.txt`.
   - A new game means new resources, icons, strings, and night colors.
   - The build must fail if something is missing, not silently skip the
     game.

7. **Build the oracle from two revisions**
   ```bash
   tools/oracle/build.sh
   mkdir -p build/upstream
   git archive simon/main | tar -x -C build/upstream
   tools/oracle/build.sh --src build/upstream --build-dir build/upstream-oracle
   ```
   The second binary shows what changed in upstream behavior during the
   sync. The oracle frontend tracks the vendored drawing API, so a
   frontend change is sometimes needed before the upstream build works;
   that is part of the sync, not a workaround.

8. **Run parity**
   ```bash
   ./gradlew :parity:test -Poracle=build/oracle/puzzles-oracle
   ```
   Classify every difference using `PARITY.md`.

9. **Update goldens only on purpose**
   - If upstream changed behavior, the diffs show it.
   - Updating goldens is a separate commit with an explanation.
   - If the change affects seed/DESC, it is a breaking deviation and
     needs a separate analysis.

10. **Update the docs**
    - README line (generated).
    - `UPSTREAM.lock`.
    - `DEVIATIONS.md` if a patch or behavior changed.
    - `PARITY.md` if a component status changed.

11. **Run the full CI** before merging.

## 4. Conflict policy

| File | Decision |
|---|---|
| `jni/*.c` (vendored) | upstream plus the Android patch layer recorded as D-001 in `DEVIATIONS.md`; take upstream changes, re-apply the layer, and do not add a new edit |
| `jni/android.c`, `android.h` | ours; adapt to the new API |
| `jni/executable/android-gen.c` | ours |
| `jni/cmake/platforms/android.cmake` | ours |
| `jni/CMakeLists.txt` | upstream + our Android insertions |
| `app/src/main/kotlin` | ours |
| `app/src/main/res` | ours |
| `app/src/main/assets` | generated from `assets-sources` |

If upstream changes a signature or behavior that `android.c` uses,
adapt `android.c`, not the upstream file.

## 5. Upstream patches

Sometimes an Android-specific fix is impossible without a patch.

Rules:

1. Try to send the fix upstream first.
2. If a temporary patch is needed, it lives in `patches/` with a number
   and a test.
3. The patch must be in `DEVIATIONS.md`.
4. On the next sync, review the patch. If upstream fixed it, remove it.
5. The patch must not change game rules or the seed.

## 6. Two revisions in CI

The `oracle-current` job builds the oracle from current `simon/main`
and runs parity. It does not block a merge, but it opens an issue if
behavior changed. This tells us about upstream breaking changes before
they reach a release.

## 7. Schedule

- Scheduled sync: once a month.
- Extra sync: before a release and on a critical upstream bug.
- After a sync: full parity and lock update.
- Do not mix a sync with features in one PR.

## 8. Checklist

- [ ] `git status` is clean before starting.
- [ ] `upstream` is updated with fast-forward.
- [ ] Conflicts are resolved by the table.
- [ ] `UPSTREAM.lock` is updated.
- [ ] The README line matches.
- [ ] The game list and resources are checked.
- [ ] The oracle is built from two revisions.
- [ ] Parity is green.
- [ ] Goldens are updated only with an explanation.
- [ ] `DEVIATIONS.md` is current.
- [ ] The full CI is green.
