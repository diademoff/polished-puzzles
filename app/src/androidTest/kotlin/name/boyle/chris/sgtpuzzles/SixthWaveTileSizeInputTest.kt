package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent.KEYCODE_1
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
import name.boyle.chris.sgtpuzzles.backend.BRIDGES
import name.boyle.chris.sgtpuzzles.backend.FILLING
import name.boyle.chris.sgtpuzzles.backend.GALAXIES
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.MINES
import name.boyle.chris.sgtpuzzles.backend.UNRULY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Bridges
import name.boyle.chris.sgtpuzzles.engine.Filling
import name.boyle.chris.sgtpuzzles.engine.Galaxies
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Mines
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Unruly
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the sixth enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * The structure is the fifth wave's [FifthWaveTileSizeInputTest]: one
 * activity per game, the first script on the board laid out at the
 * default `limitDpi=auto`, then `limitDpi=off` and the second script on
 * the resized board. Every point comes from the live [BoardGeometry],
 * and the stored game preferences are removed so the C defaults decide
 * the gesture.
 *
 * The C input code decides each gesture, and the scripts include the
 * inert ones so the delta is honest:
 * - `galaxies` left-clicks an edge (the square centre rounds to the
 *   edge below it). A click on an occupied square is inert, and so is
 *   a right press in the board corner, where no dot is near. The arrow
 *   needs a right drag: a press held past the shell's long-press
 *   timeout becomes `RIGHT_BUTTON`, the move `RIGHT_DRAG`, and the
 *   release `RIGHT_RELEASE`.
 * - `bridges` drags from an island to the next island in that
 *   direction. A click on an empty cell is inert. A release on the
 *   press point of an island is the C lock move.
 * - `unruly` left-clicks a cell. A click in the half-tile border is
 *   outside the grid.
 * - `filling` left-clicks a region to select it and then takes the
 *   digit key of the value. A click in the half-tile border selects
 *   nothing, so the digit after it is inert. The key goes through
 *   `GamePlay.sendKey`, the same call the on-screen key row makes.
 * - `mines` opens a tile on a left click and flags a covered tile on a
 *   press held past the long-press timeout. A tap in the border is
 *   outside the grid.
 *
 * Every step asserts the move count and whether the save text changed,
 * and a move step asserts its exact `MOVE` record. The save comparison
 * drops the `TIME` record: `mines` is the only timed game, so its clock
 * rewrites that record between two reads even when nothing happened.
 */
@RunWith(Parameterized::class)
class SixthWaveTileSizeInputTest(private val case: Case) {

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
        /** The same hold, then a move and a release: a right drag. */
        data class HoldDrag(
            val from: (BoardGeometry) -> Tick,
            val to: (BoardGeometry) -> Tick,
        ) : Action
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
        // A stored preference would change the gesture: bridges reads the
        // `H` hint key and mines reads nothing. The test pins the C
        // defaults.
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
                is Action.HoldDrag -> holdDrag(board, action.from(board), action.to(board))
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
        onView(withId(R.id.game_view)).perform(holdGesture(board, listOf(at, at)))
    }

    /** A hold that becomes a right drag, so the game sees a right drag. */
    private fun holdDrag(board: BoardGeometry, from: Tick, to: Tick) {
        onView(withId(R.id.game_view)).perform(holdGesture(board, listOf(from, to)))
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
     * A press held past the shell's long-press timeout, then a move to
     * the last [points] entry and a release. `loopMainThreadForAtLeast`
     * lets the main looper run the `postDelayed` that turns the hold
     * into `RIGHT_BUTTON`, so the shell path is the real one.
     */
    private fun holdGesture(board: BoardGeometry, points: List<Tick>): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayingAtLeast(90)

            override fun getDescription(): String = "a long press on the board"

            override fun perform(uiController: UiController, view: View) {
                val screen = points.map { screenPoint(view, board, it) }
                val downTime = SystemClock.uptimeMillis()
                uiController.injectMotionEvent(
                    event(MotionEvent.ACTION_DOWN, downTime, downTime, screen.first()),
                )
                uiController.loopMainThreadForAtLeast(HOLD_MILLIS)
                for (next in screen.drop(1)) {
                    val time = SystemClock.uptimeMillis()
                    uiController.injectMotionEvent(
                        event(MotionEvent.ACTION_MOVE, downTime, time, next),
                    )
                    uiController.loopMainThreadForAtLeast(STEP_MILLIS)
                }
                val upTime = SystemClock.uptimeMillis()
                uiController.injectMotionEvent(
                    event(MotionEvent.ACTION_UP, downTime, upTime, screen.last()),
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
     * The save text without the `TIME` record. A timed game rewrites
     * that record on every tick, so an inert gesture still changes the
     * raw text; every other record is stable.
     */
    private fun liveStableSave(scenario: ActivityScenario<GamePlay>): String =
        liveSaveText(scenario).lineSequence()
            .filterNot { it.startsWith("TIME") }
            .joinToString("\n")

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
         * A point inside a `galaxies` square, a quarter tile below its
         * centre. C uses `BORDER = T` and `SCOORD(x) = x*T/2 + BORDER`,
         * and the square of cell `c` is space `2c+1`. The exact centre
         * is a tie between the horizontal edge below the square and the
         * vertical edge beside it (`dx == dy` in `coord_round_to_edge`),
         * so one pixel of view rounding would pick either one. The
         * offset keeps the horizontal edge at `E(2c+1, 2r+2)`, and the
         * same point still drops an arrow on the square `(2c+1, 2r+1)`.
         */
        private fun galaxiesSquare(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((2 * c + 1) * t / 2 + t, (2 * r + 1) * t / 2 + t + t / 4)
        }

        /**
         * A point that picks up a `galaxies` dot. The right-button press
         * searches the space grid around `FROMCOORD` for a dot, and
         * `(c+1)*T` is the point that reaches the dot this test expects:
         * seed 12345 at the default preset, dot `(3,1)` for `(1,0)` and
         * dot `(5,3)` for `(2,1)`.
         */
        private fun galaxiesDot(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /** The board corner, where no `galaxies` dot is near. */
        private fun galaxiesCorner(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        /**
         * The centre of a `bridges` cell. C uses `BORDER = T/2` and
         * `COORD(x) = x*T + BORDER`.
         */
        private fun bridgesCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /**
         * The centre of an `unruly` cell. C uses
         * `COORD(x) = x*T + T/2`.
         */
        private fun unrulyCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(c * t + t / 2, r * t + t / 2)
        }

        /** A point in the `unruly` half-tile border: outside the grid. */
        private fun unrulyBorder(): (BoardGeometry) -> Tick = { board ->
            Tick(2, board.tileSize / 4)
        }

        /**
         * The centre of a `filling` cell. C uses `BORDER = T/2` and
         * `tx = (x + T - BORDER)/T - 1`.
         */
        private fun fillingCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /** A point in the `filling` half-tile border: no cell. */
        private fun fillingBorder(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        /**
         * The centre of a `mines` cell. The Android build compiles with
         * `SMALL_SCREEN`, so C uses the constant `BORDER = 8` and
         * `COORD(x) = x*T + 8`.
         */
        private fun minesCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(c * t + 8 + t / 2, r * t + 8 + t / 2)
        }

        /** A point in the `mines` border: outside the grid. */
        private fun minesBorder(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        private val cases = listOf(
            Case(
                backend = GALAXIES,
                game = Galaxies,
                // The frozen 7x7 Normal game of seed 12345. The square
                // below (0,1) holds a dot, so that edge is not legal.
                launch = "7x7dn:cjrbhizuisfvf",
                first = listOf(
                    Step(
                        "a click on the square at (0,1)",
                        action = Action.Press(galaxiesSquare(0, 1)),
                        moves = 0,
                    ),
                    Step(
                        "a click on the square at (0,0)",
                        action = Action.Press(galaxiesSquare(0, 0)),
                        moves = 1,
                        move = "E1,2",
                    ),
                ),
                second = listOf(
                    Step(
                        "a right drag from the dot at (2,1) to the cell at (2,0)",
                        action = Action.HoldDrag(
                            from = galaxiesDot(2, 1),
                            to = galaxiesSquare(2, 0),
                        ),
                        moves = 1,
                        move = "A5,1,5,3",
                    ),
                    Step(
                        "a press held in the board corner",
                        action = Action.HoldPress(galaxiesCorner()),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = BRIDGES,
                game = Bridges,
                // The frozen 7x7 Easy game of seed 12345. Islands sit at
                // (1,0), (4,0), (3,1), (6,1), (1,3), and (5,3); the drag
                // resolves to the next island in its direction.
                launch = "7x7i30e10m2d1:a3b1e2b32g3a4a4a4a1i2a2b4b3",
                first = listOf(
                    Step(
                        "a click on the empty cell at (0,0)",
                        action = Action.Press(bridgesCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "a drag from the island at (1,0) to the cell at (2,0)",
                        action = Action.Drag(
                            from = bridgesCell(1, 0),
                            to = bridgesCell(2, 0),
                        ),
                        moves = 1,
                        move = "L1,0,4,0,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "the same drag to the south at the second tile size",
                        action = Action.Drag(
                            from = bridgesCell(1, 0),
                            to = bridgesCell(1, 1),
                        ),
                        moves = 1,
                        move = "L1,0,1,3,1",
                    ),
                    Step(
                        "a click on the island at (4,0), the C lock",
                        action = Action.Press(bridgesCell(4, 0)),
                        moves = 1,
                        move = "M4,0",
                    ),
                ),
            ),
            Case(
                backend = UNRULY,
                game = Unruly,
                // The frozen 8x8 Trivial game of seed 12345. Cell (0,0)
                // is empty and cell (1,0) already holds its value.
                launch = "8x8dt:bEACHcBdBDEalcbAbbBa",
                first = listOf(
                    Step(
                        "a click in the half-tile border",
                        action = Action.Press(unrulyBorder()),
                        moves = 0,
                    ),
                    Step(
                        "a click on the empty cell at (0,0)",
                        action = Action.Press(unrulyCell(0, 0)),
                        moves = 1,
                        move = "P1,0,0",
                    ),
                ),
                second = listOf(
                    Step(
                        "the cell click at the second tile size",
                        action = Action.Press(unrulyCell(1, 1)),
                        moves = 1,
                        move = "P1,1,1",
                    ),
                ),
            ),
            Case(
                backend = FILLING,
                game = Filling,
                // The frozen 9x7 game of seed 12345.
                launch = "9x7:a8a8a466a2b8b66a2a7777665d7b9e79c299b9c6c5b55",
                first = listOf(
                    Step(
                        "a click in the half-tile border",
                        action = Action.Press(fillingBorder()),
                        moves = 0,
                    ),
                    Step(
                        "the digit 1 with nothing selected",
                        action = Action.Key(KEYCODE_1),
                        moves = 0,
                    ),
                    Step(
                        "a click on the cell at (0,0) and the digit 1",
                        action = Action.Press(fillingCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "the digit 1 on the selected cell",
                        action = Action.Key(KEYCODE_1),
                        moves = 1,
                        move = "0_1",
                    ),
                ),
                second = listOf(
                    Step(
                        "the cell click at the second tile size",
                        action = Action.Press(fillingCell(1, 1)),
                        moves = 0,
                    ),
                    Step(
                        "the digit 1 on the selected cell",
                        action = Action.Key(KEYCODE_1),
                        moves = 1,
                        move = "10_1",
                    ),
                ),
            ),
            Case(
                backend = MINES,
                game = Mines,
                // The frozen 9x9 10-mine game of seed 12345. The
                // description is ready, so the first click opens a tile
                // without a new generation.
                launch = "9x9n10:r10,u,8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e8908980d443c29c69b60c9caefef1451662ab6b8c65888654a119724096b8402",
                first = listOf(
                    Step(
                        "a tap in the border",
                        action = Action.Press(minesBorder()),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the covered tile at (0,0)",
                        action = Action.Press(minesCell(0, 0)),
                        moves = 1,
                        move = "O0,0",
                    ),
                ),
                second = listOf(
                    Step(
                        "a press held on the covered tile at (8,8)",
                        action = Action.HoldPress(minesCell(8, 8)),
                        moves = 1,
                        move = "F8,8",
                    ),
                ),
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
