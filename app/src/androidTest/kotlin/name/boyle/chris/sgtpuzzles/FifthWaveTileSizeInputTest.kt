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
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.MAGNETS
import name.boyle.chris.sgtpuzzles.backend.MOSAIC
import name.boyle.chris.sgtpuzzles.backend.PATTERN
import name.boyle.chris.sgtpuzzles.backend.SIGNPOST
import name.boyle.chris.sgtpuzzles.backend.UNTANGLE
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LIMIT_DPI_KEY
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Magnets
import name.boyle.chris.sgtpuzzles.engine.Mosaic
import name.boyle.chris.sgtpuzzles.engine.Pattern
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Signpost
import name.boyle.chris.sgtpuzzles.engine.Untangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Level: instrumentation. Input for the fifth enablement wave at two
 * tile sizes, neither of them the game's preferred tile.
 *
 * One activity per game: the first script runs on the board laid out at
 * the default `limitDpi=auto`, then `limitDpi=off` resizes the same
 * game and the second script runs on the new board. Every point is
 * computed from the live [BoardGeometry], so the cells come from the
 * same tile size and origin the drawing layer uses, and the stored game
 * preferences are removed so the C defaults decide the gesture.
 *
 * The C input code decides each gesture, and the scripts include the
 * inert ones so the delta is honest:
 * - `magnets` left-clicks a cell to a positive magnet. A click in the
 *   border corner maps to no cell and no clue band, so it is inert.
 * - `pattern` left-clicks an unknown cell to full. A click in the clue
 *   band, one pixel left of the first cell, is inert.
 * - `mosaic` taps a shown clue to mark it. A click in the half-tile
 *   margin, left of the grid, is inert.
 * - `signpost` drags from a square to the neighbour its arrow points
 *   at. A press on the final immutable number is inert.
 * - `untangle` drags a vertex. A press in the board corner is far from
 *   every vertex, outside the fixed 60-pixel radius, so it is inert.
 *
 * Every step asserts the move count and whether the save text changed,
 * and a move step asserts its exact `MOVE` record. `untangle` is the one
 * exception: its record holds the pixel the drag last reported, which is
 * the last interpolated `MOVE` before the release, not the requested end
 * point. Those two steps assert the vertex, the live tile denominator,
 * and a point between the start and the requested end instead of an
 * exact pixel.
 */
@RunWith(Parameterized::class)
class FifthWaveTileSizeInputTest(private val case: Case) {

    /** A point in game coordinates, measured from the board origin. */
    data class Tick(val x: Int, val y: Int)

    /** A real gesture: a tap or a press-drag-release on the board. */
    sealed interface Action {
        data class Press(val at: (BoardGeometry) -> Tick) : Action
        data class Drag(
            val from: (BoardGeometry) -> Tick,
            val to: (BoardGeometry) -> Tick,
        ) : Action
    }

    /**
     * One gesture. [moves] is the expected change of the move count.
     * [changesSave] is true when the save text must change, which is the
     * case exactly when the gesture made a move: none of these five
     * games stores a `UI` record. [move] computes the expected `MOVE`
     * record from the live board, and [moveCheck] checks a record whose
     * pixels come from the drag itself.
     */
    data class Step(
        val description: String,
        val action: Action,
        val moves: Int = 0,
        val changesSave: Boolean = moves != 0,
        val move: ((BoardGeometry) -> String)? = null,
        val moveCheck: ((BoardGeometry, String) -> Unit)? = null,
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
        val originalGamePrefs = prefs.getString(case.backend.preferencesName, null)
        // A stored preference would change the gesture: signpost reads
        // no preference, and untangle's snap-to-grid would change the
        // committed point. The test pins the C defaults.
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
                is Action.Drag -> drag(board, action.from(board), action.to(board))
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
                // An inert gesture returns at once, so it needs a bounded settle.
                GamePlayTestSupport.awaitInertGesture(scenario)
                assertEquals(
                    "${step.description} must not change the save",
                    before,
                    liveSaveText(scenario),
                )
            }
            step.move?.let {
                assertEquals(
                    "${step.description} wrote the wrong move",
                    it(board),
                    lastMove(scenario),
                )
            }
            step.moveCheck?.let {
                it(board, checkNotNull(lastMove(scenario)) { "no MOVE record" })
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
         * The centre of a `magnets` cell. C uses `BORDER = T/8` and
         * `COORD(x) = (x+1)*T + BORDER`, and the centre adds `T/2`.
         */
        private fun magnetsCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val border = t / 8
            Tick((c + 1) * t + border + t / 2, (r + 1) * t + border + t / 2)
        }

        /**
         * A point in the `magnets` border corner: `FROMCOORD` maps it to
         * `(-1, -1)`, which is no cell and no clue band.
         */
        private fun magnetsBorder(): (BoardGeometry) -> Tick = { board ->
            val border = board.tileSize / 8
            Tick(border + 1, border + 1)
        }

        /**
         * The centre of a `pattern` cell. C uses `BORDER = 3T/4`,
         * `GUTTER = T/2`, `TLBORDER(d) = d/5 + 2`, and
         * `TOCOORD(d, x) = BORDER + GUTTER + T*(TLBORDER(d) + x)`.
         */
        private fun patternCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val x = 3 * t / 4 + t / 2 + t * (10 / 5 + 2 + c) + t / 2
            val y = 3 * t / 4 + t / 2 + t * (10 / 5 + 2 + r) + t / 2
            Tick(x, y)
        }

        /** A point one pixel left of the first `pattern` cell: no cell. */
        private fun patternClueBand(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            val first = 3 * t / 4 + t / 2 + t * (10 / 5 + 2)
            Tick(first - 1, first + t / 2)
        }

        /**
         * The centre of a `mosaic` cell. C reads `x/T` after subtracting
         * the half-tile border, so the centre of cell `c` is `c*T + T/2`.
         */
        private fun mosaicCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(c * t + t / 2, r * t + t / 2)
        }

        /** A point in the `mosaic` half-tile margin: no cell. */
        private fun mosaicMargin(): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick(t / 4, 2 * t)
        }

        /**
         * The centre of a `signpost` square. C uses `BORDER = T/2` and
         * `COORD(x) = x*T + BORDER`, and the centre adds `T/2`.
         */
        private fun signpostCell(c: Int, r: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            Tick((c + 1) * t, (r + 1) * t)
        }

        /**
         * A vertex of an `untangle` `10` board, from `make_circle(10, 5)`
         * at the fixed denominator 64: point 0 is (160, 23) and point 2
         * is (290, 118). The drawing scales the point by `tile/64`.
         */
        private fun untanglePoint(index: Int): (BoardGeometry) -> Tick = { board ->
            val t = board.tileSize
            when (index) {
                0 -> Tick(160 * t / 64, 23 * t / 64)
                2 -> Tick(290 * t / 64, 118 * t / 64)
                else -> error("no pinned coordinate for vertex $index")
            }
        }

        /** The board corner, far from every `untangle` vertex. */
        private fun untangleCorner(): (BoardGeometry) -> Tick = { _ -> Tick(0, 0) }

        /**
         * The `P` record of a real `untangle` drag. The record holds the
         * last pixel the drag reported and the live tile size as the
         * rational. The committed pixel must name [index], use that tile,
         * and lie strictly between the vertex and the requested end,
         * because the release repeats the last reported pixel.
         */
        private fun assertUntangleMove(
            board: BoardGeometry,
            move: String,
            index: Int,
            start: Tick,
            end: Tick,
        ) {
            val t = board.tileSize
            val prefix = "P$index:"
            val suffix = "/$t"
            assertTrue(
                "the move '$move' must name vertex $index at tile $t",
                move.startsWith(prefix) && move.endsWith(suffix),
            )
            val coordinates = move.removePrefix(prefix).removeSuffix(suffix).split(",")
            assertEquals("the move '$move' must hold two coordinates", 2, coordinates.size)
            val x = coordinates[0].toInt()
            val y = coordinates[1].toInt()
            assertTrue(
                "the move '$move' must move vertex $index right of ${start.x}",
                x > start.x && x < end.x,
            )
            assertTrue(
                "the move '$move' must move vertex $index below ${start.y}",
                y > start.y && y < end.y,
            )
        }

        private val cases = listOf(
            Case(
                backend = MAGNETS,
                game = Magnets,
                // The frozen 6x5 Tricky game of seed 12345. Every cell
                // starts neutral, so a left click is a positive magnet.
                launch = "6x5dt:222212,22322,312212,22223,LRLRLRTTTTLRBBBBTTTTTTBBBBBBLR",
                first = listOf(
                    Step(
                        "a click in the border corner",
                        action = Action.Press(magnetsBorder()),
                        moves = 0,
                    ),
                    Step(
                        "a left click on the cell at (0,0)",
                        action = Action.Press(magnetsCell(0, 0)),
                        moves = 1,
                        move = { "+0,0" },
                    ),
                ),
                second = listOf(
                    Step(
                        "the cell click at the second tile size",
                        action = Action.Press(magnetsCell(1, 1)),
                        moves = 1,
                        move = { "+1,1" },
                    ),
                ),
            ),
            Case(
                backend = PATTERN,
                game = Pattern,
                // The frozen 10x10 game of seed 12345. Cell (0,0) and
                // cell (1,0) start unknown, so a left click fills them.
                launch = "10x10:4/5/6/7/5/1.3/6/3.1/2.4/3/4/3/2/2.1/5/6.2/6.3/5.2/4.1/4",
                first = listOf(
                    Step(
                        "a click in the clue band",
                        action = Action.Press(patternClueBand()),
                        moves = 0,
                    ),
                    Step(
                        "a left click on the cell at (0,0)",
                        action = Action.Press(patternCell(0, 0)),
                        moves = 1,
                        move = { "F0,0,1,1" },
                    ),
                ),
                second = listOf(
                    Step(
                        "the cell click at the second tile size",
                        action = Action.Press(patternCell(1, 0)),
                        moves = 1,
                        move = { "F1,0,1,1" },
                    ),
                ),
            ),
            Case(
                backend = MOSAIC,
                game = Mosaic,
                // The frozen 3x3 game of seed 12345: `4b47d`. Cells
                // (0,0), (0,1), and (1,1) are shown clues.
                launch = "3x3:4b47d",
                first = listOf(
                    Step(
                        "a click in the left margin",
                        action = Action.Press(mosaicMargin()),
                        moves = 0,
                    ),
                    Step(
                        "a tap on the clue at (0,0)",
                        action = Action.Press(mosaicCell(0, 0)),
                        moves = 1,
                        move = { "t0,0" },
                    ),
                ),
                second = listOf(
                    Step(
                        "the clue tap at the second tile size",
                        action = Action.Press(mosaicCell(1, 1)),
                        moves = 1,
                        move = { "t1,1" },
                    ),
                ),
            ),
            Case(
                backend = SIGNPOST,
                game = Signpost,
                // The frozen 4x4 corner-start game of seed 12345.
                // (0,0) holds 1 and points south-east, so the link to
                // (1,1) is valid; after it, (1,1) holds 2 and points
                // south, so the link to (1,2) is the next valid one.
                launch = "4x4c:1dcegcefebeghcbh16a",
                first = listOf(
                    Step(
                        "a press on the final immutable number",
                        action = Action.Press(signpostCell(3, 3)),
                        moves = 0,
                    ),
                    Step(
                        "a drag from (0,0) to (1,1)",
                        action = Action.Drag(
                            from = signpostCell(0, 0),
                            to = signpostCell(1, 1),
                        ),
                        moves = 1,
                        move = { "L0,0-1,1" },
                    ),
                ),
                second = listOf(
                    Step(
                        "the drag from (1,1) to (1,2) at the second tile size",
                        action = Action.Drag(
                            from = signpostCell(1, 1),
                            to = signpostCell(1, 2),
                        ),
                        moves = 1,
                        move = { "L1,1-1,2" },
                    ),
                ),
            ),
            Case(
                backend = UNTANGLE,
                game = Untangle,
                // The frozen 10-point game of seed 12345.
                launch = "10:0-4,0-5,0-6,0-7,1-2,1-3,1-7,1-9,2-8,3-9,4-6,4-9,5-6,5-7,5-8,7-8",
                first = listOf(
                    Step(
                        "a press in the board corner",
                        action = Action.Press(untangleCorner()),
                        moves = 0,
                    ),
                    Step(
                        "a drag of vertex 0 by a half tile",
                        action = Action.Drag(
                            from = untanglePoint(0),
                            to = { board ->
                                val t = board.tileSize
                                val p = untanglePoint(0)(board)
                                Tick(p.x + t / 2, p.y + t / 4)
                            },
                        ),
                        moves = 1,
                        moveCheck = { board, move ->
                            val t = board.tileSize
                            val start = untanglePoint(0)(board)
                            assertUntangleMove(
                                board,
                                move,
                                0,
                                start,
                                Tick(start.x + t / 2, start.y + t / 4),
                            )
                        },
                    ),
                ),
                second = listOf(
                    Step(
                        "the drag of vertex 2 at the second tile size",
                        action = Action.Drag(
                            from = untanglePoint(2),
                            to = { board ->
                                val t = board.tileSize
                                val p = untanglePoint(2)(board)
                                Tick(p.x + t / 4, p.y + t / 4)
                            },
                        ),
                        moves = 1,
                        moveCheck = { board, move ->
                            val t = board.tileSize
                            val start = untanglePoint(2)(board)
                            assertUntangleMove(
                                board,
                                move,
                                2,
                                start,
                                Tick(start.x + t / 4, start.y + t / 4),
                            )
                        },
                    ),
                ),
            ),
        )

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Iterable<Array<Any?>> = cases.map { arrayOf(it) }
    }
}
