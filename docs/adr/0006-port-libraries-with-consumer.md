# ADR 0006: Port libraries with their first consumer

- **Status:** accepted
- **Date:** 2026-11-14

## Context

The Phase 3 task list names every shared library: `dsf`, `tree234`,
`findloop`, `loopgen`, `grid`, `latin`, `sort`, `combi`, `tdq`,
`matching`, and `random`. It also names the `midend`.

Some of these have a standalone contract. The RNG, the save container,
the sort order, the combination order, a disjoint-set forest, a to-do
queue, bridge finding, bipartite matching, and the 2-3-4 tree API can
all be driven directly from a test and compared with the oracle.

Others do not. `grid` builds a family of twenty board geometries,
`latin` is a solver framework driven by per-game callbacks, `loopgen`
generates a loop through a grid, and `midend` is the glue around a
game's vtable. Their outputs are only meaningful through a game: a
grid is validated by a board, a Latin solver by a puzzle, a loop by a
generator, and the midend by a saved state and a key sequence. Porting
them before a game exists means writing thousands of lines with no
semantic oracle, only the structural claim that the translation looks
right. That is exactly the kind of unverified change the project
forbids.

The current C implementations are not unverified: the 288 strict draw
traces, the 288 `params + seed -> DESC` goldens, and the save corpus
already pin their behavior through the oracle. The risk is in the port,
not in the reference.

## Decision

1. **Phase 3 closes on its exit criteria, not on the task list.**
   The criteria are: the parity corpus is green, `.sgtp` reads and
   writes in both directions, `seed -> DESC` matches for every game,
   and iteration order and RNG are deterministic. All four have
   evidence in `ROADMAP.md`.

2. **The following libraries are ported and pinned by differential
   tests:** `random`, the `SAVEFILE` container, `sort`, `combi`, `dsf`,
   `tdq`, `findloop`, `matching`, and `tree234`. Each has an oracle
   operation and a test that compares state or order, not just
   plausibility.

3. **The remaining libraries move to Phase 4 and are ported with their
   first consumer:** `grid` (slant or mines), `latin` (keen, towers, or
   unequal), `loopgen` (net or loopy), and `midend` (fifteen). The
   first game that needs a library is the point where the library has
   an end-to-end check: the game's dual-run matrix compares the Kotlin
   library through the game with the C oracle.

4. **A library port does not change the game's status.** A game stays
   `C` until its Kotlin path passes the full matrix in `PARITY.md`,
   including `.sgtp`, move sequences, undo/redo, `completed`, and the
   strict trace. Porting `grid` with slant does not make loopy a Kotlin
   game.

5. **Every deferred port keeps the same method.** An oracle operation
   drives the C code, a differential test compares the Kotlin result,
   and the game's goldens and traces stay unchanged. If the oracle
   disagrees, the default is that the Kotlin port is wrong.

## Consequences

Positive:

- Phase 3 ends with evidence, not with unverified translation volume;
- each deferred library is tested in the context that gives it meaning;
- the oracle already protects the C behavior in the meantime;
- the next game port (fifteen) can start without waiting for `grid`.

Negative:

- the `engine/` module cannot build a board until Phase 4 starts;
- the task list and the phase boundary differ, so the roadmap must say
  so explicitly;
- a library port and a game port now share a change, which needs a
  careful review and separate commits where possible.

## Alternatives

1. **Port all libraries now.** Rejected: no semantic oracle for
   `grid`, `latin`, `loopgen`, or `midend` before a game exists, and the
   volume makes a silent translation error likely.
2. **Keep the libraries in C forever.** Rejected: a Kotlin game needs
   them, and the project goal is a portable core.
3. **Port one grid type and one Latin solver now.** Rejected: a partial
   library is a new API with the same validation problem, and the first
   consumer still needs the rest.
