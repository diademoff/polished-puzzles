# ADR 0005: engine infrastructure port

- **Status:** accepted
- **Date:** 2026-11-14

## Context

Phase 3 ports infrastructure, not games. Upstream C is still the
runtime and the oracle: `midend`, every game, `grid`, `loopgen`,
`latin`, the solvers, and the save format all live in
`app/src/main/jni`. Nothing in Kotlin can read a game, generate a
board, or validate a move.

The target layout in `ARCHITECTURE.md` reserves `engine/` for portable
logic. It did not exist. The first Phase 3 slice must establish the
module, the parity method, and the two pieces every later port depends
on: the generator and the save container.

Phase 2's performance exit (D-09) is still open. The owner asked to
start Phase 3 anyway. Phase 3 does not touch the renderer, so the
exception does not change the D-09 evidence.

## Decision

1. **`engine/` is a Kotlin Multiplatform module.** The first target is
   `jvm()` because the parity tests run on the JVM. Code lives in
   `commonMain` and uses no JVM-only API, so a native or Android target
   can be added without a rewrite. The Android app does not depend on
   `engine/` yet; it keeps the `GameEngine` seam. `engine/` has no
   runtime dependency; `commonTest` uses `kotlin-test`, which ships with
   the Kotlin plugin. `parity/` depends on `engine/` so it can compare
   the port with the oracle.

2. **`RandomState` is a bit-exact port of `random.c`.** This includes
   the SHA-1 counter, the 40-byte seed increment, the rejection loop in
   `random_upto`, and the encoded generator state. A different RNG
   changes every seed and every `DESC`. It is not a general-purpose
   random API and must not be replaced by `kotlin.random.Random`.

3. **The oracle exposes the RNG.** `randomBits` and `randomUpto`
   accept either a seed or an encoded state and return the values plus
   the state after them. `RandomParityTest` compares thousands of
   values, every bit width, many limits, and state transfer in both
   directions. A difference is a bug in the port, never in the oracle.

4. **`SgtpSave` owns the save container.** It parses the
   `SAVEFILE`/`VERSION`/record stream into an ordered list, keeps
   repeats (`MOVE`, `SOLVE`, `RESTART`) and unknown keys, and writes
   canonical text. It does not validate game-specific parameters or
   descriptions: that needs game code and belongs to Phase 4. The
   parser rejects a different magic, a different version, truncated
   records, and non-printable values.

5. **The save corpus is committed.** `tools/gen-saves.sh` writes the
   initial state of every preset and three `fifteen` cases that cover
   `MOVE`, an undone position with redo history, and `SOLVE`.
   `SaveParityTest` parses and rewrites every file, then makes the
   oracle load both versions and compare the game id. That is the
   two-direction check: Kotlin reads C, and C reads Kotlin. The test
   also recomputes the preset set, so a missing or extra corpus file
   fails.

6. **`pegs 5x5random` is excluded from the save corpus, not
   "fixed".** Upstream generates the board and then serialises
   long-term params (`5x5cross`) that its own `validate_params`
   rejects on load. This is an upstream defect in a preset, not a
   format difference. The trace path already uses `traceNew` for the
   same reason. `SaveParityTest` pins the exclusion: if upstream makes
   the preset loadable, the test fails and the corpus is regenerated.
   No entry goes into `DEVIATIONS.md`, because we do not change the
   format or the preset.

7. **`sort` and `combi` are ported with the same method.** Upstream
   `arraysort()` is a heapsort, so it is not stable and its tie order
   is observable. `Combinations` enumerates subsets in the upstream
   lexicographic order. `DeterministicHelpersParityTest` compares both
   with the oracle. The remaining order is `dsf` and `tdq`; then `grid`
   and `latin`; then `findloop` and `loopgen`; then the `midend`
   timeline, configuration, and key mapping. Each piece gets an oracle
   operation and a differential test before the next one starts. A
   library with no differential harness is not ported.

## Consequences

Positive:

- the generator is bit-exact and checked against C on every build;
- the save format has a Kotlin reader and writer with a real corpus;
- the sort and combination order is pinned, not assumed stable;
- a future game port has the two foundations it needs: `params + seed`
  and `.sgtp`;
- the oracle protocol grew two operations without breaking existing
  tests.

Negative:

- the module is JVM-only in practice until a second target is added;
- the save container cannot build a game state on its own; it is a
  format, not a `midend`;
- the corpus adds about 1.5 MB of text to the repository;
- `pegs 5x5random` remains a hole in the save matrix until upstream
  changes.

## Alternatives

1. **Port `midend` first.** Rejected: `midend` needs game callbacks,
   and there is no Kotlin game yet. The save container and the RNG are
   independent and testable now.
2. **Generate the corpus in the test instead of committing it.**
   Rejected: a committed corpus is stable evidence, it can include old
   files, and it does not silently change when a preset changes. The
   generator is deterministic and reviewed.
3. **Keep the save parser in `parity/`.** Rejected: it is engine code,
   not a test helper. `parity/` is the harness; `engine/` ships.
4. **Use a JSON or binary save format.** Rejected: the format is a
   public interchange promise and is pinned by `PARITY.md`.
