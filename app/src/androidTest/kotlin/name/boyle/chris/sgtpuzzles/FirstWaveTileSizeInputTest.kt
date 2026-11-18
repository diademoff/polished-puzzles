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
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.FLOOD
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.PEGS
import name.boyle.chris.sgtpuzzles.backend.SAMEGAME
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Flood
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.SameGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the first enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * One activity per game: the first board is laid out at the default
 * `limitDpi=auto`, the first tap runs there, then `limitDpi=off`
 * resizes the same game and the swipe and the optional second tap run
 * on the second board. The geometry is read from the live engine, so
 * the cells are computed from the same tile size the drawing layer
 * uses.
 *
 * The shell's `dragMode` decides which gestures reach the game.
 * `samegame` and `flood` are cursor games with `dragMode = PREVENT`:
 * their swipe is a real swipe that the shell ignores, and the tap is
 * their move gesture. `samegame` needs two taps for a removal, the
 * first selects and the second removes. `flood` moves on every tap
 * whose cell colour differs from the controlled colour. `pegs` has
 * `dragMode = REVERT_OFF_SCREEN` and moves only on a release two cells
 * away, so its tap cancels and its swipe makes the move.
 *
 * Every case asserts the move count before and after each gesture and
 * that the save text changes exactly when a move was applied.
 */
@RunWith(Parameterized::class)
class FirstWaveTileSizeInputTest(private val case: Case) {

    data class Cell(val x: Int, val y: Int)

    data class Case(
        val backend: BackendName,
        val game: Game,
        val launch: String,
        val tap: Cell,
        val tapMoves: Int,
        val tapStatus: String?,
        val swipeFrom: Cell,
        val swipeTo: Cell,
        val swipeMoves: Int,
        val swipeStatus: String?,
        val secondTap: Cell?,
        val secondTapMoves: Int,
        val finalStatus: String?,
    ) {
        override fun toString(): String = backend.toString()
    }

    @Test
    fun tapAndSwipeUseTheSizedBoard() {
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
                val before = liveSaveText(scenario)

                tap(scenario, first, case.tap)
                assertMoveDelta(scenario, before, case.tapMoves, "the first tap")
                case.tapStatus?.let { assertStatus(scenario, it) }

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
                val beforeSwipe = liveSaveText(scenario)

                onView(withId(R.id.game_view)).perform(
                    GeneralSwipeAction(
                        Swipe.SLOW,
                        cellCentre(second, case.swipeFrom),
                        cellCentre(second, case.swipeTo),
                        Press.FINGER,
                    ),
                )
                assertMoveDelta(
                    scenario,
                    beforeSwipe,
                    case.swipeMoves,
                    "the swipe",
                    expectedTotal = case.tapMoves + case.swipeMoves,
                )
                case.swipeStatus?.let { assertStatus(scenario, it) }

                case.secondTap?.let { cell ->
                    val beforeSecondTap = liveSaveText(scenario)
                    tap(scenario, second, cell)
                    assertMoveDelta(
                        scenario,
                        beforeSecondTap,
                        case.secondTapMoves,
                        "the second tap",
                        expectedTotal = case.tapMoves + case.swipeMoves + case.secondTapMoves,
                    )
                    case.finalStatus?.let { assertStatus(scenario, it) }
                }
            }
        } finally {
            if (originalLimitDpi == null) {
                prefs.edit().remove(LIMIT_DPI_KEY).commit()
            } else {
                prefs.edit().putString(LIMIT_DPI_KEY, originalLimitDpi).commit()
            }
        }
    }

    /**
     * Performs a real tap and checks that it changed the save exactly
     * when it made a move. A tap that makes no move may still change
     * the UI (a selection, a started drag), which the save does not
     * record.
     */
    private fun tap(scenario: ActivityScenario<GamePlay>, board: BoardGeometry, cell: Cell) {
        onView(withId(R.id.game_view)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                cellCentre(board, cell),
                Press.FINGER,
                0,
                0,
            ),
        )
    }

    /**
     * Waits for [delta] more moves and checks that the save changed
     * exactly when [delta] is not zero.
     */
    private fun assertMoveDelta(
        scenario: ActivityScenario<GamePlay>,
        before: String,
        delta: Int,
        gesture: String,
        expectedTotal: Int = delta,
    ) {
        awaitMoveCount(scenario, expectedTotal)
        if (delta == 0) {
            // An inert gesture returns at once, so it needs a bounded settle.
            GamePlayTestSupport.awaitInertGesture(scenario)
            assertEquals(
                "$gesture made no move, so it must not rewrite the save",
                before,
                liveSaveText(scenario),
            )
        } else {
            assertNotEquals("$gesture must change the board", before, liveSaveText(scenario))
        }
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
     * The cell centre in view coordinates: the board is centred in the
     * view, and the game draws tile `(x, y)` at `border + x * tile`.
     *
     * The engine works in device-independent pixels, so the game
     * coordinate is multiplied by the density the view used for this
     * layout. `wDip` and the view width give that density exactly,
     * including `limitDpi=off`, where it is 1.
     */
    private fun cellCentre(board: BoardGeometry, cell: Cell): CoordinatesProvider =
        CoordinatesProvider { view: View ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val gameView = view as GameView
            val density = view.width.toFloat() / gameView.wDip
            val border = board.tileSize / 2
            floatArrayOf(
                location[0] +
                    density * (board.originX + border + cell.x * board.tileSize + board.tileSize / 2f),
                location[1] +
                    density * (board.originY + border + cell.y * board.tileSize + board.tileSize / 2f),
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

        private val cases = listOf(
            Case(
                backend = SAMEGAME,
                game = SameGame,
                // A top row of one colour. The shell blocks drags for
                // this game, so the first tap selects the group, the
                // swipe is ignored, and the second tap removes.
                launch = "3x3c3s2:1,1,1,2,2,2,3,3,3",
                tap = Cell(0, 0),
                tapMoves = 0,
                tapStatus = "Selected: 3",
                swipeFrom = Cell(0, 0),
                swipeTo = Cell(0, 1),
                swipeMoves = 0,
                swipeStatus = null,
                secondTap = Cell(0, 0),
                secondTapMoves = 1,
                finalStatus = "Score: 1",
            ),
            Case(
                backend = FLOOD,
                game = Flood,
                // Every cell has its own colour, so the taps cannot
                // complete the board and the swipe stays available.
                launch = "3x3:012345678,20",
                tap = Cell(1, 0),
                tapMoves = 1,
                tapStatus = "1 / 20 moves",
                swipeFrom = Cell(2, 2),
                swipeTo = Cell(2, 1),
                swipeMoves = 0,
                swipeStatus = null,
                secondTap = Cell(0, 1),
                secondTapMoves = 1,
                finalStatus = "2 / 20 moves",
            ),
            Case(
                backend = PEGS,
                game = Pegs,
                // 5x7 cross: (2,1) over (2,2) into the centre hole at
                // (2,3). A plain tap starts a drag and cancels it.
                launch = "5x7cross",
                tap = Cell(2, 1),
                tapMoves = 0,
                tapStatus = null,
                swipeFrom = Cell(2, 1),
                swipeTo = Cell(2, 3),
                swipeMoves = 1,
                swipeStatus = null,
                secondTap = null,
                secondTapMoves = 0,
                finalStatus = null,
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
