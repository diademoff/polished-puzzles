package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import name.boyle.chris.sgtpuzzles.backend.BLACKBOX
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.LIGHTUP
import name.boyle.chris.sgtpuzzles.backend.RECT
import name.boyle.chris.sgtpuzzles.backend.UNDEAD
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BlackBox
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Rect
import name.boyle.chris.sgtpuzzles.engine.Undead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the third enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * One activity per game: the first script runs on the board laid out at
 * the default `limitDpi=auto`, then `limitDpi=off` resizes the same
 * game and the second script runs on the new board. Every point is
 * computed from the live [BoardGeometry], so the cells come from the
 * same tile size and origin the drawing layer uses.
 *
 * The C input code decides each gesture, and a script includes an inert
 * gesture where the game has one, so the delta is honest:
 * - `blackbox` fires a laser with a tap on a top range cell, one move
 *   per unfired laser. Every range-cell tap fires, so it contributes
 *   no inert gesture. Its shell drag mode is `PREVENT`.
 * - `lightup` cycles a cell with a tap: one move for a light. A tap in
 *   the half-tile frame has no cell and is inert.
 * - `undead` has no `UI` record. A tap on a cell only highlights it, so
 *   it changes no move and no save text; a tap on a clue toggles the
 *   mark and adds one move.
 * - `rect` resolves a tap to a corner, an edge, or a square centre. A
 *   square centre is inert; an edge centre toggles that edge and adds
 *   one move.
 *
 * Every step asserts the move count and whether the save text changed.
 * An inert step also holds its save text for a bounded settle window,
 * so a delayed move cannot pass as no move. The status line is
 * asserted where the game has one.
 */
@RunWith(Parameterized::class)
class ThirdWaveTileSizeInputTest(private val case: Case) {

    /** A point in game coordinates, measured from the board origin. */
    data class Tick(val x: Int, val y: Int)

    /**
     * One tap. [moves] is the expected change of the move count.
     * [changesSave] is true when the save text must change, which is the
     * case exactly when the tap made a move: none of these four games
     * stores a `UI` record.
     */
    data class Step(
        val description: String,
        val at: (BoardGeometry) -> Tick,
        val moves: Int = 0,
        val changesSave: Boolean = moves != 0,
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
            tap(board, step.at(board))
            total += step.moves
            awaitMoveCount(scenario, total)
            if (step.changesSave) {
                assertNotEquals(
                    "${step.description} must change the save",
                    before,
                    liveSaveText(scenario),
                )
            } else {
                // An inert gesture returns at once, so it needs a bounded settle.
                GamePlayTestSupport.awaitInertGesture(scenario)
                assertEquals(
                    "${step.description} must not change the save",
                    before,
                    liveSaveText(scenario),
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
         * A top range cell of `blackbox`. `TODRAW(x) = x*T + T/2` and
         * its centre adds `T/2`, so range cell [gx] starts at
         * `(gx + 1) * T`. Row 0 is `T`.
         */
        private fun laser(gx: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((gx + 1) * t, t)
        }

        /**
         * The centre of a `lightup` cell. `COORD(x) = x*T + T/2` with a
         * border of `T/2`, so cell [c] spans `(c + T/2, c + 3T/2)` and
         * its centre is `(c + 1) * T`.
         */
        private fun lightupCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /** A point in the half-tile frame of `lightup`: no cell there. */
        private fun lightupFrame(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2 - 1, t / 2 + 1)
        }

        /**
         * The centre of an `undead` cell. The C layout is
         * `x*T + T/4 + T/2` and `y*T + T/4 + 3T/2`, from the input
         * parity test, so the clue row is row 0.
         */
        private fun undeadCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 4 + c * t + t / 2, t / 4 + r * t + 3 * t / 2)
        }

        /**
         * The centre of the top edge of `rect` square (c, r):
         * `COORD(x) = x*T + 2` with `SMALL_SCREEN`, and `coord_round`
         * maps an edge midpoint to an `H` move. The point stays in
         * integers, so an odd tile truncates `T/2` as the C
         * `FROMCOORD` integer division does.
         */
        private fun rectEdge(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(2 + c * t + t / 2, 2 + r * t)
        }

        /** The centre of a `rect` square: a corner, edge, or centre click. */
        private fun rectSquareCentre(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(2 + c * t + t / 2, 2 + r * t + t / 2)
        }

        private val cases = listOf(
            Case(
                backend = BLACKBOX,
                game = BlackBox,
                // The board of `GamePlayParameterizedTest`, so the id is
                // known to load and to leave the game open.
                launch = "w3h3m1M1:38727296",
                first = listOf(
                    Step(
                        "a tap on the first top range cell",
                        at = laser(1),
                        moves = 1,
                        status = "Balls marked: 0 / 1",
                    ),
                ),
                second = listOf(
                    Step(
                        "the laser tap at the second tile size",
                        at = laser(2),
                        moves = 1,
                        status = "Balls marked: 0 / 1",
                    ),
                ),
            ),
            Case(
                backend = LIGHTUP,
                game = Lightup,
                // The clue-less 3x3 board of `LightupInputParityTest`;
                // the two lights stay off the winning diagonal.
                launch = "3x3b20s3d0:i",
                first = listOf(
                    Step(
                        "a tap in the frame with no cell",
                        at = lightupFrame(),
                        moves = 0,
                    ),
                    Step(
                        "a tap on a white cell",
                        at = lightupCell(2, 0),
                        moves = 1,
                    ),
                ),
                second = listOf(
                    Step(
                        "the light tap at the second tile size",
                        at = lightupCell(1, 1),
                        moves = 1,
                    ),
                ),
            ),
            Case(
                backend = UNDEAD,
                game = Undead,
                // The frozen 4x4 Normal game of seed 12345. Cell (1,0)
                // is a clue, so a press toggles its mark.
                launch = "4x4:1,5,6,bRdRLbRd,2,4,2,4,3,2,0,4,1,3,4,1,4,0,3,2",
                first = listOf(
                    Step(
                        "a tap on a cell only highlights it",
                        at = undeadCell(1, 1),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the first clue",
                        at = undeadCell(1, 0),
                        moves = 1,
                    ),
                ),
                second = listOf(
                    Step(
                        "the clue tap at the second tile size toggles it off",
                        at = undeadCell(1, 0),
                        moves = 1,
                    ),
                ),
            ),
            Case(
                backend = RECT,
                game = Rect,
                // The frozen 7x7 game of seed 12345. The board starts
                // with no player edges, so both edge taps add one.
                launch = "7x7:3_2a2_3g2f5_2a6a3_3b2_2d4b3_2c5f",
                first = listOf(
                    Step(
                        "a tap on a square centre draws nothing",
                        at = rectSquareCentre(2, 2),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the top edge of that square",
                        at = rectEdge(2, 2),
                        moves = 1,
                    ),
                ),
                second = listOf(
                    Step(
                        "the edge tap at the second tile size",
                        at = rectEdge(3, 3),
                        moves = 1,
                    ),
                ),
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
