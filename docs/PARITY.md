# Parity: the upstream contract

This is the main document of the project. It answers one question:
**what exactly must match upstream, and what are we allowed to change?**

Upstream C is the oracle. Our implementation (UI, renderer, later the
Kotlin core) is correct only if it passes the parity check at the
declared level.

## 1. What must match

| Area | Must match | May differ |
|---|---|---|
| Game rules | always | — |
| State after a move | always | — |
| `canUndo` / `canRedo` / `completed` | always | — |
| `params + seed -> DESC` | always | — |
| `SAVEFILE` / `.sgtp` | always | — |
| `requestKeys` | always | — |
| `presets`, `status`, `htmlHelpTopic` | always | — |
| Draw primitives: geometry | always | — |
| Draw primitives: clip/blitter order | always | — |
| Game board palette (parity mode) | always | — |
| Game board palette (UI mode) | — | always |
| Typography, spacing, shapes | — | always |
| Animations and transitions | — | always |
| Shell: toolbar, menus, dialogs | — | always |
| Accessibility and content descriptions | — | always |

How to read the table: if a row is not in "must match", any difference
is allowed without a separate approval. If it is, the only path is
`DEVIATIONS.md` plus a test.

## 2. Test levels

Every test must declare a level. A test without a level is not a
parity test.

### `strict`

Compared:

- serialized state;
- public flags and strings;
- draw primitives: geometry, order, colors as roles, text;
- clip/unclip and blitter operations.

Used for the renderer and for games marked as `dual-run`.

### `semantic`

Compared:

- state (normalized);
- `canUndo`, `canRedo`, `completed`;
- `status`, `presets`, `requestKeys`;
- `params + seed -> DESC`.

Drawing is not compared. Used for logic and for intermediate port
stages.

### `ui`

Compared: screenshots or visual snapshots with a tolerance.
Shell differences are allowed by definition. The level catches layout
regressions, not rule changes.

## 3. Trace normalization

Without normalization, a strict test is either always red or
meaninglessly green.

- **Coordinates** are in game coordinates. Origin and scale are
  normalized; device pixels never enter the trace.
- **Colors** are compared as roles (`COL_BACKGROUND`,
  `COL_FOREGROUND`, `COL_HIGHLIGHT`, ...) against the reference
  palette. In UI mode the role maps to the theme.
- **Text** is compared as string + anchor + size class. Glyph metrics,
  kerning, and font files are not compared.
- **Float** tolerance is `1e-3`.
- **Clip/unclip** is strict, including order.
- **Blitter** `alloc/free/save/load` is compared as an operation
  sequence.
- **Excluded from the trace:** `postInvalidateOnAnimation`, lifecycle
  events, frame timings, logs.
- **Schema version** is `traceSchema: 1`. It changes only together with
  the normalizer and the goldens.

## 4. Seed contract

The seed is a public API of the project. It must stay shared with
upstream forever.

- `random.c` is ported **bit-exact**. The RNG must not change.
- `params + seed -> DESC` is a golden test for every game.
- `DESC` does not change for the same seed.
- Any `DESC` change is a breaking deviation with a separate test.
- `savefile_version` does not break.
- Old `.sgtp` files must load; keep a corpus of old versions.
- Games that use time or platform randomness must have a
  deterministic path for tests.

## 5. Save format

`SAVEFILE` is not an internal format. It is a promise to users
(README: exchange saved states between platforms).

Required properties:

- read and write `.sgtp` in both directions with C;
- preserve move history and the position in that history;
- forward and backward compatibility within supported versions;
- no crash on a corrupt file (the fuzzer checks this).

## 6. Game matrix

A game is ported only after every item passes at the declared level.

| Check | Level | What it catches |
|---|---|---|
| `params + seed -> DESC` | semantic | RNG, parameters, generator |
| New game | semantic | initial state |
| Move sequence | semantic | rules, `canUndo/canRedo` |
| Undo/redo invariants | semantic | state history |
| `completed` | semantic | win condition |
| `requestKeys`, `presets`, `status` | semantic | public strings |
| `.sgtp` from C into Kotlin | strict/semantic | deserialization |
| `.sgtp` from Kotlin into C | strict/semantic | serialization |
| Solver (if present) | semantic | correctness and uniqueness |
| Draw primitives | strict | geometry and colors |
| Screenshots | ui | layout and theme |

## 7. Component status

The table is updated in the same PR that changes a status. Only the
owner of the "Authority" column is authoritative; in every other case
C wins.

| Component | Status | Authority |
|---|---|---|
| `midend` | C | C |
| `grid`, `loopgen`, `latin` | C | C |
| `renderer` | new | C traces |
| `fifteen` | not started | C |
| `slant` | not started | C |
| `net` | not started | C |
| `loopy` | not started | C |
| other games | not started | C |

Statuses: `C` — C only; `dual-run` — both implementations, C decides;
`parity` — the Kotlin implementation passes the matrix; `kotlin` — C
was removed after two releases without P1 bugs (see `ROADMAP.md`).

## 8. Goldens

- Goldens are text (JSON/JSON.gz), not PNG.
- Every golden has provenance: game, parameters, seed, upstream
  commit, trace schema.
- `testdata/manifest.json` stores `sha256` and the version.
- Regenerate only with `tools/gen-goldens.sh`, and review the diff.
- Never update a golden to "fix" a red test. Find the cause first,
  then add a `DEVIATIONS.md` entry or fix the code.

## 9. When the oracle disagrees

1. Reproduce with a minimal test.
2. Classify: our bug, upstream bug, or intentional deviation.
3. Our bug — fix it and pin it.
4. Upstream bug — file an issue or a patch; do not edit a vendored
   file silently.
5. Deviation — add a `DEVIATIONS.md` entry and a test that pins it.

The parity suite is always green. A red suite blocks the merge.
