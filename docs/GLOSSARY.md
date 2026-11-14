# Terminology

This file fixes project terms so documentation, commits, and tests use
the same words.

## Style

- English, simple English, en-US: `color`, `serialize`, `behavior`,
  `initialize`.
- Target about 80% of ASD-STE100: short sentences, one idea per
  sentence, no idioms.
- Do not rename existing upstream identifiers (`colours`, `serialise`,
  `midend_serialise`, `behaviour`). They are part of the contract and
  the vendored code.
- File names, classes, functions, commands, flags, and identifiers are
  not translated or renamed.
- Tables and rules are not softened: `must` stays `must`, `never`
  stays `never`.

## Terms

| Term | Meaning | Note |
|---|---|---|
| upstream | Simon Tatham's C code | do not translate |
| oracle | the upstream C build used as a reference | `puzzles-oracle` |
| parity | equivalence with upstream | do not translate |
| parity suite | the automated parity tests | |
| differential test | compare two implementations on the same input | |
| golden | a stored reference file | for files, use `golden` |
| trace | the stream of draw primitives | |
| primitive | a drawing operation | line, polygon, text, blitter |
| normalization | making traces comparable | en-US |
| state | game state | |
| move | a player move | |
| undo / redo | history navigation | |
| save / savefile | saved game | format is `SAVEFILE` |
| compatibility | read/write across versions | |
| deviation | an intentional difference | |
| deviation register | `DEVIATIONS.md` | |
| pinned commit | the upstream commit in `UPSTREAM.lock` | |
| sync / upstream sync | updating C from upstream | |
| vendored code | upstream files kept verbatim | do not translate |
| generated code | files produced by the build | |
| build | compile and package | |
| release | a published build | |
| accessibility | TalkBack, contrast, targets | |
| touch target | the tappable area | |
| contrast | text/background difference | |
| dark theme | the dark color scheme | |
| night palette | the game board palette for dark mode | |
| game board | the puzzle area | |
| shell / chrome | UI around the board | |
| chooser | the game selection screen | |
| button bar | the on-screen controls | |
| help | offline documentation | |
| settings | app preferences | |
| fuzzing | automated input generation | |
| corpus | the set of test inputs | |
| regression | a bug that returns | |
| fixture | a stored test case | do not translate |
| snapshot | a stored visual state | screenshot or trace |
| test level | `strict`, `semantic`, or `ui` | |
| exit criterion | the condition that closes a phase | |
| roadmap | the phase plan | |
| ADR | architecture decision record | |

## Names that do not change

- `strict`, `semantic`, `ui` — parity levels.
- `traceSchema`, `oracle_protocol`, `savefile_version` — contract
  fields.
- `params`, `seed`, `DESC`, `SAVEFILE`, `.sgtp`.
- `GameEngine`, `ViewCallbacks`, `GameEngineImpl`, `BackendName`.
- `midend`, `grid`, `loopgen`, `latin`, `random.c`.
- `UPSTREAM.lock`, `DEVIATIONS.md`, `PARITY.md`.

## Check before merging a doc

- [ ] All cross-references point to existing files.
- [ ] Commands and paths are unchanged.
- [ ] The "must match / may differ" tables lost no rows.
- [ ] Test levels and normalization rules are worded exactly.
- [ ] `AGENTS.md` stays short (target: about 150 lines).
- [ ] No emoji and no marketing tone.
- [ ] No ASCII art; use Unicode box-drawing characters or Markdown
      tables.
