# ADR 0012: Window size classes for the shell

- **Status:** accepted
- **Date:** 2026-11-19

## Context

The shell was designed for a phone window. The chooser, Settings, and
Help stretched their content across the whole window, the game screen
decided how much of the action bar to fill with a hard-coded
`screenWidthDIP` threshold, and no screen knew the Material 3 window size
classes. On a tablet, a Chromebook, or a desktop window this is one very
long line of text and a list that reads badly.

The app already declares `android:appCategory="game"`, so the Android 16
change that ignores orientation and resizability restrictions does not
apply here, and every activity lists `orientation|screenSize|
smallestScreenSize|screenLayout` in `android:configChanges`. A resize
therefore arrives as `onConfigurationChanged` in a live activity, not as
a recreation. The shell must follow it without recreating the game.

The dependency policy in `AGENTS.md` asks for an ADR before a new
dependency. The owner allowed exactly the window size class artifact of
the Compose Material 3 set that the Compose BOM already resolves.

## Decision

1. **One rule, three classes.** Every screen reads its window through
   `ui/WindowSize.kt`. The classes are the Material 3 breakpoints on the
   activity window width: compact below 600dp, medium 600dp to 839dp,
   expanded 840dp and wider. A screen never compares a width with its own
   breakpoint.

2. **The dependency is
   `androidx.compose.material3:material3-window-size-class`, without a
   version.** The version comes from the Compose BOM that the app already
   uses, so the artifact cannot drift from `material3`.
   `WindowSizeClass.calculateFromSize` is the one function behind the
   rule.

3. **The class is read from the activity `Configuration`.** That is the
   size of the app window, so it is correct in split screen and in a
   freeform window, and the same call works from View code and from
   Compose. Compose screens read `LocalConfiguration`; View screens read
   `resources.configuration` where they need it, which is what makes a
   resize take effect in place.

4. **Medium and expanded keep the reading measure.** Text-heavy shell
   content is capped at `shell_content_max_width` (840dp) and centred:
   the chooser list, the settings list, and the help page. Compact
   windows are not narrowed. The game board is never capped; the engine
   keeps `midend_size` as the only authority on board geometry.

5. **The chooser stays one list.** A list-detail pane was rejected: the
   chooser's only job is to open a game in one tap, and a detail pane
   would either add a second tap before the game or show a preview that
   nothing needs. The adaptive grid (`GridCells.Adaptive`) already gives
   two or three columns inside the reading measure, so the extra width is
   used by the grid, not by stretched rows.

6. **The game screen keeps its orientation rule.** The board fills the
   space, the key bar is a bottom row in a window that is taller than it
   is wide and a side column in a window that is wider than it is tall.
   That arrangement already comes from `layout/` and `layout-land/`,
   which follow the window shape, not the device, so split screen and
   desktop windows get the right one. The key grid is the game's input
   contract and is not rebuilt for the size class. The window size class
   decides one shell detail: from medium up, the toolbar shows its action
   labels and the game title, because there is room for them.

7. **Dialogs are capped at the Material 3 maximum width.** A dialog the
   shell creates calls `Dialog.capReadingWidth()`, which sets
   `shell_dialog_max_width` (560dp) on a medium or wider window and does
   nothing on a compact one.

8. **No duplicated size buckets.** The margins (16dp on a compact window,
   24dp from medium up) come from the existing `space_m` and `space_l`
   tokens chosen by the size class, so there is no `values-sw600dp` copy
   of the same number. The only new resources are the two width tokens in
   `values/dimens.xml`.

9. **The two game screen layouts stay two.** They are not duplicates:
   the key bar is at the bottom in one and on the side in the other, and
   the board container is the same view tree in both. Merging them would
   move the board's measurement into new code, which `docs/PARITY.md`
   does not allow.

## The rule in one table

| Window width | Side margin | Reading measure | Chooser | Settings and Help | Game screen |
|---|---|---|---|---|---|
| compact, below 600dp | 16dp | none, full width | adaptive grid | full width | bottom key bar, actions are icons |
| medium, 600-839dp | 24dp | 840dp, centred | adaptive grid inside the measure | capped and centred | toolbar title and action labels |
| expanded, 840dp and wider | 24dp | 840dp, centred | adaptive grid inside the measure | capped and centred | toolbar title and action labels |

## Fallback for minSdk 21

The artifact is a Compose Material 3 library, so it works from the
minimum SDK the app supports; it computes the class from a size and does
not need a platform API. Nothing in the shell depends on an API level for
the size class. The dialog cap uses `Window.setLayout`, which exists long
before API 21. Predictive back and dynamic color keep their existing
per-API fallbacks and are not touched here.

## Consequences

Positive:

- one place to change if the breakpoints or the reading measure change;
- Settings, Help, and the chooser are readable on a tablet without a
  second copy of each layout;
- the size class is available to View and Compose code through the same
  function, so the two halves of the shell cannot disagree;
- a resize is handled in the live activity, so a running game keeps its
  id and its save text, which
  `ShellLayoutTest.theGameSurvivesAWindowResize` checks.

Negative:

- one more AndroidX artifact in the APK;
- the toolbar shows action labels from 600dp, where the old threshold was
  620dp, so a window between those two widths now shows the title and the
  labels;
- a dialog on a medium window is 560dp wide even if the platform would
  have made it narrower.

## Alternatives

1. **Hand-rolled breakpoints on `screenWidthDp`.** Rejected: it is the
   same three numbers in every screen, and every screen would be free to
   drift. The audit found exactly that kind of drift in the action bar.
2. **`androidx.window` and `WindowMetricsCalculator`.** Rejected: the
   configuration already carries the window size for the activities this
   app has, and a second window library is a larger dependency for the
   same answer.
3. **A list-detail chooser.** Rejected for the reason in decision 5.
4. **Compose for Settings and Help.** Rejected: ADR 0002 keeps the
   `Preference` screens and the `WebView`, and this change caps their
   content with one helper instead of rewriting them.

### Verification of this ADR

- `ShellLayoutTest.theShellKeepsTheReadingMeasureOnAnExpandedWindow`
  asserts the chooser list and the settings list are 840dp wide and
  centred in a simulated expanded window.
- `ShellLayoutTest.theGameSurvivesAWindowResize` grows the window with a
  running game and checks the board is drawn, the game id and the save
  text are unchanged, the key bar is usable, a real tap still applies the
  same move, and a landscape window puts the key bar in its side column.
- `docs/TESTING.md` records the run and its totals.
