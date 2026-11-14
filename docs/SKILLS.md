# External skills and multi-agent patterns

This document answers three questions:

1. Which external skills should we not use?
2. Which concrete rules do we apply instead?
3. Which multi-agent patterns fit the project?

Principle: **do not install a skill until it is proven to fit
Android/Compose/C and does not duplicate our documents.** Useful
instructions are moved into `docs/` as plain text.

## 1. When not to use a skill

| Criterion | Why | How to check |
|---|---|---|
| **Wrong platform** | HTML/CSS/JS rules do not work in Compose and Canvas | does it support Android/Compose, or is it web-only |
| **Needs network** | a dev tool must not be required for the build | does it work offline; can telemetry be disabled |
| **Extra toolchain** | Node, Python, Playwright, Chromium, a binary, a hook | what it installs and requires |
| **Auto-activation** | a skill triggered by "UI/UX" can push a landing-page style | does it start on its own; can it be limited |
| **Duplicated truth** | rules in a skill and in `docs/` diverge | does it repeat `DESIGN.md`, `PARITY.md`, `AGENTS.md` |
| **No license** | the text and instructions cannot be copied | is there a LICENSE and clear attribution |
| **Repo artifacts** | hidden folders, reports, caches, screenshots | what it writes; can it stay uncommitted |
| **Non-deterministic** | advice cannot be checked; checks belong in CI | is it a rule or an opinion |
| **Independent changes** | the skill version changes without the code | can it be pinned |
| **Visual style instead of rules** | "pick one of 80 styles" conflicts with parity | does it offer a ready-made aesthetic |

If any answer is bad, **do not install the skill**. Take only the
concrete instructions that pass the filter.

## 2. What to do instead of installing

1. Read the material and extract only what applies to Android, Compose,
   the C engine, and an offline app.
2. Rewrite it as **verifiable instructions** in `docs/`, without
   references to the tool.
3. If a rule can be checked automatically, add it to CI, lint, a test,
   or a hook, not to a skill.
4. Do not add a dependency, directory, hook, or config to the
   repository.
5. If text is copied verbatim, add the license and attribution.
6. Keep one version of the rule. Duplication is forbidden.

## 3. Concrete rules we apply

### 3.1 Engineering discipline

- **Understand first.** State assumptions explicitly. If there are
  several interpretations, do not pick one silently; ask.
- **Minimum code.** No abstractions "for the future", no configurability
  that was not requested, no handling of impossible cases.
- **Surgical changes.** Change only what the task needs. Do not
  reformat nearby code, "improve" comments, or delete other people's
  dead code without a request.
- **Goal over process.** Define a verifiable result: "a test that
  reproduces the bug, then the fix", not "fix the bug".
- **Evidence.** In the report, include the command and its output.
  "It looks right" is not proof.

### 3.2 Design

- Grid 4dp, rhythm 8dp, side margins 16/24dp.
- Minimum target 48×48dp; gap 8dp.
- Contrast: text ≥ 4.5:1; large text and UI ≥ 3:1.
- Font scale up to 200%: text wraps, does not clip, containers grow.
- Light and dark themes are required. Dark is its own palette, not an
  inversion.
- One accent color, neutral surfaces, semantic theme roles. No hex in
  components.
- Icons: Material Symbols, one style per screen. Emoji as icons is
  forbidden.
- Motion 150–300ms, standard easing, support "remove animations".
  Animation explains a change; it does not entertain.
- Information is never conveyed by color alone. Where game rules allow,
  add shape, a label, or a pattern.
- The game board palette is part of parity. The theme does not change
  geometry or recolor the board outside the explicit UI mode.

Anti-slop:

- no purple-blue gradients, glow, neon, glassmorphism;
- no emoji icons or stock illustrations;
- no ALL CAPS or marketing tone;
- no uneven spacing or random radii;
- no cards inside cards and pill buttons everywhere;
- no decorative animations that cannot be disabled;
- no text on a colored background with poor contrast.

Design pass order:

1. spacing and grid;
2. color and contrast;
3. icons;
4. text and tone;
5. typography;
6. layout and adaptivity;
7. details and states;
8. motion.

Review severity:

- **P0** — blocks: an unreachable control, clipped text, a screen that
  cannot be closed.
- **P1** — major defect: poor contrast, broken RTL, lost state.
- **P2** — noticeable but not blocking.
- **P3** — polish.

### 3.3 Communication

- Chat replies: short, no filler, but do not lose technical meaning.
- Documents, comments, and commits: full sentences. Telegraphic style
  in artifacts is forbidden.
- Commit: `type(scope): imperative summary`; the body explains why.

### 3.4 Process

- `AGENTS.md` stays short; context lives in `docs/`.
- Hard rules live in CI or hooks, not only in text.
- Independent review happens in a fresh context and focuses on
  correctness, not style.
- After two failed explanations, rewrite the task instead of arguing
  with the model.

## 4. Checklist for a new skill

Before installing:

1. **Platform.** Does it understand Android/Compose, or is it web-only?
2. **Offline.** Does it work without network? Can telemetry be turned
   off?
3. **Dependencies.** Does it bring Node/Python/Playwright/a binary/a
   hook? Is there an ADR?
4. **Auto-activation.** Can it trigger where it should not?
5. **Source of truth.** Does it duplicate `DESIGN.md`/`PARITY.md`?
6. **License.** Can the text be copied? Is attribution required?
7. **Artifacts.** What does it write to the repo? Can it stay
   uncommitted?
8. **Determinism.** Is it advice or a check? Checks belong in CI.
9. **Version.** Can it be pinned and updated on purpose?

If any answer is bad, do not install it. Take the instructions.

## 5. Automation instead of skills

Hard rules must not depend on whether an agent remembers a skill. They
belong in deterministic checks:

- vendored file protection — CI `upstream-diff`;
- parity — tests;
- formatting and lint — Gradle/CI;
- accessibility — checks and screenshots;
- no new permissions — manifest diff;
- no network — no INTERNET permission and dependency review.

Skills and `AGENTS.md` are for knowledge and priorities, not for what
can be checked automatically.

## 6. Multi-agent patterns

Based on public material about agentic systems. The main conclusion:
multi-agent wins on **broad, independent, verifiable** tasks. On
tightly coupled tasks it loses to a single agent because of
coordination, cost, and context drift.

### 6.1 What fits

| Pattern | Fits? | Where to use |
|---|---|---|
| Evaluator-optimizer | yes, strongly | oracle/parity as evaluator, independent reviewer |
| Sectioning | yes, read-only | upstream archaeology, review, code research |
| Prompt chaining with gates | yes | sync -> oracle -> parity -> goldens -> docs |
| Routing | conditional | route by path/task type, not an LLM router |
| Orchestrator-workers | conditional | large mechanical migrations, not game logic |
| Voting | limited | ambiguous triage/design, not objective checks |
| Autonomous swarm | no | too expensive and risky for one maintainer |

### 6.2 Evaluator-optimizer

The main pattern. The evaluator is a **deterministic check**, not a
second LLM:

```
implementer -> oracle/test -> fail? -> fix -> repeat
                     |
                     +-> pass -> independent reviewer -> merge
```

Project evaluators:

- parity against the C oracle;
- `params + seed -> DESC`;
- `.sgtp` cross-compatibility;
- solver agreement;
- strict draw trace.

The LLM reviewer is the second loop. It checks what a test cannot:
goal fit, architectural consequences, and missed edge cases. The
reviewer gets a fresh context and the focus "correctness, skip style".

### 6.3 Sectioning: parallel read-only research

The best fit is **upstream archaeology**. It is a breadth-first task:
many games, issues, commits, `devel.but`, `auxiliary/*-test.c`, and
fuzz corpora.

```
Lead agent
├─ Worker A: git log/issues per game -> seed, params, moves
├─ Worker B: devel.but + comments + asserts -> invariants
├─ Worker C: fuzzpuzz + icons/*.sav -> suspicious inputs
└─ Lead: dedupe, classify, testdata/regressions/<game>/*.json
```

Why it works: the subtasks are independent, all are read-only, and the
result is data, not code. There are no conflicts.

The same approach works for:

- code research through `codebase-memory` by subsystem;
- release-candidate review by axis: parity/correctness,
  a11y/design, security/privacy;
- analyzing a large diff or test log so the noise stays out of the
  main context.

Do not parallelize: edits in one worktree, a `midend` port and game
ports at the same time, or golden updates from several processes.

### 6.4 Prompt chaining with gates

Our process is sequential:

```
sync upstream -> build oracle -> parity -> goldens -> docs -> release
```

Every step is a gate. This is cheaper and more reliable than an
orchestrator: no races, and everything is reproducible in CI. This is
a workflow, not an agent, and for deterministic processes the workflow
is the right choice.

### 6.5 Routing

Routing is useful, but implement it with repository structure and
rules, not an LLM classifier:

- `app/src/main/jni` -> vendored rules, read-only;
- `app/src/main/kotlin` -> Compose/M3, a11y;
- `docs/` -> contracts and terminology;
- `testdata/` -> provenance and review.

In OpenCode this can be nested `AGENTS.md` files or path-scoped rules.
Then routing is deterministic instead of depending on model
classification.

### 6.6 Orchestrator-workers

Justified for large mechanical migrations: for example, moving
`app/src/main/jni` to `jni/upstream` + `jni/android`, updating
`GenerateBackendsTask`, CMake, and paths.

Conditions:

- a separate worktree per worker;
- disjoint file sets;
- the lead checks the build and parity;
- no logic changes in the same pass.

It does not fit game ports: they share `midend`, `grid`, `loopgen`,
and `random`. Parallel ports of `net` and `loopy` would hit the same
unported code.

### 6.7 Voting

Useful only where there is no objective criterion:

- "is this an upstream bug or ours?";
- "which of two chooser variants is better?";
- "should this go into DEVIATIONS?".

Voting is not needed for parity: the oracle gives an objective answer.
For design, 2–3 variants in parallel worktrees can be compared by
screenshots and a11y, but that is expensive and belongs to Phase 1.

### 6.8 Autonomous swarm

Autonomous agents with write access, golden updates, and push rights
are a high risk under the seed/save/parity contract. If used at all:

- in a separate worktree;
- with a budget and a turn limit;
- read-only access to vendored files;
- human approval before merge;
- a deterministic gate after every step.

For the current scope this is overengineering.

### 6.9 Lessons from multi-agent research

1. **Breadth-first, not deep.** Upstream archaeology fits; game ports
   do not.
2. **Describe the subtask fully.** A subagent does not see the main
   conversation: it needs the goal, context, files, constraints,
   output format, and boundaries.
3. **It is expensive.** Token use grows several times. Run it only
   where the value is higher than the coordination cost.
4. **Evaluate the end state.** For us that is green parity, a build,
   and tests, not the agent's report.

### 6.10 Subtask handoff contract

```text
Goal:        what to do
Context:     game/module, docs links, upstream commit
Files:       what may be read or changed
Constraints: vendored/seed/save/DEVIATIONS, no network
Output:      format (JSON/table/patch), concise
Evidence:    commands and output
Stop:        when to stop and ask
```

Without this, a subagent starts to invent the task.

### 6.11 Minimum set

1. One main agent writes code.
2. Read-only subagents only for research, archaeology, and review.
3. The oracle and tests are the evaluator; an LLM does not replace a
   pass/fail check.
4. One reviewer per diff, in a fresh context.
5. Parallel worktrees only for independent tasks after Phase 3.
6. A human checkpoint before goldens, vendored patches, and release.
