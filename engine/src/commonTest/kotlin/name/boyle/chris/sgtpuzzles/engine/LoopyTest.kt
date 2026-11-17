package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: strict. Game: loopy, engine side.
 *
 * The loopy grid-type table, the parameter grammar, the validation
 * order, the frontend defaults, the text gate, and the generator retry
 * counters. The oracle compares the resulting descriptions; these tests
 * pin the internal rules that a description does not expose.
 */
class LoopyTest {

    private val presets = Loopy.presets().map { it.params }

    @Test
    fun `the preset table maps the loopy index to the grid type`() {
        val expected = listOf(
            "7x7t0de" to GridType.SQUARE,
            "7x7t0dn" to GridType.SQUARE,
            "7x7t0dh" to GridType.SQUARE,
            "7x7t1dh" to GridType.TRIANGULAR,
            "5x5t3dh" to GridType.SNUBSQUARE,
            "7x7t4dh" to GridType.CAIRO,
            "5x5t7dh" to GridType.KITES,
            "6x6t11dh" to GridType.PENROSE_P2,
            "6x6t12dh" to GridType.PENROSE_P3,
            "7x7t2dh" to GridType.HONEYCOMB,
            "5x4t5dh" to GridType.GREATHEXAGONAL,
            "5x4t14dh" to GridType.KAGOME,
            "5x5t6dh" to GridType.OCTAGONAL,
            "3x3t8dh" to GridType.FLORET,
            "3x3t9dh" to GridType.DODECAGONAL,
            "3x3t10dh" to GridType.GREATDODECAGONAL,
            "3x2t13dh" to GridType.GREATGREATDODECAGONAL,
            "3x3t15dh" to GridType.COMPASSDODECAGONAL,
            "6x6t16dh" to GridType.HATS,
            "6x6t17dh" to GridType.SPECTRES,
        )
        assertEquals(20, presets.size)
        assertEquals(expected.map { it.first }, presets)
        assertEquals(18, Loopy.gridTypeCount)
        for ((params, type) in expected) {
            val p = Loopy.decodeParams(params) as Loopy.Params
            assertEquals(type, Loopy.gridTypeFor(p.type), "$params type")
        }
    }

    @Test
    fun `the parameter grammar matches the C quirks`() {
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("7x7"))
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("+7x+7"))
        assertEquals(Loopy.Params(0, 0, 0, 0), Loopy.decodeParams("zzz"))
        assertEquals(Loopy.Params(7, 7, 0, 4), Loopy.decodeParams("007x7t4"))
        assertEquals(Loopy.Params(7, 7, 3, 4), Loopy.decodeParams("7x7t04dh"))
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("7x7dZ"))
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams(" 7x7t"))
        assertEquals(Loopy.Params(7, -7, 0, 0), Loopy.decodeParams("7x-7"))
        assertEquals(Loopy.Params(7, 7, 3, 18), Loopy.decodeParams("7x7t18dh"))

        assertEquals("7x7t4", Loopy.encodeParams(Loopy.Params(7, 7, 3, 4), false))
        assertEquals("7x7t4dh", Loopy.encodeParams(Loopy.Params(7, 7, 3, 4), true))
        assertEquals("7x7t0de", Loopy.encodeParams(Loopy.Params(7, 7, 0, 0), true))
        // A hand-made difficulty outside the table writes a bare `d`,
        // which decodes as Easy; C reads out of bounds.
        assertEquals("7x7t0d", Loopy.encodeParams(Loopy.Params(7, 7, 4, 0), true))
        assertEquals(Loopy.Params(7, 7, 0, 0), Loopy.decodeParams("7x7t0d"))
    }

    @Test
    fun `validation follows the C order and the area bound`() {
        fun check(w: Int, h: Int, diff: Int, type: Int): String? =
            Loopy.validateParams(Loopy.Params(w, h, diff, type), true)

        assertNull(check(7, 7, 0, 0))
        assertEquals("Illegal grid type", check(7, 7, 0, 18))
        assertEquals("Illegal grid type", check(7, 7, 0, -1))
        assertEquals(
            "Width and height for this grid type must both be at least 3",
            check(2, 7, 0, 0),
        )
        assertEquals(
            "At least one of width and height for this grid type must be at least 2",
            check(1, 1, 0, 8),
        )
        assertEquals(
            "Width and height for this grid type must both be at least 6",
            check(5, 6, 0, 16),
        )
        assertEquals("Unknown difficulty level", check(7, 7, 4, 0))
        assertEquals("Unknown difficulty level", check(7, 7, -1, 0))
        // Square accepts `(w + 1) * (h + 1) <= INT_MAX`.
        assertNull(check(46339, 46339, 0, 0))
        assertEquals(
            "Grid size must not be unreasonably large",
            check(46340, 46340, 0, 0),
        )
    }

    @Test
    fun `the frontend defaults are the C new_ui`() {
        val midend = Midend.newGame(Loopy, "7x7t0de", "12345")
        val ui = midend.ui as Loopy.Ui
        val state = midend.state as Loopy.State
        assertEquals((state.grid.lowestX + state.grid.highestX) / 2, ui.curX)
        assertEquals((state.grid.lowestY + state.grid.highestY) / 2, ui.curY)
        assertFalse(ui.curVisible)
        assertTrue(ui.drawFaintLines)
        assertEquals(Loopy.AutoFollow.OFF, ui.autoFollow)
        // No `UI` record: a load recreates the same defaults.
        val loaded = Midend.fromSave(Loopy, midend.serialise())
        val loadedUi = loaded.ui as Loopy.Ui
        assertTrue(loadedUi.drawFaintLines)
        assertEquals(Loopy.AutoFollow.OFF, loadedUi.autoFollow)
        assertFalse(loadedUi.curVisible)
    }

    @Test
    fun `the text form is square only`() {
        val square = Midend.newGame(Loopy, "7x7t0de", "12345")
        assertTrue(Loopy.canFormatAsTextNow(square.currentParams))
        assertTrue(square.textFormat().isNotEmpty())

        for (params in listOf("7x7t1dh", "5x5t7dh", "6x6t16dh", "3x2t13dh")) {
            val midend = Midend.newGame(Loopy, params, "12345")
            assertFalse(Loopy.canFormatAsTextNow(midend.currentParams), "$params gate")
            assertEquals("", midend.textFormat(), "$params text")
            assertFailsWith<IllegalArgumentException>("$params direct call") {
                Loopy.textFormat(midend.state)
            }
        }
    }

    @Test
    fun `the generator retry counts are the pinned fixtures`() {
        // These seeds are the C retry fixtures in `LoopyDescTest`. The
        // description is compared with the oracle there; the counters
        // here catch a generator change that still reaches the same text.
        val fixtures = listOf(
            Fixture("7x7t0de", "28", 1, 8, 0, null),
            Fixture("7x7t0dn", "0", 4, 5, 3, null),
            Fixture("7x7t0dh", "33", 6, 6, 5, null),
            Fixture("6x6t11dh", "4", 12, 12, 11, "80BABUAABUAA"),
            Fixture("6x6t16dh", "38", 43, 48, 42, "6,1,10,4,4,P"),
            Fixture("6x6t17dh", "33", 40, 153, 39, "406143F"),
        )
        for (fixture in fixtures) {
            val p = Loopy.decodeParams(fixture.params) as Loopy.Params
            val generated = Loopy.generate(p, RandomState.fromSeed(fixture.seed))
            assertEquals(fixture.board, generated.boardAttempts, "${fixture.params}/${fixture.seed} board")
            assertEquals(fixture.full, generated.fullClueAttempts, "${fixture.params}/${fixture.seed} full")
            assertEquals(fixture.easy, generated.tooEasyRetries, "${fixture.params}/${fixture.seed} easy")
            assertEquals(
                fixture.grid,
                generated.gridDescription,
                "${fixture.params}/${fixture.seed} grid",
            )
        }
    }

    @Test
    fun `the generator is deterministic for a seed`() {
        val p = Loopy.decodeParams("3x3t9dh") as Loopy.Params
        val first = Loopy.generate(p, RandomState.fromSeed("12345"))
        val second = Loopy.generate(p, RandomState.fromSeed("12345"))
        assertEquals(first, second)
        val other = Loopy.generate(p, RandomState.fromSeed("12346"))
        assertTrue(first.text != other.text)
    }

    @Test
    fun `an invalid description fails before a state exists`() {
        val p = Loopy.decodeParams("7x7t0de")
        assertEquals(
            "Unknown character in description",
            Loopy.validateDesc(p, "!"),
        )
        assertFailsWith<IllegalArgumentException> {
            Loopy.newState(p, "!")
        }
        assertFailsWith<IllegalArgumentException> {
            Loopy.newState(p, "a")
        }
    }

    @Test
    fun `the solve payload is the S form`() {
        val midend = Midend.newGame(Loopy, "7x7t0de", "12345")
        val solve = Loopy.solve(midend.state)!!
        assertTrue(solve.startsWith("S"))
        val body = solve.substring(1)
        assertTrue(body.isNotEmpty())
        var i = 0
        while (i < body.length) {
            val start = i
            while (i < body.length && body[i].isDigit()) i++
            assertTrue(i > start, "edge at $start")
            assertTrue(body[i] == 'y' || body[i] == 'n', "state at $i")
            i++
        }
        val applied = Loopy.executeMove(midend.state, solve)!!
        assertEquals(1, Loopy.status(applied))
        // The solved flag is sticky and supplied by completion, not by
        // the move string.
        val again = Loopy.executeMove(applied, "0n")!!
        assertEquals(1, Loopy.status(again))
    }

    @Test
    fun `changed state reports only the first honest completion`() {
        val params = Loopy.decodeParams("7x7t0de") as Loopy.Params
        val unsolved = Midend.newGame(Loopy, "7x7t0de", "12345").state as Loopy.State
        val solved = unsolved.copy() as Loopy.State
        solved.solved = true
        val ui = Loopy.newUi(unsolved)
        assertTrue(Loopy.changedState(ui, unsolved, solved))
        assertFalse(Loopy.changedState(ui, solved, solved))

        val cheated = unsolved.copy() as Loopy.State
        cheated.solved = true
        cheated.cheated = true
        assertFalse(Loopy.changedState(ui, unsolved, cheated))
        assertFalse(Loopy.changedState(ui, null, solved))
    }

    private data class Fixture(
        val params: String,
        val seed: String,
        val board: Int,
        val full: Int,
        val easy: Int,
        val grid: String?,
    )
}
