package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_2
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.withId
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.RANGE
import name.boyle.chris.sgtpuzzles.backend.SLANT
import name.boyle.chris.sgtpuzzles.backend.TOWERS
import name.boyle.chris.sgtpuzzles.backend.UNEQUAL
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Range
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Slant
import name.boyle.chris.sgtpuzzles.engine.Towers
import name.boyle.chris.sgtpuzzles.engine.Unequal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the fourth enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * One activity per game: the first script runs on the board laid out at
 * the default `limitDpi=auto`, then `limitDpi=off` resizes the same
 * game and the second script runs on the new board. Every point is
 * computed from the live [BoardGeometry], so the cells come from the
 * same tile size and origin the drawing layer uses.
 *
 * The C input code decides each gesture, and the scripts include the
 * inert ones so the delta is honest:
 * - `range` left-clicks an empty cell to black. A clue click is inert.
 * - `slant` left-clicks a square and cycles it to `\`. A tap in the
 *   outer border has no square and is inert.
 * - `towers` and `unequal` select a cell with a tap and then take a
 *   digit from the shell's real key input. A tap in the outer border
 *   deselects and is inert.
 *
 * Every step asserts the move count and whether the save text changed,
 * and a move step asserts its exact `MOVE` record.
 */
@RunWith(Parameterized::class)
class FourthWaveTileSizeInputTest(private val case: Case) {

    /** A point in game coordinates, measured from the board origin. */
    data class Tick(val x: Int, val y: Int)

    /** A real gesture: a tap at a board point, or a real hardware key. */
    sealed interface Action {
        data class Press(val at: (BoardGeometry) -> Tick) : Action
        data class Key(val code: Int) : Action
    }

    /**
     * One gesture. [moves] is the expected change of the move count.
     * [changesSave] is true when the save text must change, which is the
     * case exactly when the gesture made a move: none of these four
     * games stores a `UI` record.
     */
    data class Step(
        val description: String,
        val action: Action,
        val moves: Int = 0,
        val changesSave: Boolean = moves != 0,
        val move: String? = null,
        val status: String? = null,
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
    fun theTapsUseTheSizedBoard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        GamePlayTestSupport.clearBackendState(context, case.backend)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val originalLimitDpi = prefs.getString(LIMIT_DPI_KEY, "auto")
        val originalGamePrefs = prefs.getString(case.backend.preferencesName, null)
        // A stored swap preference would change which button cycles the
        // cell. The test pins the C defaults.
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
            val before = liveSaveText(scenario)
            when (val action = step.action) {
                is Action.Press -> tap(board, action.at(board))
                is Action.Key -> press(action.code)
            }
            total += step.moves
            awaitMoveCount(scenario, total)
            if (step.changesSave) {
                assertNotEquals(
                    "${step.description} must change the save",
                    before,
                    liveSaveText(scenario),
                )
            } else {
                assertEquals(
                    "${step.description} must not change the save",
                    before,
                    liveSaveText(scenario),
                )
            }
            step.move?.let {
                assertEquals(
                    "${step.description} wrote the wrong move",
                    it,
                    lastMove(scenario),
                )
            }
            step.status?.let { assertStatus(scenario, it) }
        }
        return total
    }

    /** A real tap at [tick], the centre of a cell, band, or button. */
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

    /** A real key, as the shell's hardware keyboard path receives it. */
    private fun press(code: Int) {
        onView(withId(R.id.game_view)).perform(ViewActions.pressKey(code))
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

    private fun assertStatus(scenario: ActivityScenario<GamePlay>, expected: String) {
        scenario.onActivity {
            val text = GamePlayTestSupport.statusText(it)
            assertTrue("the status '$text' does not contain '$expected'", text.contains(expected))
        }
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

        /**
         * The centre of a `range` cell. `CELL = T/2` and `COORD(x) =
         * x*T + T/2`, so cell (c, r) centres at `(c + 1) * T`.
         */
        private fun rangeCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /**
         * The centre of a `slant` square. C uses `BORDER = T` and
         * `COORD(x) = x*T + T`, so cell (c, r) centres at
         * `(c + 1) * T + T/2`.
         */
        private fun slantCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t + t / 2, (r + 1) * t + t / 2)
        }

        /** A point in the `slant` outer border: no square there. */
        private fun slantBorder(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2, t / 2)
        }

        /**
         * The centre of a `towers` cell. The clue border is `T*9/8`,
         * and the cell centre adds `T/2`.
         */
        private fun towersCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val border = t * 9 / 8
            Tick(border + c * t + t / 2, border + r * t + t / 2)
        }

        /** A point in the `towers` outer border: no cell and no clue. */
        private fun towersBorder(): (BoardGeometry) -> Tick = { _ -> Tick(2, 2) }

        /**
         * The centre of an `unequal` cell. The border and the gap are
         * `T/2`, so a cell is `T + gap` wide and starts at `border`.
         */
        private fun unequalCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val border = t / 2
            val square = t + border
            Tick(border + c * square + t / 2, border + r * square + t / 2)
        }

        /** A point in the `unequal` outer border: no cell there. */
        private fun unequalBorder(): (BoardGeometry) -> Tick = { _ -> Tick(2, 2) }

        private val cases = listOf(
            Case(
                backend = RANGE,
                game = Range,
                // The frozen 9x6 game of seed 12345. Cell (0,0) is a
                // clue; cells (2,0) and (3,0) are empty.
                launch = "9x6:7_5e4d5g8l7g9d11e8_13",
                first = listOf(
                    Step(
                        "a tap on the clue at (0,0)",
                        action = Action.Press(rangeCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "a left tap on the empty cell at (2,0)",
                        action = Action.Press(rangeCell(2, 0)),
                        moves = 1,
                        move = "B,0,2",
                    ),
                ),
                second = listOf(
                    Step(
                        "the left tap at the second tile size",
                        action = Action.Press(rangeCell(3, 0)),
                        moves = 1,
                        move = "B,0,3",
                    ),
                ),
            ),
            Case(
                backend = SLANT,
                game = Slant,
                // The frozen 5x5 Easy game of seed 12345.
                launch = "5x5de:a2d1b2a2b3d3b2b21a1c1110",
                first = listOf(
                    Step(
                        "a tap in the outer border",
                        action = Action.Press(slantBorder()),
                        moves = 0,
                    ),
                    Step(
                        "a left tap on the square at (0,0)",
                        action = Action.Press(slantCell(0, 0)),
                        moves = 1,
                        move = "\\0,0",
                    ),
                ),
                second = listOf(
                    Step(
                        "the square tap at the second tile size",
                        action = Action.Press(slantCell(1, 1)),
                        moves = 1,
                        move = "\\1,1",
                    ),
                ),
            ),
            Case(
                backend = TOWERS,
                game = Towers,
                // The frozen 5x5 Easy game of seed 12345. Every cell is
                // empty, so a digit fills the selected cell.
                launch = "5de:3/2/2/2/1/2/4/3/1/2/5/2/3/1/2/1/2/3/3/2",
                first = listOf(
                    Step(
                        "a tap in the outer border",
                        action = Action.Press(towersBorder()),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the cell at (0,0)",
                        action = Action.Press(towersCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "the real digit 1 for the selected cell",
                        action = Action.Key(KEYCODE_1),
                        moves = 1,
                        move = "R0,0,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the cell at (1,1)",
                        action = Action.Press(towersCell(1, 1)),
                        moves = 0,
                    ),
                    Step(
                        "the real digit 2 at the second tile size",
                        action = Action.Key(KEYCODE_2),
                        moves = 1,
                        move = "R1,1,2",
                    ),
                ),
            ),
            Case(
                backend = UNEQUAL,
                game = Unequal,
                // The 3x3 game of `GamePlayParameterizedTest`. Every
                // cell is empty, so a digit fills the selected cell.
                launch = "3:0D,0,0,0,0,0,0R,0,0U,",
                first = listOf(
                    Step(
                        "a tap in the outer border",
                        action = Action.Press(unequalBorder()),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the cell at (0,0)",
                        action = Action.Press(unequalCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "the real digit 1 for the selected cell",
                        action = Action.Key(KEYCODE_1),
                        moves = 1,
                        move = "R0,0,1",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the cell at (1,1)",
                        action = Action.Press(unequalCell(1, 1)),
                        moves = 0,
                    ),
                    Step(
                        "the real digit 2 at the second tile size",
                        action = Action.Key(KEYCODE_2),
                        moves = 1,
                        move = "R1,1,2",
                    ),
                ),
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
