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
import name.boyle.chris.sgtpuzzles.backend.FLIP
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.SIXTEEN
import name.boyle.chris.sgtpuzzles.backend.TWIDDLE
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Flip
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Guess
import name.boyle.chris.sgtpuzzles.engine.Sixteen
import name.boyle.chris.sgtpuzzles.engine.Twiddle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the second enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * One activity per game: the first script runs on the board laid out at
 * the default `limitDpi=auto`, then `limitDpi=off` resizes the same
 * game and the second script runs on the new board. Every point is
 * computed from the live [BoardGeometry], so the cells come from the
 * same tile size and origin the drawing layer uses.
 *
 * The shell's `dragMode` decides which gestures reach the game:
 * - `sixteen` and `flip` are `PREVENT`: a swipe is swallowed by the
 *   shell, so their scripts pair a real tap with an inert swipe.
 * - `twiddle` is `UNMODIFIED`: the shell delivers the press at the
 *   swipe start. The inert drag therefore starts in the strip the game
 *   rejects, one half tile left of the first rotation band.
 * - `guess` is `REVERT_TO_START`: a colour drag is a real gesture. The
 *   script taps the submit area with no draft (inert), then drags a
 *   colour into a peg, and taps submit.
 *
 * Every step asserts the move count, whether the save changed, and the
 * status line when the game has one.
 */
@RunWith(Parameterized::class)
class SecondWaveTileSizeInputTest(private val case: Case) {

    /** A point in game coordinates, measured from the board origin. */
    data class Tick(val x: Int, val y: Int)

    /**
     * One gesture: a tap when [to] is null, a swipe otherwise.
     *
     * [moves] is the expected change of the move count. [changesSave]
     * is true when the save text must change, which is the case for a
     * move and for a draft that the game stores in its `UI` record.
     */
    data class Step(
        val description: String,
        val from: (BoardGeometry) -> Tick,
        val to: ((BoardGeometry) -> Tick)? = null,
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
    fun theGesturesUseTheSizedBoard() {
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
            val from = step.from(board)
            step.to?.let { to ->
                swipe(board, from, to(board))
            } ?: tap(board, from)
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

    /** A real press, drag, and release from [from] to [to]. */
    private fun swipe(board: BoardGeometry, from: Tick, to: Tick) {
        onView(withId(R.id.game_view)).perform(
            GeneralSwipeAction(
                Swipe.SLOW,
                point(board, from),
                point(board, to),
                Press.FINGER,
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
         * The centre of cell (c, r) of a board whose border is half a
         * tile, which is `flip`: `COORD(x) = x * T + T/2`, and the
         * centre adds `T/2`.
         */
        private fun cellCentre(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /**
         * The middle of the left frame band at row [r], which is
         * `sixteen`: the border is one whole tile, and a click there
         * slides row [r] one place.
         */
        private fun leftBand(r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 2, r * t + t + t / 2)
        }

        /**
         * The middle of the click band of rotation region (0, 0) on a
         * radius-2 board, which is `twiddle`. The band starts at
         * `(n-1) * T/2` plus half a tile and is one tile wide.
         */
        private fun twiddleBand(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val offset = t / 2
            Tick(offset + t, offset + t)
        }

        /**
         * A point in the strip `twiddle` rejects: left of the first
         * click band, which starts one tile from the view origin.
         */
        private fun twiddleRejectedStrip(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t * 3 / 4, t + t / 2)
        }

        /**
         * The centre of colour [colour] (one-based) of a `c2p2g3bm`
         * board, from the C `COL_OX`, `COL_OY`, and `PEGOFF` layout.
         */
        private fun guessColour(colour: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val gap = (t * 0.10).toInt()
            val pegOff = t + gap
            val colh = pegOff * 2 - gap
            Tick(t / 2 + t / 2, (board.height - colh) / 2 + (colour - 1) * pegOff + t / 2)
        }

        /**
         * The centre of peg [peg] of guess row [row], from the C
         * `GUESS_X` and `GUESS_Y` layout.
         */
        private fun guessPeg(row: Int, peg: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val gap = (t * 0.10).toInt()
            val pegOff = t + gap
            val guessh = pegOff * 3 + gap + t
            val guessy = (board.height - guessh) / 2
            Tick(t / 2 + 2 * t + peg * pegOff + t / 2, guessy + row * pegOff + t / 2)
        }

        /**
         * The submit area right of the guess pegs. C treats every point
         * right of `GUESS_X + GUESS_W` inside the guess rows as the
         * submit area, so the right edge of the board is a submit.
         */
        private fun guessSubmit(): (BoardGeometry) -> Tick = { board ->
            Tick(board.width - 1, board.height / 2)
        }

        private val cases = listOf(
            Case(
                backend = SIXTEEN,
                game = Sixteen,
                // The board of `SixteenInputParityTest`, so the desc is
                // known to load and to leave the game open.
                launch = "4x4:13,15,14,3,10,12,5,6,7,1,8,2,11,9,16,4",
                first = listOf(
                    Step(
                        "a tap in the top frame band",
                        from = leftBand(0),
                        moves = 1,
                        status = "Moves: 1",
                    ),
                    Step(
                        "a swipe the shell prevents",
                        from = leftBand(1),
                        to = leftBand(2),
                        moves = 0,
                    ),
                ),
                second = listOf(
                    Step(
                        "the tap at the second tile size",
                        from = leftBand(0),
                        moves = 1,
                        status = "Moves: 2",
                    ),
                    Step(
                        "the prevented swipe at the second tile size",
                        from = leftBand(1),
                        to = leftBand(2),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = FLIP,
                game = Flip,
                // The board of `FlipInputParityTest`: the top-left cell
                // toggles both of its lines.
                launch = "3x3c:d074191345d1644c17058,c90",
                first = listOf(
                    Step(
                        "a tap on the top-left cell",
                        from = cellCentre(0, 0),
                        moves = 1,
                        status = "Moves: 1",
                    ),
                    Step(
                        "a swipe the shell prevents",
                        from = cellCentre(0, 0),
                        to = cellCentre(1, 0),
                        moves = 0,
                    ),
                ),
                second = listOf(
                    Step(
                        "the tap at the second tile size",
                        from = cellCentre(0, 0),
                        moves = 1,
                        status = "Moves: 2",
                    ),
                    Step(
                        "the prevented swipe at the second tile size",
                        from = cellCentre(0, 0),
                        to = cellCentre(1, 0),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = TWIDDLE,
                game = Twiddle,
                // The board of `TwiddleInputParityTest`, radius 2, so
                // the click band of region (0, 0) is one tile wide.
                launch = "3x3n2:2,5,6,7,3,1,4,9,8",
                first = listOf(
                    Step(
                        "a tap on the first rotation band",
                        from = twiddleBand(),
                        moves = 1,
                        status = "Moves: 1",
                    ),
                    Step(
                        "a drag from the rejected strip",
                        from = twiddleRejectedStrip(),
                        to = twiddleBand(),
                        moves = 0,
                    ),
                ),
                second = listOf(
                    Step(
                        "the tap at the second tile size",
                        from = twiddleBand(),
                        moves = 1,
                        status = "Moves: 2",
                    ),
                    Step(
                        "the inert drag at the second tile size",
                        from = twiddleRejectedStrip(),
                        to = twiddleBand(),
                        moves = 0,
                    ),
                ),
            ),
            Case(
                backend = GUESS,
                game = Guess,
                // Three guesses, two colours, two pegs, blanks allowed:
                // a one-peg draft is markable, and a one-peg guess can
                // never match the two-peg secret, so both submits stay
                // open.
                launch = "c2p2g3bm",
                first = listOf(
                    Step(
                        "a submit with no draft",
                        from = guessSubmit(),
                        moves = 0,
                    ),
                    Step(
                        "a colour drag into the first peg",
                        from = guessColour(1),
                        to = guessPeg(0, 0),
                        moves = 0,
                        changesSave = true,
                    ),
                    Step(
                        "a submit of the draft",
                        from = guessSubmit(),
                        moves = 1,
                    ),
                ),
                second = listOf(
                    Step(
                        "the colour drag at the second tile size",
                        from = guessColour(1),
                        to = guessPeg(1, 0),
                        moves = 0,
                        changesSave = true,
                    ),
                    Step(
                        "the submit at the second tile size",
                        from = guessSubmit(),
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
