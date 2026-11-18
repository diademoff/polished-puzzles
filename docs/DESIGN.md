# Design

This document describes how the app looks and feels. It does not
replace the official guidelines. It records which guidelines we apply
and how we resolve conflicts.

## 1. Principles

1. **The game comes first.** The board gets most of the space; the
   shell gets as little as possible.
2. **A quiet shell.** No decorative effects that compete with the
   puzzle.
3. **Clarity over decoration.** Clear hierarchy, predictable controls,
   honest states.
4. **Accessibility is not optional.** One-handed use, large targets,
   TalkBack, dark theme.
5. **No AI slop.** There is an explicit anti-pattern list below.

## 2. Sources

Required:

- Material 3: https://m3.material.io/
- Material 3 in Compose:
  https://developer.android.com/develop/ui/compose/designsystems/material3
- Android accessibility:
  https://developer.android.com/guide/topics/ui/accessibility
- Compose accessibility:
  https://developer.android.com/develop/ui/compose/accessibility
- Adaptive layouts:
  https://developer.android.com/develop/ui/compose/layouts/adaptive
- Edge-to-edge:
  https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Predictive back:
  https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture

Secondary material is allowed but is not a source of truth. The
criteria are in `docs/SKILLS.md`. Any useful rule is moved here as a
concrete instruction, without installing a tool.

## 3. Color

- Use **M3 semantic roles**: `primary`, `onPrimary`, `surface`,
  `surfaceVariant`, `outline`, `error`, and so on.
- No hex values in components. Theme tokens only.
- Light and dark themes are required. Dark is not an inversion; it is
  its own palette.
- Dynamic color (API 31+) is the **Use system colors** setting: on by
  default on Android 12 and later, absent below it. The shell roles then
  come from the wallpaper palette; the game board and the key bar do
  not, because their palette is part of parity and of the game's input
  contract. The setting is a theme, so a screen reads it when it is
  built; the settings screen rebuilds itself so the change shows at once,
  and a running game is never restarted.
- One accent color. Everything else is a neutral surface.
- Contrast: text ≥ 4.5:1, large text and UI ≥ 3:1.
- Information is never conveyed by color alone. Where game rules allow,
  add shape, a label, or a pattern.
- The game board palette comes from `BackendName`/`game_props.xml` and
  is part of parity. The shell theme does not recolor it, except in the
  explicit UI mode recorded in `DEVIATIONS.md`.

## 4. Typography

- System font (Roboto / system sans). No decorative fonts and no font
  downloads from the network.
- M3 scale: display / headline / title / body / label.
- No more than two weights per screen.
- Sentence case. No ALL CAPS except for short labels where it is
  traditional.
- Support font scale up to 200%: text must not clip or overlap, and
  containers must grow.
- Long lines wrap. No `maxLines=1` for meaningful text without an
  accessible full-value path.
- Text on the game board is drawn by the C engine. Its sizes and
  classes are checked by parity tests, not by the theme.

## 5. Grid and spacing

- Base unit: 4dp. Rhythm: 8dp.
- Screen side margins: 16dp (phone), 24dp (tablet).
- Minimum target: 48×48dp; gap between targets: 8dp.
- Lists: a two-line item is about 72dp, a one-line item about 56dp.
- No eyeballed spacing. If a value is not in the tokens, add a token
  instead of a magic number.

## 6. Shape and depth

- Radii come from the M3 scale (for example 12/16/28dp). Do not round
  everything.
- Shadows are minimal. Hierarchy comes from surface tone and borders,
  not from stacked cards.
- Do not nest cards without a reason.
- Dividers are thin. A divider that marks a section boundary uses
  `outline`, the lightest neutral role that reaches the 3:1 UI minimum
  (4.3:1 light, 5.8:1 dark on the surface). `outlineVariant` is only
  1.6:1 and 2.0:1 there, so it marks decoration inside a section, not a
  boundary.

## 7. Motion

- Duration 150–300ms, standard M3 easing.
- Animation explains a state change; it does not entertain.
- Respect the system "remove animations" setting
  (`ANIMATOR_DURATION_SCALE` / accessibility settings).
- Game board animations come from state diffs, not from game logic.
  They do not affect `canUndo`, `completed`, or the seed.
- No infinite decorative loops.

## 8. Icons and images

- Material Symbols, 24dp, one style (outlined or filled) per screen.
- No emoji instead of icons.
- An icon without a label must have a `contentDescription`; a
  decorative icon uses `null`.
- Game icons are the existing assets. Do not replace them with random
  illustrations.
- No stock "hero" images or gradient blobs.

## 9. Components

### Toolbar

- `MaterialToolbar`; the title is the game name, not a slogan.
- Actions: undo, redo, menu. Everything else goes into overflow.
- In fullscreen the toolbar hides, but the action stays reachable.

### Chooser

- Grid or list: icon, name, one line of description.
- "Starred" and "All" sections; the current game is highlighted, but
  it does not shout.
- Search is optional. If added, it needs a proper keyboard and a clear
  action.
- Density matters more than air: the user came to pick a game.

### Game screen

- The board gets maximum area, status is one line, controls are at the
  bottom.
- No floating buttons over the board.
- The key bar is one Material 3 surface with tonal keys: a 48dp cell, a
  visible container, a ripple, and a dimmer container and glyph for a
  disabled key. The cell grid is the game's input contract, so its size
  and its arrangement do not follow the 8dp spacing step.
- Menus and settings do not cover the board without a reason.
- Gestures do not conflict with scrolling or system back.

### Dialogs

- M3 basic dialog, one primary action.
- Configuration fields are M3 text fields and booleans are M3
  checkboxes; the field's own floating label is its name, so a screen
  reader reads the name with the value.
- Title says what happens; body says why; buttons are verbs.
- Destructive actions need confirmation.
- An error explains what to do next, not just "Error".
- No nested dialogs.

### Settings

- Group by meaning; apply immediately.
- The default value is visible.
- Dangerous settings (reset) are separate and confirmed.
- A row is a Material 3 list item: a body-large title, a body-medium
  summary in `onSurfaceVariant`, 16dp side padding, and a switch from the
  Material 3 compound button. A section label is a title-small label in
  the accent color. Rows grow with the text scale; they never ellipsize
  a title.

### Help

- Local HTML from assets, no JavaScript, no network.
- Readable typography, dark theme, zoom.
- External links open in the browser only after a user action.

## 10. Game board

- Palette and geometry are part of parity (`PARITY.md`).
- The theme does not change coordinates, thicknesses, or primitive
  order.
- Grid, cursor, and highlights must be distinguishable in the dark
  theme.
- Night colors are set in `game_props.xml`; they are not computed from
  the theme.
- Board touch targets are at least 48dp where possible without
  changing rules. Otherwise record a deviation.
- Games with drag (Untangle, Palisade, Inertia) need dedicated gesture
  and undo tests.

## 11. Accessibility

Required for every screen:

- TalkBack: meaningful traversal, order, headings, and states.
- `contentDescription` for every interactive element.
- Custom actions for undo/redo/solve when they are on screen.
- Visible focus for keyboard and DPAD.
- 48dp minimum; 8dp between targets.
- Contrast 4.5:1 / 3:1.
- Font scale 200% without clipping.
- RTL: the layout mirrors, text does not break, numbers stay readable.
- Do not rely on color alone.
- Reduced motion.
- Errors and empty states are announced.
- A status line is a polite live region, and the same text is not
  announced twice: a redraw or a timer tick is not a state change.
- The game board is one node labelled with the game name and its shape,
  carrying Undo, Redo, Restart, and Solve, and one node per play unit
  below 400 units, each labelled from game state and each offering the
  game's pointer buttons. Above the cap the cursor unit alone is
  exposed and the arrow keys move it. Labels carry no coordinate that
  `collectionItemInfo` already reports. (`docs/adr/0013-spoken-board.md`)
- One hidden polite live region per game screen announces the diff of a
  board change: one or two units are read in board order, three or more
  are summarised from the cursor. The status line is not a live region
  while the board has units, so one event is one utterance.

Accessibility is an allowed area for deviations: upstream does not
describe it. Every deviation is recorded in `DEVIATIONS.md`.

## 12. Text and tone

- Simple English, sentence case, short phrases.
- No exclamation marks and no marketing tone.
- Buttons are verbs: `Undo`, `Restart`, `Share`.
- An error says what happened and what to do.
- Do not shorten words for space if clarity suffers.
- All strings live in resources, with plurals and no concatenation.
- UI localization does not start until upstream #1 is fixed for C
  strings (see README).

## 13. Anti-slop

Forbidden:

- purple-blue gradients, glow, neon, glassmorphism for effect;
- emoji as icons;
- shouty headlines, ALL CAPS, "revolutionary", "smart";
- uneven spacing and random radii;
- cards inside cards and pill buttons everywhere;
- stock illustrations and abstract blobs;
- decorative animations that cannot be disabled;
- text on a colored background with poor contrast;
- "AI style": identical cards, huge headings, empty space instead of
  content.

Instead:

- a neutral surface, one accent, clear hierarchy;
- real game icons and real descriptions;
- a dense but breathable grid;
- one expressive moment per screen, not ten.

## 14. Review

Pass order (like a design review, not "repaint everything at once"):

1. spacing and grid;
2. color and contrast;
3. icons;
4. text and tone;
5. typography;
6. layout and adaptivity;
7. details and states;
8. motion.

Before merging a UI change:

1. Before/after screenshots: light/dark, phone/tablet, font scale 200%.
2. TalkBack pass.
3. Contrast and targets against the checklist.
4. Anti-slop check from section 13.
5. Triage findings by severity: fix P0/P1 before merge; P2/P3 become
   issues.
6. In the PR, include evidence: screenshots, commands, test results.

Severity:

- **P0** — blocks: an unreachable control, clipped text, a screen that
  cannot be closed.
- **P1** — major defect: poor contrast, broken RTL, lost state.
- **P2** — noticeable but not blocking.
- **P3** — polish.

## 15. Platform constraints

- `minSdk 21`: dynamic color, predictive back, and some M3 effects are
  not available everywhere. Graceful degradation is required.
- Phone, tablet, Chromebook, and TV-like layouts: verify adaptive
  behavior.
- Window size classes: the shell has one rule, in `ui/WindowSize.kt`
  (`docs/adr/0012-adaptive-layouts.md`). Compact is below 600dp of window
  width, medium is 600dp to 839dp, expanded is 840dp and wider. Side
  margins are 16dp on compact and 24dp from medium up; text-heavy shell
  content (chooser, settings, help) is capped at 840dp and centred; a
  dialog is capped at 560dp. The game board is never capped: the engine
  owns its geometry.
- The game screen's key bar follows the window shape, not the size
  class: a bottom row when the window is taller than it is wide, a side
  column when it is wider. Both give the 48dp cells of the input
  contract.
- A resize arrives as `onConfigurationChanged` because the activities
  swallow `screenSize`. The shell must follow it in place: no game is
  restarted and no board is lost.
- Mouse, stylus, hardware keyboard, and DPAD must keep working.
- One APK, offline, minimum permissions.
- The night theme does not affect the game board palette.
