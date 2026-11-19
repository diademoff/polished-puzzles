package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent.KEYCODE_2
import android.view.KeyEvent.KEYCODE_4
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
import name.boyle.chris.sgtpuzzles.backend.CUBE
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.SOLO
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Solo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the eighth enablement wave, `solo`
 * and `cube`, at two tile sizes, neither of them the game's preferred
 * tile.
 *
 * The structure is [SeventhWaveTileSizeInputTest]: one activity per
 * game, the first script on the board laid out at the default
 * `limitDpi=auto`, then `limitDpi=off` and the second script on the
 * resized board. Every point comes from the live [BoardGeometry], and
 * the stored game preferences are removed so the C defaults decide the
 * gesture.
 *
 * Every case loads a frozen game ID, so the suite generates no board at
 * all and therefore cannot generate one above the D-036 cap. The cap
 * itself is pinned in the engine and parity tests.
 *
 * The C input code decides each gesture, and the scripts include the
 * inert ones so the delta is honest:
 * - `solo` taps a cell, which highlights it without a move, and the
 *   numerals come from the keypad. A tap on a given clears the
 *   highlight and is inert.
 * - `cube` taps a point, which rolls the die in the bearing of the tap.
 *   A tap half a cell left of the die on the left edge has no square
 *   beyond it, so it is inert. The arrow keys and the keypad directions
 *   are the same rolls on the key path.
 *
 * Every step asserts the move count and whether the save text changed,
 * and a move step asserts its exact `MOVE` record.
 */
@RunWith(Parameterized::class)
class EighthWaveTileSizeInputTest(private val case: Case) {

    /** A point in game coordinates, measured from the board origin. */
    data class Tick(val x: Int, val y: Int)

    /** A real gesture on the board, or a real key. */
    sealed interface Action {
        data class Press(val at: (BoardGeometry) -> Tick) : Action
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
        // A stored preference would change the gesture: solo reads
        // `pencil-keep-highlight`. The test pins the C defaults.
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

    /**
     * A real key, as the shell's on-screen key row sends it. The key row
     * is a Compose view, so this calls the same entry point the button
     * does instead of tapping the button.
     */
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

    /** The save text without the `TIME` record, as the earlier waves do. */
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

        /**
         * The centre of a `solo` cell. C uses `BORDER = T/2`, and
         * `interpret_move` reads `(x + T/2)/T - 1`, so the cell at
         * (x, y) spans `T/2 + x*T` and its centre is `(x+1)*T`.
         */
        private fun soloCell(x: Int, y: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((x + 1) * t, (y + 1) * t)
        }

        /**
         * The centre of a `cube` square on the cube solid. `XSIZE` puts
         * the grid origin one tile in, and a square spans one tile, so
         * its centre is `(x+1)*T`.
         */
        private fun cubeCell(x: Int, y: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((x + 1) * t, (y + 1) * t)
        }

        /**
         * A point half a tile left of the die at (0, 1). The bearing is
         * west and the die has no square beyond the left edge, so
         * `interpret_move` is inert.
         */
        private fun cubeLeftEdge(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2, 2 * t)
        }

        private val cases = listOf(
            Case(
                backend = SOLO,
                game = Solo,
                // The frozen 2x2 game of seed 12345: a 4x4 grid, with
                // givens at (0,0), (2,0), (1,3) and (3,3).
                launch = "2x2:3a2j1a3",
                first = listOf(
                    Step(
                        "a tap on the given cell at (0,0)",
                        action = Action.Press(soloCell(0, 0)),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the free cell at (1,0)",
                        action = Action.Press(soloCell(1, 0)),
                        moves = 0,
                    ),
                    Step(
                        "the digit 4 on the highlighted cell",
                        action = Action.Key(KEYCODE_4),
                        moves = 1,
                        move = "R1,0,4",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap on the free cell at (2,1)",
                        action = Action.Press(soloCell(2, 1)),
                        moves = 0,
                    ),
                    Step(
                        "the digit 2 on the highlighted cell",
                        action = Action.Key(KEYCODE_2),
                        moves = 1,
                        move = "R2,1,2",
                    ),
                    Step(
                        "a tap on the given cell at (3,3)",
                        action = Action.Press(soloCell(3, 3)),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = CUBE,
                game = Cube,
                // The frozen 4x4 cube of seed 12345. The die starts on
                // square 4, which is column 0, row 1.
                launch = "c4x4:205E,4",
                first = listOf(
                    Step(
                        "a tap half a cell left of the die, on the left edge",
                        action = Action.Press(cubeLeftEdge()),
                        moves = 0,
                    ),
                    Step(
                        "a tap two squares right of the die",
                        action = Action.Press(cubeCell(2, 1)),
                        moves = 1,
                        move = "R",
                    ),
                ),
                second = listOf(
                    Step(
                        "a tap two squares below the die",
                        action = Action.Press(cubeCell(1, 3)),
                        moves = 1,
                        move = "D",
                    ),
                    Step(
                        "a tap two squares right of the die",
                        action = Action.Press(cubeCell(3, 2)),
                        moves = 1,
                        move = "R",
                    ),
                ),
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
