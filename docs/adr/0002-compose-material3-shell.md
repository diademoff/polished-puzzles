# ADR 0002: Compose and Material 3 for the shell

- **Status:** accepted. Amended 2026-11-19: dynamic color is now a user
  setting (`docs/DESIGN.md` §3), so decision 3 is superseded by the
  "Use system colors" switch. It still applies to the shell only.
- **Date:** 2026-11-14

## Context

The shell is old: XML layouts, `GridLayout`, two themes
(`Theme.AppCompat`), Holo dialogs, and hard-coded colors. It works, but
it is hard to make accessible and adaptive. The project goal is a modern
UI/UX with correct insets, dark theme, 200% font scale, RTL, and
predictive back.

The game board is different: it is drawn by the C engine through
`drawing_api`. Its geometry and palette are part of parity and must not
be touched by a UI framework.

Compose is already in the build: the `ButtonsView` in the game screen is
a Compose view, and the Compose BOM, Material 3, and `activity-compose`
are dependencies. No new runtime dependency is needed.

## Decision

1. **Shell screens use Compose and Material 3.** The chooser is the
   first screen. Settings and help stay view-based where they wrap
   platform components that have no Compose equivalent yet (the
   `Preference` screens and the `WebView`), but they use the same
   Material 3 theme.
2. **One palette, one source.** The colors live in
   `values/colors_shell.xml` and `values-night/colors_shell.xml`. The
   XML theme maps them to Material 3 attributes; the Compose theme reads
   the same resources through `colorResource`. Components never contain
   hex values.
3. **Dynamic color is not enabled now.** A wallpaper-derived palette
   would put the shell and the game board in different color worlds.
   `DESIGN.md` allows it as an optional user setting later; if it is
   added, it applies to the shell only.
4. **The board is not themed.** `Theme.SgTPuzzles.Game` only sets the
   window background to `@color/game_background`. The C engine keeps
   drawing its own palette, and `docs/PARITY.md` still governs it.
5. **Edge-to-edge everywhere.** Every shell activity calls
   `enableEdgeToEdge()` and applies the insets it needs. Scrollable
   content draws under the system bars and uses content padding so the
   last row stays reachable.
6. **Compose UI tests for shell behavior.** `androidx.compose.ui:ui-test-junit4`
   is added to `androidTest`. It is part of the Compose dependency set
   that the app already uses, not a third-party test framework.
7. **Screenshot comparison is deferred.** The existing fastlane
   screenshots remain as store artifacts. A pixel-comparison gate needs
   a deterministic device or a host renderer; that decision is separate
   and is tracked as Phase 1 debt until a device is available.

## Consequences

Positive:

- one theme for the shell, with real light and dark palettes;
- accessibility is expressed in the component (semantics, minimum
  targets, font scale) instead of being patched per layout;
- adaptive layouts become simple (`GridCells.Adaptive`);
- the board stays untouched, so parity is unaffected.

Negative:

- the chooser is rewritten, so its instrumentation test moves from
  Espresso view matchers to Compose matchers;
- Compose and view code coexist during the migration;
- a screenshot gate still needs a device or a host renderer.

## Alternatives

1. **Restyle the XML layouts only.** Rejected: it keeps `GridLayout` and
   the manual insets, and it does not fix font scale or RTL behavior.
2. **Rewrite the board in Compose now.** Rejected: it would break the
   parity contract in Phase 1. The renderer is Phase 2 and needs a
   trace comparison first.
3. **Add a design system library.** Rejected: Material 3 already
   provides the components and tokens the design needs, and the
   dependency policy asks for the platform first.
