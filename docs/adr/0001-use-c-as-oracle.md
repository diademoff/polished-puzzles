# ADR 0001: upstream C as the oracle

- **Status:** accepted
- **Date:** 2026-11-14

## Context

We have a mature C codebase: Simon Tatham's Puzzle Collection. It
contains the rules, generators, solvers, RNG, and save format. It runs
on several platforms and contains two decades of fixed bugs. The
Android shell is outdated, and a full Kotlin port of every game is a
multi-year project with a high regression risk.

We need a modernization path that:

- shows visible results quickly;
- does not repeat bugs that are already fixed;
- allows porting logic gradually;
- keeps a way back.

## Decision

Use upstream C as an **executable specification and oracle**.

1. Build a host binary, `puzzles-oracle`, from the same C code.
2. Define the contract: state, seed, `DESC`, `.sgtp`, and the draw
   primitive stream.
3. Every new implementation passes differential tests against the
   oracle at a declared level (`strict`, `semantic`, `ui`).
4. C stays the source of truth until a component passes the matrix and
   two releases without P1 bugs.
5. Deviations are recorded in `DEVIATIONS.md` and pinned by a test.

## Consequences

Positive:

- the modern UI can be built immediately without touching games;
- every port step is checked automatically;
- historical bugs become regression fixtures;
- the work can stop at any stage and leave a hybrid app.

Negative:

- infrastructure is required: oracle, normalizer, corpus, CI;
- strict comparison needs discipline (coordinates, colors, text);
- two implementations must be maintained in dual-run;
- there is a temptation to "adjust" a golden instead of fixing a bug.
  The rules forbid this.

## Alternatives

1. **Rewrite everything at once.** Rejected: years of work, no green
   baseline, high risk.
2. **Keep C forever and change only the shell.** Valid as an end state,
   but it gives no path for porting logic.
3. **Compare pixels.** Rejected: fonts, anti-aliasing, and Skia
   versions make the test unstable. We compare primitives and state.
4. **Sync from documentation only.** Rejected: documentation does not
   describe every edge case; the code does.
