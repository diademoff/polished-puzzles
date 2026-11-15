package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.ArrowMode
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Netslide
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic for state, strict for the warm trace. Game: netslide.
 *
 * The layout, real row and column slides at tiles 48, 63, 82, and 100,
 * the 411x702 letterboxed viewport with origin (0,145), inert clicks in
 * a cell, a frame corner, a centre band, and the letterbox, the cursor
 * ring with a select at each end, a drag and a release, and
 * `requestKeys` on every preset.
 *
 * A real slide must change the board, add one move, and add one state.
 * An inert click must change neither the board nor the move count, must
 * return `Unused` so the shell asks for no redraw, and must leave the
 * warm drawstate unchanged. A drag is not supported: the GameView drag
 * mode is PREVENT and `interpret_move` returns `NULL` for a drag, so a
 * press, drag, and release makes one move at the press and no more.
 */
class NetslideInputParityTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    /** A viewport and the tile size and letterbox origin C picks for it. */
    private data class View(
        val viewWidth: Int,
        val viewHeight: Int,
        val tile: Int,
        val originX: Int = 0,
        val originY: Int = 0,
    )

    private val views = listOf(
        View(241, 241, 48),
        View(316, 316, 63),
        View(411, 411, 82),
        View(501, 501, 100),
        View(411, 702, 82, 0, 145),
    )

    @Test
    fun `the layout matches the oracle at four tiles and the letterbox`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("netslide", "3x3", SEED).state
            for (view in views) {
                val layout = oracle.layout(save, view.viewWidth, view.viewHeight)
                val geometry = Midend.fromSave(Netslide, SgtpSave.parse(save))
                    .geometryFor(view.viewWidth, view.viewHeight)
                val label = "${view.viewWidth}x${view.viewHeight}"
                assertEquals("tile at $label", view.tile, geometry.tileSize)
                assertEquals("width at $label", layout.width, geometry.width)
                assertEquals("height at $label", layout.height, geometry.height)
                assertEquals("origin x at $label", view.originX, geometry.originX)
                assertEquals("origin y at $label", view.originY, geometry.originY)
            }
        }

    @Test
    fun `real slides at four tiles and the letterbox match the oracle`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("netslide", "3x3", SEED).state
            for (view in views) {
                val label = "tile ${view.tile} origin (${view.originX},${view.originY})"
                for (click in bandClicks(view.tile)) {
                    checkClick(oracle, save, view, click, KeyOutcome.SomeEffect, "$label $click")
                }
            }
        }

    @Test
    fun `centre, corner, band, and letterbox clicks are inert`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("netslide", "3x3", SEED).state
            for (view in views) {
                val t = view.tile
                val label = "tile ${view.tile} origin (${view.originX},${view.originY})"
                val inert = listOf(
                    Click(t + t / 2, t + t / 2, Buttons.LEFT_BUTTON),
                    Click(t / 2, t / 2, Buttons.LEFT_BUTTON),
                    Click(t / 2, 2 * t + t / 2, Buttons.LEFT_BUTTON),
                    Click(2 * t + t / 2, t / 2, Buttons.LEFT_BUTTON),
                    Click(5 * t + t / 2, 3 * t / 2, Buttons.LEFT_BUTTON),
                )
                for (click in inert) {
                    checkClick(oracle, save, view, click, KeyOutcome.Unused, "$label inert $click")
                }
            }

            // The 411x702 view is 145 pixels taller than the board, so
            // view (24,24) is game (24,-121): above the board and inert.
            val letterbox = views.last()
            checkClick(
                oracle,
                save,
                letterbox,
                Click(24, -letterbox.originY + 24, Buttons.LEFT_BUTTON),
                KeyOutcome.Unused,
                "letterbox above the board",
            )
        }

    @Test
    fun `a press, drag, and release make one move and a lone drag none`() =
        OracleClient().use { oracle ->
            val save = oracle.newGame("netslide", "3x3", SEED).state
            val view = views.first { it.tile == 63 }
            val t = view.tile
            val gameX = t / 2
            val gameY = 3 * t / 2
            val points = listOf(
                OracleClient.Point(view.originX + gameX, view.originY + gameY, Buttons.LEFT_BUTTON),
                OracleClient.Point(
                    view.originX + gameX + 10,
                    view.originY + gameY + 10,
                    Buttons.LEFT_DRAG,
                ),
                OracleClient.Point(
                    view.originX + gameX + 20,
                    view.originY + gameY + 20,
                    Buttons.LEFT_RELEASE,
                ),
            )
            val expected = oracle.pointsSized(
                save,
                points,
                view.viewWidth,
                view.viewHeight,
            )
            assertEquals("the press makes one move", listOf("R0,1"), movesOf(expected.state.state))

            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val before = midend.state as Netslide.State
            assertEquals(
                "press outcome",
                KeyOutcome.SomeEffect,
                midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, t),
            )
            assertEquals(
                "drag outcome",
                KeyOutcome.Unused,
                midend.processKey(gameX + 10, gameY + 10, Buttons.LEFT_DRAG, t),
            )
            assertEquals(
                "release outcome",
                KeyOutcome.Unused,
                midend.processKey(gameX + 20, gameY + 20, Buttons.LEFT_RELEASE, t),
            )
            assertEquals("one move only", listOf("R0,1"), midend.serialise().values("MOVE"))
            assertEquals("two states", 2, midend.stateCount)
            assertEquals("C save after the gesture", expected.state.state, midend.serialise().serialise())
            assertEquals("one move counted", before.moveCount + 1, (midend.state as Netslide.State).moveCount)

            val lone = Midend.fromSave(Netslide, SgtpSave.parse(save))
            assertEquals(
                "a lone drag is unused",
                KeyOutcome.Unused,
                lone.processKey(gameX, gameY, Buttons.LEFT_DRAG, t),
            )
            assertEquals(
                "a lone release is unused",
                KeyOutcome.Unused,
                lone.processKey(gameX, gameY, Buttons.LEFT_RELEASE, t),
            )
            assertEquals("no state from a drag", 1, lone.stateCount)
        }

    @Test
    fun `the cursor ring and requestKeys match the oracle`() = OracleClient().use { oracle ->
        val save = oracle.newGame("netslide", "3x3", SEED).state
        val t = 63
        val ring = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_UP,
            Buttons.CURSOR_UP,
            Buttons.CURSOR_UP,
        )
        val sequences = listOf(
            ring,
            ring + Buttons.CURSOR_SELECT,
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN) +
                Buttons.CURSOR_SELECT,
            listOf(Buttons.CURSOR_SELECT),
            listOf(Buttons.CURSOR_SELECT, Buttons.CURSOR_SELECT2),
        )
        for (sequence in sequences) {
            val expected = oracle.keys(save, sequence)
            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            for (key in sequence) {
                assertEquals(
                    "outcome for $key in $sequence",
                    KeyOutcome.SomeEffect,
                    midend.processKey(0, 0, key, t),
                )
            }
            assertEquals("save for $sequence", expected.state, midend.serialise().serialise())
            assertEquals("id for $sequence", expected.id, midend.id())
            assertEquals("status for $sequence", expected.status, midend.status())
            assertEquals(
                "state count for $sequence",
                SgtpSave.parse(expected.state).value("NSTATES"),
                midend.stateCount.toString(),
            )
        }

        for (preset in Netslide.presets()) {
            val cSave = oracle.newGame("netslide", preset.params, SEED).state
            val expected = oracle.requestKeys(cSave)
            val midend = Midend.newGame(Netslide, preset.params, SEED)
            val actual = Netslide.requestKeys(midend.params)
            assertEquals("${preset.params} arrow mode", 3, expected.arrowMode)
            assertEquals(
                "${preset.params} Kotlin arrow mode",
                ArrowMode.ARROWS_LEFT_RIGHT,
                actual.arrowMode,
            )
            assertTrue("${preset.params} C labels", expected.labels.isEmpty())
            assertTrue("${preset.params} Kotlin labels", actual.labels.isEmpty())
        }
    }

    @Test
    fun `an inert click leaves the warm frame unchanged`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("netslide", "3x3", SEED).state
        for (view in listOf(views.first { it.tile == 48 }, views.last())) {
            val t = view.tile
            val gameX = t + t / 2
            val gameY = t + t / 2
            val point = OracleClient.Point(
                view.originX + gameX,
                view.originY + gameY,
                Buttons.LEFT_BUTTON,
            )
            val without = oracle.traceWarm(
                save,
                emptyList(),
                view.viewWidth,
                view.viewHeight,
            )
            val withClick = oracle.traceWarm(
                save,
                listOf(point),
                view.viewWidth,
                view.viewHeight,
            )
            assertEquals(
                "the inert click changes no drawing at tile $t",
                TraceNormalizer.normalize(requireNotNull(without.trace), upstream),
                TraceNormalizer.normalize(requireNotNull(withClick.trace), upstream),
            )

            val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
            val before = midend.state as Netslide.State
            val tilesBefore = before.tiles.toList()
            assertEquals(
                "inert outcome at tile $t",
                KeyOutcome.Unused,
                midend.processKey(gameX, gameY, Buttons.LEFT_BUTTON, t),
            )
            val after = midend.state as Netslide.State
            assertEquals("no history at tile $t", 1, midend.stateCount)
            assertEquals("no board change at tile $t", tilesBefore, after.tiles.toList())
            assertEquals("no move at tile $t", before.moveCount, after.moveCount)

            val actualWith = KotlinTraceSink.warmTrace(
                Midend.fromSave(Netslide, SgtpSave.parse(save)),
                listOf(OracleClient.Point(gameX, gameY, Buttons.LEFT_BUTTON)),
                t,
            )
            val actualWithout = KotlinTraceSink.warmTrace(
                Midend.fromSave(Netslide, SgtpSave.parse(save)),
                emptyList(),
                t,
            )
            assertEquals(
                "Kotlin warm frame is unchanged at tile $t",
                TraceNormalizer.normalize(actualWithout, upstream),
                TraceNormalizer.normalize(actualWith, upstream),
            )
        }
    }

    private data class Click(val x: Int, val y: Int, val button: Int)

    /**
     * The four arrow bands at a non-centre row and column, in game
     * coordinates. `(24,72)` at tile 48 is the left band of row 0.
     */
    private fun bandClicks(t: Int): List<Click> = listOf(
        Click(t / 2, 3 * t / 2, Buttons.LEFT_BUTTON),
        Click(t / 2, 3 * t + t / 2, Buttons.LEFT_BUTTON),
        Click(4 * t + t / 2, 3 * t / 2, Buttons.LEFT_BUTTON),
        Click(4 * t + t / 2, 3 * t / 2, Buttons.RIGHT_BUTTON),
        Click(t + t / 2, t / 2, Buttons.LEFT_BUTTON),
        Click(3 * t + t / 2, t / 2, Buttons.LEFT_BUTTON),
        Click(t + t / 2, 4 * t + t / 2, Buttons.LEFT_BUTTON),
    )

    /**
     * Compares one click with the oracle. The oracle takes view
     * coordinates and subtracts the letterbox origin, so a game point is
     * shifted by [View.originX] and [View.originY].
     */
    private fun checkClick(
        oracle: OracleClient,
        save: String,
        view: View,
        click: Click,
        expectedOutcome: KeyOutcome,
        label: String,
    ) {
        val expected = oracle.pointsSized(
            save,
            listOf(
                OracleClient.Point(
                    view.originX + click.x,
                    view.originY + click.y,
                    click.button,
                ),
            ),
            view.viewWidth,
            view.viewHeight,
        )
        val midend = Midend.fromSave(Netslide, SgtpSave.parse(save))
        val before = midend.state as Netslide.State
        val tilesBefore = before.tiles.toList()

        assertEquals(
            "$label outcome",
            expectedOutcome,
            midend.processKey(click.x, click.y, click.button, view.tile),
        )
        assertEquals("$label save", expected.state.state, midend.serialise().serialise())
        assertEquals("$label id", expected.state.id, midend.id())
        assertEquals("$label status", expected.state.status, midend.status())

        val after = midend.state as Netslide.State
        if (expectedOutcome == KeyOutcome.SomeEffect) {
            assertNotEquals("$label changes the board", tilesBefore, after.tiles.toList())
            assertEquals("$label adds one move", before.moveCount + 1, after.moveCount)
            assertEquals("$label adds one state", 2, midend.stateCount)
            assertEquals("$label records one move", 1, midend.serialise().values("MOVE").size)
        } else {
            assertEquals("$label keeps the board", tilesBefore, after.tiles.toList())
            assertEquals("$label keeps the move count", before.moveCount, after.moveCount)
            assertEquals("$label adds no state", 1, midend.stateCount)
            assertEquals("$label records no move", emptyList<String>(), midend.serialise().values("MOVE"))
        }
    }

    private fun movesOf(state: String): List<String> = SgtpSave.parse(state).values("MOVE")

    private companion object {
        const val SEED = "12345"
    }
}
