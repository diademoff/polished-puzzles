package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_2
import android.view.KeyEvent.KEYCODE_9
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast
import androidx.test.espresso.matcher.ViewMatchers.withId
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.DOMINOSA
import name.boyle.chris.sgtpuzzles.backend.INERTIA
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.LOOPY
import name.boyle.chris.sgtpuzzles.backend.MAP
import name.boyle.chris.sgtpuzzles.backend.NET
import name.boyle.chris.sgtpuzzles.backend.NETSLIDE
import name.boyle.chris.sgtpuzzles.backend.PALISADE
import name.boyle.chris.sgtpuzzles.backend.PEARL
import name.boyle.chris.sgtpuzzles.backend.SINGLES
import name.boyle.chris.sgtpuzzles.backend.TENTS
import name.boyle.chris.sgtpuzzles.backend.TRACKS
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Dominosa
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Inertia
import name.boyle.chris.sgtpuzzles.engine.Keen
import name.boyle.chris.sgtpuzzles.engine.Loopy
import name.boyle.chris.sgtpuzzles.engine.Map
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.Palisade
import name.boyle.chris.sgtpuzzles.engine.Pearl
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Singles
import name.boyle.chris.sgtpuzzles.engine.Tents
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the seventh enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * The structure is the sixth wave's [SixthWaveTileSizeInputTest]: one
 * activity per game, the first script on the board laid out at the
 * default `limitDpi=auto`, then `limitDpi=off` and the second script on
 * the resized board. Every point comes from the live [BoardGeometry],
 * and the stored game preferences are removed so the C defaults decide
 * the gesture.
 *
 * Every case loads a frozen game ID, so the suite generates no board at
 * all and therefore cannot generate one above a generation cap. The
 * caps themselves are pinned in the engine and parity tests.
 *
 * The C input code decides each gesture, and the scripts include the
 * inert ones so the delta is honest:
 * - `netslide` taps the left or top margin band, which slides the
 *   nearest row or column. A tap inside a cell is inert.
 * - `palisade` taps near a cell edge, which toggles the nearest fence
 *   on both sides. A tap in the board corner lands on an outward fence
 *   and is inert. C has no fence drag.
 * - `inertia` taps a cell other than the dot's, which moves the dot in
 *   the nearest of eight compass octants. A tap on the dot's own cell
 *   is inert.
 * - `tents` taps a cell, which cycles blank, tent, and grass on the
 *   released cell. A tap on a tree cell is inert.
 * - `net` taps a tile, which rotates it left on the release. A tap in
 *   the grid gutter is inert.
 * - `tracks` taps a square centre or one of its edge bands. A tap in
 *   the outer border is inert.
 * - `dominosa` taps one half of a square to place the domino across
 *   that edge, and taps it again to remove it. A tap in the border is
 *   inert.
 * - `map` drags from a clue-coloured region to an uncoloured one, the
 *   only C path that recolours. A plain tap never changes the board.
 * - `keen` taps a cell and then a digit. A digit with no highlighted
 *   cell is inert.
 * - `singles` taps a cell to cycle black and circle, and holds a press
 *   for the right-button circle. A tap in the outer border toggles the
 *   stored `show-black-nums` display only.
 * - `pearl` taps near a cell edge to draw the line there, and holds a
 *   press on an unlined edge to mark the circle. A tap on a cell centre
 *   is inert.
 * - `loopy` taps near an edge to cycle yes and no. A tap outside the
 *   grid is inert.
 *
 * Every step asserts the move count and whether the save text changed,
 * and a move step asserts its exact `MOVE` record. The save comparison
 * drops the `TIME` record: none of these games is timed, but the helper
 * is the same one the earlier waves use.
 */
@RunWith(Parameterized::class)
class SeventhWaveTileSizeInputTest(private val case: Case) {

    /** A point in game coordinates, measured from the board origin. */
    data class Tick(val x: Int, val y: Int)

    /** A real gesture on the board, or a real key. */
    sealed interface Action {
        data class Press(val at: (BoardGeometry) -> Tick) : Action
        data class Drag(
            val from: (BoardGeometry) -> Tick,
            val to: (BoardGeometry) -> Tick,
        ) : Action
        /** A press held past the long-press timeout: one right click. */
        data class HoldPress(val at: (BoardGeometry) -> Tick) : Action
        data class Key(val code: Int) : Action
    }

    /**
     * One gesture. [moves] is the expected change of the move count,
     * [changesSave] is true when the save text must change, and [move]
     * is the exact `MOVE` record of a move step.
     */
    data class Step(
        val description: String,
        val action: Action,
        val moves: Int = 0,
        val changesSave: Boolean = moves != 0,
        val move: String? = null,
    )

    data class Case(
        val backend: BackendName,
        val game: Game,
        val launch: String,
        val first: List<Step>,
        val second: List<Step>,
    ) {
        override fun toString(): String = backend.toString()
    }

    @Test
    fun theGesturesUseTheSizedBoard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearBackendState(context, case.backend)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val originalLimitDpi = prefs.getString(LIMIT_DPI_KEY, "auto")
        val originalGamePrefs = prefs.getString(case.backend.preferencesName, null)
        // A stored preference would change the gesture: singles reads
        // `show-black-nums` and palisade its cursor mode. The test pins
        // the C defaults.
        prefs.edit().remove(case.backend.preferencesName).commit()
        try {
            ActivityScenario.launch<GamePlay>(
                GamePlayTestSupport.intent(context, case.backend, case.launch),
            ).use { scenario ->
                val first = awaitSizedBoard(scenario, previousTile = null)
                // A legitimate viewport that already yields the
                // preferred tile cannot exercise the non-default case,
                // so it skips. The second board is a hard assertion.
                assumeTrue(
                    "skipped: this viewport yields the preferred tile " +
                        "(${case.game.preferredTileSize}), so the board is not at a " +
                        "non-default tile size",
                    first.tileSize != case.game.preferredTileSize,
                )
                assertKotlinEngine(scenario)
                assertEquals("a fresh game starts with no moves", 0, liveMoveCount(scenario))

                val afterFirst = runSteps(scenario, first, case.first, 0)

                prefs.edit().putString(LIMIT_DPI_KEY, "off").commit()
                val second = awaitSizedBoard(scenario, previousTile = first.tileSize)
                assertTrue(
                    "the live tile ${second.tileSize} is the preferred tile, so this is not " +
                        "the non-default case",
                    second.tileSize != case.game.preferredTileSize,
                )
                assertTrue(
                    "limitDpi=off did not change the tile size (${first.tileSize})",
                    second.tileSize != first.tileSize,
                )
                assertKotlinEngine(scenario)
                runSteps(scenario, second, case.second, afterFirst)
            }
        } finally {
            if (originalLimitDpi == null) {
                prefs.edit().remove(LIMIT_DPI_KEY).commit()
            } else {
                prefs.edit().putString(LIMIT_DPI_KEY, originalLimitDpi).commit()
            }
            if (originalGamePrefs == null) {
                prefs.edit().remove(case.backend.preferencesName).commit()
            } else {
                prefs.edit()
                    .putString(case.backend.preferencesName, originalGamePrefs)
                    .commit()
            }
        }
    }

    /** Runs [steps] on [board] and returns the total move count. */
    private fun runSteps(
        scenario: ActivityScenario<GamePlay>,
        board: BoardGeometry,
        steps: List<Step>,
        startMoves: Int,
    ): Int {
        var total = startMoves
        for (step in steps) {
            val before = liveStableSave(scenario)
            when (val action = step.action) {
                is Action.Press -> tap(board, action.at(board))
                is Action.Drag -> drag(board, action.from(board), action.to(board))
                is Action.HoldPress -> hold(board, action.at(board))
                is Action.Key -> press(action.code)
            }
            total += step.moves
            awaitMoveCount(scenario, total)
            if (step.changesSave) {
                assertNotEquals(
                    "${step.description} must change the save",
                    before,
                    liveStableSave(scenario),
                )
            } else {
                // An inert gesture returns at once, so it needs a bounded settle.
                GamePlayTestSupport.awaitInertGesture(scenario)
                assertEquals(
                    "${step.description} must not change the save",
                    before,
                    liveStableSave(scenario),
                )
            }
            step.move?.let {
                assertEquals(
                    "${step.description} wrote the wrong move",
                    it,
                    lastMove(scenario),
                )
            }
        }
        return total
    }

    /** A real tap at [tick], released before the long-press timeout. */
    private fun tap(board: BoardGeometry, tick: Tick) {
        onView(withId(R.id.game_view)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                point(board, tick),
                Press.FINGER,
                0,
                0,
            ),
        )
    }

    /** A real press, drag, and release from one board point to another. */
    private fun drag(board: BoardGeometry, from: Tick, to: Tick) {
        onView(withId(R.id.game_view)).perform(
            GeneralSwipeAction(
                Swipe.SLOW,
                point(board, from),
                point(board, to),
                Press.FINGER,
            ),
        )
    }

    /** The shell turns this hold into one right click. */
    private fun hold(board: BoardGeometry, at: Tick) {
        onView(withId(R.id.game_view)).perform(holdGesture(board, at))
    }

    /**
     * A real key, as the shell's on-screen key row sends it. The key row
     * is a Compose view, so this calls the same entry point the button
     * does instead of tapping the button.
     */
    private fun press(code: Int) {
        onView(withId(R.id.game_view)).perform(ViewActions.pressKey(code))
    }

    /**
     * A press held past the shell's long-press timeout. `android.c`
     * turns the hold into `RIGHT_BUTTON`, and the release becomes
     * `RIGHT_RELEASE`; `loopMainThreadForAtLeast` lets the main looper
     * run the `postDelayed` that makes that switch, so the shell path is
     * the real one.
     */
    private fun holdGesture(board: BoardGeometry, tick: Tick): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayingAtLeast(90)

            override fun getDescription(): String = "a long press on the board"

            override fun perform(uiController: UiController, view: View) {
                val at = screenPoint(view, board, tick)
                val downTime = SystemClock.uptimeMillis()
                uiController.injectMotionEvent(
                    event(MotionEvent.ACTION_DOWN, downTime, downTime, at),
                )
                uiController.loopMainThreadForAtLeast(HOLD_MILLIS)
                val upTime = SystemClock.uptimeMillis()
                uiController.injectMotionEvent(
                    event(MotionEvent.ACTION_UP, downTime, upTime, at),
                )
                uiController.loopMainThreadForAtLeast(STEP_MILLIS)
            }
        }

    private fun event(action: Int, downTime: Long, eventTime: Long, at: FloatArray) =
        MotionEvent.obtain(downTime, eventTime, action, at[0], at[1], 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    /**
     * The point in screen coordinates, the same conversion [point] makes
     * for Espresso: the board is centred in the view, and a game
     * coordinate is multiplied by the density the view used for this
     * layout.
     */
    private fun screenPoint(view: View, board: BoardGeometry, tick: Tick): FloatArray {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val gameView = view as GameView
        val density = view.width.toFloat() / gameView.wDip
        return floatArrayOf(
            location[0] + density * (board.originX + tick.x),
            location[1] + density * (board.originY + tick.y),
        )
    }

    private fun assertKotlinEngine(scenario: ActivityScenario<GamePlay>) {
        scenario.onActivity { activity ->
            assertTrue(
                "${case.backend} is not using the Kotlin backend",
                activity.gameEngine is KotlinGameEngine,
            )
            assertEquals(
                "the activity selected a different backend",
                case.backend,
                activity.currentBackend,
            )
        }
    }

    private fun liveEngine(scenario: ActivityScenario<GamePlay>): KotlinGameEngine {
        var engine: KotlinGameEngine? = null
        scenario.onActivity { activity ->
            engine = activity.gameEngine as? KotlinGameEngine
        }
        return checkNotNull(engine) { "${case.backend} is not using the Kotlin backend" }
    }

    private fun liveSaveText(scenario: ActivityScenario<GamePlay>): String =
        GamePlayTestSupport.saveText(liveEngine(scenario))

    /**
     * The save text without the `TIME` record. None of these games is
     * timed, but the helper keeps the same comparison as the earlier
     * waves.
     */
    private fun liveStableSave(scenario: ActivityScenario<GamePlay>): String =
        GamePlayTestSupport.stableSave(liveEngine(scenario))

    private fun liveMoveCount(scenario: ActivityScenario<GamePlay>): Int =
        GamePlayTestSupport.moveCount(liveEngine(scenario))

    private fun lastMove(scenario: ActivityScenario<GamePlay>): String? =
        SgtpSave.parse(liveSaveText(scenario)).values("MOVE").lastOrNull()

    /** Waits for the move count, because a move can arrive after a post. */
    private fun awaitMoveCount(scenario: ActivityScenario<GamePlay>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (liveMoveCount(scenario) == expected) return
            SystemClock.sleep(50)
        }
        assertEquals(
            "the gesture did not reach the expected move count",
            expected,
            liveMoveCount(scenario),
        )
    }

    /**
     * The point in view coordinates: the board is centred in the view,
     * and a game coordinate is multiplied by the density the view used
     * for this layout. `wDip` and the view width give that density
     * exactly, including `limitDpi=off`, where it is 1.
     */
    private fun point(board: BoardGeometry, tick: Tick): CoordinatesProvider =
        CoordinatesProvider { view: View ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val gameView = view as GameView
            val density = view.width.toFloat() / gameView.wDip
            floatArrayOf(
                location[0] + density * (board.originX + tick.x),
                location[1] + density * (board.originY + tick.y),
            )
        }

    /**
     * Waits for the resize that follows the launch or the preference
     * change, then returns the geometry. A [previousTile] of null
     * accepts any laid-out board, so the caller can skip when the
     * viewport yields the preferred tile. A non-null [previousTile]
     * requires a live tile that changed and is not the preferred one.
     */
    private fun awaitSizedBoard(
        scenario: ActivityScenario<GamePlay>,
        previousTile: Int?,
    ): BoardGeometry {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var board: BoardGeometry? = null
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.game_view)
                val engine = activity.gameEngine as? KotlinGameEngine
                if (view.width > 0 && view.height > 0 && engine != null) {
                    val candidate = engine.boardGeometry
                    val accepted = if (previousTile == null) {
                        true
                    } else {
                        candidate.tileSize != previousTile &&
                            candidate.tileSize != case.game.preferredTileSize
                    }
                    if (accepted) {
                        board = candidate
                    }
                }
            }
            board?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("the board did not reach the expected tile size")
    }

    companion object {

        /** Past `ViewConfiguration.getLongPressTimeout()` (500 ms). */
        private const val HOLD_MILLIS = 900L
        private const val STEP_MILLIS = 32L

        /**
         * A `netslide` cell. C uses `BORDER = TILE_SIZE` and one pixel
         * of tile border, so the first cell starts at `T + 1`.
         */
        private fun netslideCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t + 1 + c * t + t / 2, t + 1 + r * t + t / 2)
        }

        /** The left margin band of a row: a row slide `+1`. */
        private fun netslideRowBand(r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2, t + 1 + r * t + t / 2)
        }

        /** The top margin band of a column: a column slide `+1`. */
        private fun netslideColBand(c: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t + 1 + c * t + t / 2, t / 2)
        }

        /** The corner margin, where neither a row nor a column is meant. */
        private fun netslideCorner(): (BoardGeometry) -> Tick = { board ->
            Tick(board.tileSize / 2, board.tileSize / 2)
        }

        /**
         * A point in the lower half of a `palisade` cell.
         * `FROMCOORD` subtracts `MARGIN = T/2`, and the cell centre is
         * a tie between the two axes, so a one-pixel density rounding
         * could pick either fence. Three quarters down is far from both
         * ties and always makes the nearest fence the bottom edge.
         */
        private fun palisadeCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2 + c * t + t / 2, t / 2 + r * t + 3 * t / 4)
        }

        /** The board corner: cell (0,0) with its top fence outward. */
        private fun palisadeCorner(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        /**
         * The centre of an `inertia` cell. The Android build defines
         * `SMALL_SCREEN`, so C uses `BORDER = T/4` and
         * `COORD(x) = x*T + T/4`.
         */
        private fun inertiaCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 4 + c * t + t / 2, t / 4 + r * t + t / 2)
        }

        /** The centre of a `tents` cell: `COORD(x) = x*T + T/2`. */
        private fun tentsCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(c * t + t / 2, r * t + t / 2)
        }

        /**
         * The centre of a `net` tile. The input origin is
         * `WINDOW_OFFSET + LINE_THICK`, where `LINE_THICK` is
         * `(T+47)/48`.
         */
        private fun netTile(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val pad = 4 + (t + 47) / 48
            Tick(pad + c * t + t / 2, pad + r * t + t / 2)
        }

        /** A point in the `net` grid gutter, outside every tile. */
        private fun netGutter(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val pad = 4 + (t + 47) / 48
            Tick(pad - 1, pad - 1)
        }

        /**
         * The `tracks` cell size, `(T/6)*6`, and the outer border.
         * C uses `BORDER = TILE_SIZE/8`, or `T % 6` for a tiny tile.
         */
        private fun tracksTile(t: Int): Int = (t / 6) * 6

        private fun tracksBorder(t: Int): Int {
            val tile = tracksTile(t)
            val maxBorder = tile / 8
            return if (maxBorder <= 5) minOf(t % 6, maxBorder) else maxBorder
        }

        /** The centre of a `tracks` square. */
        private fun tracksSquare(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val tile = tracksTile(t)
            val border = tracksBorder(t)
            Tick((c + 1) * tile + border + tile / 2, (r + 1) * tile + border + tile / 2)
        }

        /** A point in the north edge band of a `tracks` square. */
        private fun tracksEdgeNorth(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val tile = tracksTile(t)
            val border = tracksBorder(t)
            Tick(
                (c + 1) * tile + border + tile / 2,
                (r + 1) * tile + border + tile / 2 - tile / 4,
            )
        }

        /** A point in the outer border, off the `tracks` grid. */
        private fun tracksBorderPoint(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val tile = tracksTile(t)
            val border = tracksBorder(t)
            Tick(1, 4 * tile + border + tile / 2)
        }

        /**
         * The right half of a `dominosa` square: C uses
         * `BORDER = 3T/4`, and the domino goes to the square on the
         * right.
         */
        private fun dominosaRight(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val border = t * 3 / 4
            Tick(border + c * t + t / 2 + t / 4, border + r * t + t / 2)
        }

        /** A point in the `dominosa` border, before the first square. */
        private fun dominosaBorder(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        /** The centre of a `map` cell: `COORD(x) = x*T + T`. */
        private fun mapCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t + t / 2, (r + 1) * t + t / 2)
        }

        /**
         * A point inside a `keen` cell. C uses `BORDER = T/2`, and the
         * two-pixel offset keeps the press off the cell border.
         */
        private fun keenCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2 + c * t + 2, t / 2 + r * t + 2)
        }

        /** The centre of a `singles` cell: `COORD(x) = x*T + T/2`. */
        private fun singlesCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2 + c * t + t / 2, t / 2 + r * t + t / 2)
        }

        /** A point in the `singles` border: outside the grid. */
        private fun singlesBorder(): (BoardGeometry) -> Tick = { board ->
            Tick(0, board.tileSize / 2 + board.tileSize / 2)
        }

        /**
         * The effective `pearl` tile: C uses `((T-1)/2)*2+1`, and
         * `BORDER = T/2` for the square grid type.
         */
        private fun pearlTile(t: Int): Int = ((t - 1) / 2) * 2 + 1

        /** The centre of a `pearl` cell: a cell click with no move. */
        private fun pearlCentre(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val et = pearlTile(board.tileSize)
            val border = et / 2
            Tick(border + c * et + et / 2, border + r * et + et / 2)
        }

        /** A point near the top edge of a `pearl` cell. */
        private fun pearlTopEdge(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val et = pearlTile(board.tileSize)
            val border = et / 2
            Tick(border + c * et + 2, border + r * et + 2)
        }

        /** The board corner, outside the `loopy` grid. */
        private fun loopyCorner(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        /**
         * A point on a `loopy` horizontal edge, one tile apart. C
         * subtracts `BORDER = T/2` and picks the nearest edge; the
         * midpoint of an edge is half a tile from each vertex, so the
         * point stays on one edge at every tile size.
         */
        private fun loopyEdge(step: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2 + step * t, t)
        }

        private val cases = listOf(
            Case(
                backend = NETSLIDE,
                game = Netslide,
                // The frozen 3x3 game of seed 12345.
                launch = "3x3:21c4d5963",
                first = listOf(
                    Step(
                        "a tap inside the cell at (0,0)",
                        action = Action.Press(netslideCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "a tap in the left band of row 0",
                        action = Action.Press(netslideRowBand(0)),
                        moves = 1,
                        move = "R0,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap in the top band of column 2",
                        action = Action.Press(netslideColBand(2)),
                        moves = 1,
                        move = "C2,1",
                    ),
                    Step(
                        "a tap in the board corner",
                        action = Action.Press(netslideCorner()),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = PALISADE,
                game = Palisade,
                // The frozen 5x5n5 game of seed 12345.
                launch = "5x5n5:a313d3d3f32a1",
                first = listOf(
                    Step(
                        "a tap in the board corner",
                        action = Action.Press(palisadeCorner()),
                        moves = 0,
                    ),
                    Step(
                        "a tap in the centre of the cell at (1,1)",
                        action = Action.Press(palisadeCell(1, 1)),
                        moves = 1,
                        move = "F1,1,4F1,2,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap in the centre of the cell at (3,3)",
                        action = Action.Press(palisadeCell(3, 3)),
                        moves = 1,
                        move = "F3,3,4F3,4,1",
                    ),
                    Step(
                        "the same tap again, which adds the disabled bit",
                        action = Action.Press(palisadeCell(3, 3)),
                        moves = 1,
                        move = "F3,3,68F3,4,17",
                    ),
                ),
            ),
            Case(
                backend = INERTIA,
                game = Inertia,
                // The frozen 10x8 game of seed 12345. The dot starts at
                // (7,0).
                launch = "10x8:bswmsgmSbmbmgmgbwgbbmgmwgwwswswmbmssbgmswbsbggbsswgwggbgsmswwswmwssbgbmmwgsbgmwm",
                first = listOf(
                    Step(
                        "a tap on the cell at (7,7), south of the dot",
                        action = Action.Press(inertiaCell(7, 7)),
                        moves = 1,
                        move = "4",
                    ),
                    Step(
                        "a tap on the cell at (9,7), south-east of the dot",
                        action = Action.Press(inertiaCell(9, 7)),
                        moves = 1,
                        move = "4",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the dot's own cell at (7,4)",
                        action = Action.Press(inertiaCell(7, 4)),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the cell at (0,7), south-west of the dot",
                        action = Action.Press(inertiaCell(0, 7)),
                        moves = 1,
                        move = "5",
                    ),
                ),
            ),
            Case(
                backend = TENTS,
                game = Tents,
                // The frozen 8x8de game of seed 12345. Cell (1,0) holds a
                // tree.
                launch = "8x8de:ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3",
                first = listOf(
                    Step(
                        "a tap on the tree at (1,0)",
                        action = Action.Press(tentsCell(1, 0)),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the blank cell at (0,0)",
                        action = Action.Press(tentsCell(0, 0)),
                        moves = 1,
                        move = "T0,0",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the blank cell at (2,0)",
                        action = Action.Press(tentsCell(2, 0)),
                        moves = 1,
                        move = "T2,0",
                    ),
                    Step(
                        "the same tap again, which marks grass",
                        action = Action.Press(tentsCell(2, 0)),
                        moves = 1,
                        move = "N2,0",
                    ),
                ),
            ),
            Case(
                backend = NET,
                game = Net,
                // The frozen 5x5 game of seed 12345.
                launch = "5x5:1863151ea86e7e3c7abc21a32",
                first = listOf(
                    Step(
                        "a tap in the grid gutter",
                        action = Action.Press(netGutter()),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the tile at (0,0)",
                        action = Action.Press(netTile(0, 0)),
                        moves = 1,
                        move = "A0,0",
                    ),
                ),
                second = listOf(
                    Step(
                        "the same tile rotation at the second tile size",
                        action = Action.Press(netTile(0, 0)),
                        moves = 1,
                        move = "A0,0",
                    ),
                    Step(
                        "a tap on the tile at (2,2)",
                        action = Action.Press(netTile(2, 2)),
                        moves = 1,
                        move = "A2,2",
                    ),
                ),
            ),
            Case(
                backend = TRACKS,
                game = Tracks,
                // The frozen 8x8dt game of seed 12345.
                launch = "8x8dt:i5c5CiCu9pA,4,4,4,3,4,7,5,S3,3,6,6,S5,4,4,3,3",
                first = listOf(
                    Step(
                        "a tap in the centre of the square at (2,2)",
                        action = Action.Press(tracksSquare(2, 2)),
                        moves = 1,
                        move = "TS2,2",
                    ),
                    Step(
                        "a tap in the north edge band of the square at (2,2)",
                        action = Action.Press(tracksEdgeNorth(2, 2)),
                        moves = 1,
                        move = "TU2,2",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap in the centre of the square at (3,3)",
                        action = Action.Press(tracksSquare(3, 3)),
                        moves = 1,
                        move = "TS3,3",
                    ),
                    Step(
                        "a tap in the outer border",
                        action = Action.Press(tracksBorderPoint()),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = DOMINOSA,
                game = Dominosa,
                // The frozen 6db game of seed 12345.
                launch = "6db:36035216262405121355164306536241046314351143260400522054",
                first = listOf(
                    Step(
                        "a tap in the right half of the square at (0,0)",
                        action = Action.Press(dominosaRight(0, 0)),
                        moves = 1,
                        move = "D0,1",
                    ),
                    Step(
                        "the same tap again, which removes the domino",
                        action = Action.Press(dominosaRight(0, 0)),
                        moves = 1,
                        move = "D0,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap in the right half of the square at (1,0)",
                        action = Action.Press(dominosaRight(1, 0)),
                        moves = 1,
                        move = "D1,2",
                    ),
                    Step(
                        "a tap in the border",
                        action = Action.Press(dominosaBorder()),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = MAP,
                game = Map,
                // The frozen 20x15n30dn game of seed 12345. Cell (0,0)
                // is in a clue-coloured region, cell (9,7) is not.
                launch = "20x15n30dn:" +
                    "ddbehafbbafbabccabkaaacabaabccbadahacbcbbbjaaaaaabaaeeaaabdbjcabcaiajcfb" +
                    "dabacblabahcecabzbaiclbaboagbcacdedabaaeacebdcacagaaacaahfbgcaaadefbcabbac" +
                    "aeabbaabaaefbaaabaaffbcodgdjad,212a30a2g22d1a02c21",
                first = listOf(
                    Step(
                        "a drag from the clue region at (0,0) to the region at (9,7)",
                        action = Action.Drag(
                            from = mapCell(0, 0),
                            to = mapCell(9, 7),
                        ),
                        moves = 1,
                        move = "2:13",
                    ),
                    Step(
                        "a tap on the cell at (1,1)",
                        action = Action.Press(mapCell(1, 1)),
                        moves = 0,
                    ),
                ),
                second = listOf(
                    Step(
                        "a drag from the clue region at (2,0) to the region at (7,0)",
                        action = Action.Drag(
                            from = mapCell(2, 0),
                            to = mapCell(7, 0),
                        ),
                        moves = 1,
                        move = "1:3",
                    ),
                    Step(
                        "a tap on the cell at (0,0)",
                        action = Action.Press(mapCell(0, 0)),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = KEEN,
                game = Keen,
                // The frozen 6dn game of seed 12345.
                launch = "6dn:a_4a__a_b_aa_3aa_baa__a3b__b_3a_4," +
                    "a8m12d2d2s1m108m20a7s3a5s3a11s1s2m6",
                first = listOf(
                    Step(
                        "a tap on the cell at (0,0)",
                        action = Action.Press(keenCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "the digit 1 on the highlighted cell",
                        action = Action.Key(KEYCODE_1),
                        moves = 1,
                        move = "R0,0,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the cell at (1,0)",
                        action = Action.Press(keenCell(1, 0)),
                        moves = 0,
                    ),
                    Step(
                        "the digit 2 on the highlighted cell",
                        action = Action.Key(KEYCODE_2),
                        moves = 1,
                        move = "R1,0,2",
                    ),
                    Step(
                        "the digit 9 with no highlighted cell",
                        action = Action.Key(KEYCODE_9),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = SINGLES,
                game = Singles,
                // The frozen 5x5de game of seed 12345.
                launch = "5x5de:1122434125331414135512313",
                first = listOf(
                    Step(
                        "a tap on the cell at (0,0)",
                        action = Action.Press(singlesCell(0, 0)),
                        moves = 1,
                        move = "B0,0",
                    ),
                    Step(
                        "the same tap again, which turns black to circle",
                        action = Action.Press(singlesCell(0, 0)),
                        moves = 1,
                        move = "C0,0",
                    ),
                ),
                second = listOf(
                    Step(
                        "a press held on the cell at (1,0): one right click",
                        action = Action.HoldPress(singlesCell(1, 0)),
                        moves = 1,
                        move = "C1,0",
                    ),
                    Step(
                        "a tap in the border, which toggles the black numbers only",
                        action = Action.Press(singlesBorder()),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = PEARL,
                game = Pearl,
                // The frozen 8x8de game of seed 12345.
                launch = "8x8de:dWaWcWbWWaBhWbWWdBBaWiWeWbBbWd",
                first = listOf(
                    Step(
                        "a tap on the centre of the cell at (3,3)",
                        action = Action.Press(pearlCentre(3, 3)),
                        moves = 0,
                    ),
                    Step(
                        "a tap near the top edge of the cell at (2,2)",
                        action = Action.Press(pearlTopEdge(2, 2)),
                        moves = 1,
                        move = "F4,2,2;F1,1,2",
                    ),
                ),
                second = listOf(
                    Step(
                        "a press held near the top edge of the cell at (3,3)",
                        action = Action.HoldPress(pearlTopEdge(3, 3)),
                        moves = 1,
                        move = "M4,3,3;M1,2,3",
                    ),
                    Step(
                        "a press held near the top edge of the cell at (4,4)",
                        action = Action.HoldPress(pearlTopEdge(4, 4)),
                        moves = 1,
                        move = "M4,4,4;M1,3,4",
                    ),
                ),
            ),
            Case(
                backend = LOOPY,
                game = Loopy,
                // The frozen 7x7t0de game of seed 12345.
                launch = "7x7t0de:a2b2c02c22a32b20a21a032c3c22a21b2a3a1a",
                first = listOf(
                    Step(
                        "a tap in the board corner",
                        action = Action.Press(loopyCorner()),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the first horizontal edge",
                        action = Action.Press(loopyEdge(0)),
                        moves = 1,
                        move = "3y",
                    ),
                    Step(
                        "a tap on the next edge along",
                        action = Action.Press(loopyEdge(1)),
                        moves = 1,
                        move = "1y",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the next edge along at the second tile size",
                        action = Action.Press(loopyEdge(2)),
                        moves = 1,
                        move = "5y",
                    ),
                    Step(
                        "a tap on the next edge after that",
                        action = Action.Press(loopyEdge(3)),
                        moves = 1,
                        move = "8y",
                    ),
                ),
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
