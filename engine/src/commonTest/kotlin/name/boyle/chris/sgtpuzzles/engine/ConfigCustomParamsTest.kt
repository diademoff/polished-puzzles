package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Level: semantic. The derived and non-obvious `custom_params` readers.
 *
 * These cases cannot be left to the parity table alone: they pin the
 * integer readers (`atoi`, `atol`, `atof`), the blackbox range scan, the
 * mines percent rule, the bridges derived fields, and the solo jigsaw
 * fold against the C text.
 */
class ConfigCustomParamsTest {

    private fun edited(
        game: Game,
        edits: kotlin.collections.Map<Int, String>,
    ): List<ConfigItem> {
        val schema = game.configure(game.defaultParams())
            ?: error("${game.backendName} has no schema")
        return schema.mapIndexed { index, item ->
            val value = edits[index] ?: return@mapIndexed item
            when (item) {
                is ConfigItem.Text -> item.copy(value = value)
                is ConfigItem.Bool -> item.copy(value = value == "true")
                is ConfigItem.Choices -> item.copy(selected = value.toInt())
            }
        }
    }

    private fun params(game: Game, edits: kotlin.collections.Map<Int, String>): Game.Params =
        game.customParams(edited(game, edits))

    @Test
    fun `atoi takes the numeric prefix, a sign, and leading space`() {
        val prefix = params(SameGame, mapOf(0 to "12abc", 1 to "+7", 2 to " 4")) as SameGame.Params
        assertEquals(12, prefix.width)
        assertEquals(7, prefix.height)
        assertEquals(4, prefix.ncols)

        val none = params(SameGame, mapOf(0 to "abc")) as SameGame.Params
        assertEquals(0, none.width)
    }

    @Test
    fun `samegame scoring is the selected index plus one`() {
        assertEquals(1, (params(SameGame, mapOf(3 to "0")) as SameGame.Params).scoresub)
        assertEquals(2, (params(SameGame, mapOf(3 to "1")) as SameGame.Params).scoresub)
    }

    @Test
    fun `bridges derives maxb, islands, and expansion from the choices`() {
        val p = params(
            Bridges,
            mapOf(2 to "2", 3 to "false", 4 to "3", 5 to "1", 6 to "2"),
        ) as Bridges.Params
        assertEquals(2, p.difficulty)
        assertFalse(p.allowloops)
        assertEquals(4, p.maxb)
        assertEquals(10, p.islands)
        assertEquals(20, p.expansion)
    }

    @Test
    fun `mines reads a percent of the area`() {
        val ten = params(Mines, mapOf(2 to "10%")) as Mines.Params
        assertEquals(8, ten.n, "10% of 9x9 is 8 after C integer division")
        assertEquals(-1, ten.firstClickX)

        assertEquals(81, (params(Mines, mapOf(2 to "100%")) as Mines.Params).n)
        assertEquals(26, (params(Mines, mapOf(2 to "33%")) as Mines.Params).n)
        assertEquals(10, (params(Mines, mapOf(2 to "10")) as Mines.Params).n)
    }

    @Test
    fun `blackbox reads a range, a single number, and the sscanf rules`() {
        fun balls(value: String): Pair<Int, Int> {
            val p = params(BlackBox, mapOf(2 to value)) as BlackBox.Params
            return p.minBalls to p.maxBalls
        }

        assertEquals(4 to 7, balls("4-7"))
        assertEquals(4 to 4, balls("4"))
        assertEquals(4 to 7, balls(" 4- 7"))
        assertEquals(4 to 4, balls("4 -7"))
        assertEquals(4 to 4, balls("4-"))
        assertEquals(-3 to 5, balls("-3-5"))
        assertEquals(5 to 10, balls("5- 10"))
    }

    @Test
    fun `blackbox renders a range only when the bounds differ`() {
        val equal = BlackBox.configure(BlackBox.Params(8, 8, 5, 5))
        assertEquals("5", (equal[2] as ConfigItem.Text).value)
        val range = BlackBox.configure(BlackBox.Params(8, 8, 4, 7))
        assertEquals("4-7", (range[2] as ConfigItem.Text).value)
    }

    @Test
    fun `mosaic reads Height before Width with atol`() {
        val p = params(Mosaic, mapOf(0 to "4", 1 to "6")) as Mosaic.Params
        assertEquals(6, p.width)
        assertEquals(4, p.height)
    }

    @Test
    fun `mosaic atol is narrowed to int as the C assignment is`() {
        /* 4294967306 = 2^32 + 10, so the int field is 10. */
        val wrapped = params(Mosaic, mapOf(0 to "4294967306")) as Mosaic.Params
        assertEquals(10, wrapped.height)

        /* A value above Long saturates to Long.MAX_VALUE, then narrows. */
        val saturated = params(
            Mosaic,
            mapOf(0 to "99999999999999999999999"),
        ) as Mosaic.Params
        assertEquals(Long.MAX_VALUE.toInt(), saturated.height)
    }

    @Test
    fun `net and netslide read the barrier with atof`() {
        assertEquals(0.5f, (params(Net, mapOf(3 to "0.5")) as Net.Params).barrierProbability)
        assertEquals(0.1f, (params(Net, mapOf(3 to "1e-1")) as Net.Params).barrierProbability)
        assertEquals(0.5f, (params(Net, mapOf(3 to "0x1p-1")) as Net.Params).barrierProbability)
        assertEquals(0.25f, (params(Net, mapOf(3 to " 0.25")) as Net.Params).barrierProbability)
        assertEquals(
            0.25f,
            (params(Netslide, mapOf(3 to "0.25")) as Netslide.Params).barrierProbability,
        )
    }

    @Test
    fun `rect reads the expansion factor with atof`() {
        assertEquals(0.5f, (params(Rect, mapOf(2 to "0.5")) as Rect.Params).expandFactor)
        assertEquals(2.0f, (params(Rect, mapOf(2 to "2")) as Rect.Params).expandFactor)
        assertEquals(
            0.25f,
            (params(Rect, mapOf(2 to "0.25junk")) as Rect.Params).expandFactor,
        )
    }

    @Test
    fun `flip and pegs map the selected choice to the enum`() {
        assertEquals(
            Flip.MatrixType.RANDOM,
            (params(Flip, mapOf(2 to "1")) as Flip.Params).matrixType,
        )
        assertEquals(
            Pegs.Type.OCTAGON,
            (params(Pegs, mapOf(2 to "1")) as Pegs.Params).type,
        )
        assertEquals(
            Pegs.Type.RANDOM,
            (params(Pegs, mapOf(2 to "2")) as Pegs.Params).type,
        )
    }

    @Test
    fun `lightup keeps the black percentage without a clamp`() {
        assertEquals(35, (params(Lightup, mapOf(2 to "35")) as Lightup.Params).blackpc)
        assertEquals(500, (params(Lightup, mapOf(2 to "500")) as Lightup.Params).blackpc)
    }

    @Test
    fun `solo jigsaw folds the rows into the columns`() {
        val jigsaw = params(
            Solo,
            mapOf(0 to "3", 1 to "3", 3 to "true"),
        ) as Solo.Params
        assertEquals(9, jigsaw.c)
        assertEquals(1, jigsaw.r)
        assertEquals(DIFF_KINTERSECT, jigsaw.kdiff)

        val normal = params(Solo, mapOf(0 to "3", 1 to "4")) as Solo.Params
        assertEquals(3, normal.c)
        assertEquals(4, normal.r)
    }

    @Test
    fun `solo keeps the X and killer booleans and the two choices`() {
        val p = params(
            Solo,
            mapOf(2 to "true", 4 to "true", 5 to "7", 6 to "5"),
        ) as Solo.Params
        assertTrue(p.xtype)
        assertTrue(p.killer)
        assertEquals(7, p.symm)
        assertEquals(5, p.diff)
    }

    private companion object {
        const val DIFF_KINTERSECT = 3
    }
}
