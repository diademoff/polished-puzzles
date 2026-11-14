# ADR 0007: Kotlin game seam and the semantic midend

- **Status:** accepted
- **Date:** 2026-11-14

## Context

Phase 3 ported the portable infrastructure that has a standalone
contract: the RNG, the save container, the deterministic helpers, the
solver utilities, and the drawing trace contract. Phase 4 starts with
`fifteen`, the first game. A game needs two seams that did not exist:

- a game contract that owns parameters, generation, state, moves,
  text, status, and drawing;
- a midend that owns history, undo and redo, solve, and the save file.

Upstream `midend.c` is 3,295 lines. Most of it is the shell: menus,
configuration dialogs, timers, preferences, size negotiation,
animations, and frontend callbacks. None of that is reachable from a
stateless parity request, and none of it changes a saved state. Porting
it before the shell needs it would repeat the unverified-translation
problem that ADR 0006 rejects.

The strict trace contract (ADR 0003) is the opposite case: `fifteen`
draws with a small, bounded set of primitives, and the Kotlin drawing
must be checked against the frozen traces, not just described as
"looks the same".

## Decision

1. **The game seam is `engine/Game.kt`.** It declares parameters,
   presets, the description generator, description validation, state
   creation, the solver hook, move execution, status, text, requested
   keys, geometry, colours, input interpretation, and drawing. A game
   defines nested `Params` and `State` types and implements the
   interface. The seam grows with each ported game; an operation that
   no game needs is not declared, so it cannot become a silent stub.

2. **The midend is a semantic subset.** `engine/Midend.kt` owns the
   parameters, the seed, the description, the state history, and the
   position in that history. It reproduces `midend_serialise` and
   `midend_deserialise` record for record, and it reproduces the
   history rules: a move truncates the redo branch, undo and redo move
   the position, and solve appends a `SOLVE` entry. Menus, timers,
   preferences, animations, and size negotiation stay in C until the
   Android shell uses a Kotlin game.

3. **Drawing goes through `DrawSink`.** The seam has the primitives a
   ported game uses. `Fifteen` records the same operations as the C
   `game_redraw`, and the parity test puts both through
   `TraceNormalizer`. A trace difference is a geometry bug, not a
   rendering preference.

4. **`fifteen` is `dual-run`, not `kotlin`.** Both implementations pass
   the same matrix, and C still decides in the app. The game becomes
   `kotlin` only after the feature flag has been on by default for two
   releases without P1 bugs, as `ROADMAP.md` requires.

5. **The app is wired in a separate step.** Until `GameEngineImpl` can
   choose a Kotlin backend, the app keeps using C for `fifteen`. The
   parity suite is the gate for that step; it is not replaced by it.

## Consequences

Positive:

- the first game fixes the seam that the rest of Phase 4 uses;
- the generator, moves, history, save file, solver, and drawing of
  `fifteen` are all checked against the oracle;
- the midend port stays small enough to review and does not guess at
  shell behavior that has no oracle;
- the same `DrawSink` will serve the next game without a second trace
  format.

Negative:

- `Game.Params` and `Game.State` are marker interfaces, so a game casts
  its own types; Kotlin has no sealed hierarchy across modules that
  would remove the casts;
- the semantic midend is not yet a drop-in replacement for the C
  midend in the app;
- `DrawSink` has only the primitives `fifteen` needs and grows with
  each game.

## Alternatives

1. **Port all of `midend.c` now.** Rejected: most of it is shell code
   with no oracle before the Kotlin shell exists.
2. **Skip the midend and test the game only.** Rejected: the parity
   matrix requires undo, redo, solve history, and `.sgtp` in both
   directions.
3. **Test the Kotlin drawing by comparing pixels.** Rejected: ADR 0003
   makes the primitive trace the contract; screenshots cannot gate CI
   while D-04 is open.
4. **Wire the app immediately.** Rejected: the feature flag and the
   chooser are a separate change, and the matrix must stay green
   without it.
