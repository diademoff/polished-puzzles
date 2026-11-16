package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game:
 * tracks.
 *
 * The thirteen frozen initial saves round-trip byte for byte in both
 * directions. Tracks has no `UI` record, no `AUXINFO`, and no
 * `PRIVDESC`; the parser rebuilds errors and completion from the move
 * history, and the flash field is absent until the next move.
 */
class TracksSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Tracks.name }
                .sorted()
                .toList()
            assertEquals("twelve presets plus the default", 13, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Tracks, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals(
                    "${file.name} text",
                    oracle.text(text),
                    Tracks.textFormat(midend.state),
                )

                val save = SgtpSave.parse(text)
                assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertNull("${file.name} PRIVDESC", save.value("PRIVDESC"))
                assertNull("${file.name} UI", save.value("UI"))
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )
            }

            // The split is part of the contract: the long-term
            // parameters are always `...dt`, and the id has no `d`.
            val tenEasy = File(testdata, "saves/initial/tracks/10x10de.sgtp").readText()
            val save = SgtpSave.parse(tenEasy)
            assertEquals("10x10dt", save.value("PARAMS"))
            assertEquals("10x10de", save.value("CPARAMS"))
            assertTrue(Midend.fromSave(Tracks, save).id().startsWith("10x10:"))
        }

    @Test(timeout = 300_000)
    fun `a Kotlin save for every preset and seed loads in the oracle`() =
        OracleClient().use { oracle ->
            val seeds = listOf(
                "1", "2", "3", "12345", "seed", "another seed", "0", "-7", "",
            )
            for (params in Tracks.presets().map { it.params } + listOf("4x4dt", "8x8dto")) {
                for (seed in seeds) {
                    val midend = Midend.newGame(Tracks, params, seed)
                    val text = midend.serialise().serialise()
                    val loaded = oracle.id(text)
                    assertEquals("$params/$seed id", midend.id(), loaded.id)
                    assertEquals("$params/$seed canonical text", text, loaded.state)
                    assertEquals("$params/$seed status", midend.status(), loaded.status)
                    assertFalse("$params/$seed starts with no undo", loaded.canUndo)
                    assertFalse("$params/$seed starts with no redo", loaded.canRedo)
                }
            }
        }

    @Test(timeout = 120_000)
    fun `a real click and a real drag round-trip in both directions`() =
        OracleClient().use { oracle ->
            val clicked = Midend.newGame(Tracks, "8x8dt", SEED)
            val geom = tileGeometry(33)
            // A square click at (2,2), then an edge click near the top
            // edge of (3,2).
            click(clicked, 2, 2, geom)
            clicked.processKey(
                (4 * geom.tile + geom.border + geom.tile / 2),
                (3 * geom.tile + geom.border + geom.tile / 8),
                Buttons.LEFT_BUTTON,
                geom.tileSize,
            )
            clicked.processKey(
                (4 * geom.tile + geom.border + geom.tile / 2),
                (3 * geom.tile + geom.border + geom.tile / 8),
                Buttons.LEFT_RELEASE,
                geom.tileSize,
            )
            assertTrue(clicked.stateCount >= 3)
            assertRoundTrip(oracle, clicked)

            val dragged = Midend.newGame(Tracks, "8x8dt", SEED)
            val y = 4
            dragged.processKey(cellX(1, geom), cellY(y, geom), Buttons.LEFT_BUTTON, geom.tileSize)
            for (x in 2..5) {
                dragged.processKey(
                    cellX(x, geom),
                    cellY(y, geom),
                    Buttons.LEFT_DRAG,
                    geom.tileSize,
                )
            }
            dragged.processKey(cellX(5, geom), cellY(y, geom), Buttons.LEFT_RELEASE, geom.tileSize)
            val move = SgtpSave.parse(dragged.serialise().serialise()).value("MOVE")
            assertTrue("a drag produces one multi-item move: $move", move!!.contains(";"))
            assertRoundTrip(oracle, dragged)
        }

    @Test(timeout = 120_000)
    fun `an undone position with a live redo branch round-trips`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Tracks, "8x8dt", SEED)
            val geom = tileGeometry(33)
            click(midend, 2, 2, geom)
            click(midend, 3, 2, geom)
            assertTrue(midend.undo())
            assertTrue(midend.canRedo)
            val text = midend.serialise().serialise()
            assertTrue(SgtpSave.parse(text).value("NSTATES")!!.toInt() >= 2)
            val loaded = oracle.id(text)
            assertEquals(midend.id(), loaded.id)
            assertEquals(text, loaded.state)
            assertEquals(midend.status(), loaded.status)
            assertTrue(loaded.canUndo)
            assertTrue(loaded.canRedo)
            assertRoundTrip(oracle, midend)
        }

    @Test(timeout = 300_000)
    fun `a solve and a restart round-trip`() = OracleClient().use { oracle ->
        val solved = Midend.newGame(Tracks, "8x8dt", SEED)
        assertTrue(solved.solve())
        assertEquals(1, solved.status())
        assertTrue(SgtpSave.parse(solved.serialise().serialise()).value("SOLVE")!!.startsWith("S"))
        assertRoundTrip(oracle, solved)

        val restarted = Midend.newGame(Tracks, "8x8dt", SEED)
        val geom = tileGeometry(33)
        click(restarted, 2, 2, geom)
        assertTrue(restarted.restart())
        assertEquals(0, restarted.status())
        val save = restarted.serialise()
        assertEquals(save.value("DESC"), save.value("RESTART"))
        assertTrue(restarted.stateCount >= 2)
        assertRoundTrip(oracle, restarted)
    }

    @Test(timeout = 120_000)
    fun `a completed save rebuilds completion and drops the flash field`() =
        OracleClient().use { oracle ->
            val solved = Midend.newGame(Tracks, "8x8dt", SEED)
            assertTrue(solved.solve())
            val desc = fullPathDesc(solved.state as Tracks.State)
            val initial = Midend.fromGameId(Tracks, "8x8:$desc")
            val text = withMove(initial.serialise().serialise(), "")
            val loaded = Midend.fromSave(Tracks, SgtpSave.parse(text))
            assertEquals(1, loaded.status())
            assertTrue(Tracks.textFormat(loaded.state).isNotEmpty())

            val marked = Tracks.executeMove(loaded.state, "") as Tracks.State
            assertTrue(marked.completed)
            // The path has 34 cells; the entrance stores position 0.
            val flashCells = (0 until 64).count {
                (marked.sflags[it] ushr 8) and 0xFF != 0
            }
            assertEquals("every path cell except the entrance has a flash position", 33, flashCells)
            assertEquals(0, marked.numErrors.sum())
            assertTrue(marked.sflags.none { it and 4 != 0 })

            // The flash field is not in DESC. A load replays the move
            // that completed the board, so it recomputes the same field.
            // A board built from the description alone has no move and
            // therefore no flash bits.
            val reloaded = Midend.fromSave(Tracks, SgtpSave.parse(markedSave(loaded, marked)))
            val reloadedState = reloaded.state as Tracks.State
            assertTrue(reloadedState.completed)
            assertEquals(33, (0 until 64).count {
                (reloadedState.sflags[it] ushr 8) and 0xFF != 0
            })
            val fresh = Midend.fromGameId(Tracks, "8x8:$desc").state as Tracks.State
            assertFalse(fresh.completed)
            assertEquals(0, (0 until 64).count {
                (fresh.sflags[it] ushr 8) and 0xFF != 0
            })

            // The seam has no flash time, so completion and the flash
            // field never change the drawn frame.
            assertEquals(
                "the cold frame is identical",
                TraceNormalizer.normalize(KotlinTraceSink.stateTrace(loaded)),
                TraceNormalizer.normalize(KotlinTraceSink.stateTrace(reloaded)),
            )
            assertEquals(
                "a fresh board draws the same frame",
                TraceNormalizer.normalize(KotlinTraceSink.stateTrace(loaded)),
                TraceNormalizer.normalize(KotlinTraceSink.stateTrace(Midend.fromGameId(Tracks, "8x8:$desc"))),
            )
            assertTrue(Tracks.textFormat(reloadedState) == Tracks.textFormat(marked))

            val cState = oracle.id(markedSave(loaded, marked))
            assertEquals(1, cState.status)
            assertEquals(oracle.text(markedSave(loaded, marked)), Tracks.textFormat(reloadedState))
        }

    private fun markedSave(base: Midend, marked: Tracks.State): String =
        withMove(base.serialise().serialise(), "")

    private fun assertRoundTrip(oracle: OracleClient, midend: Midend) {
        val text = midend.serialise().serialise()
        val loaded = oracle.id(text)
        assertEquals("id", midend.id(), loaded.id)
        assertEquals("canonical text", text, loaded.state)
        assertEquals("status", midend.status(), loaded.status)
        assertEquals("can undo", midend.canUndo, loaded.canUndo)
        assertEquals("can redo", midend.canRedo, loaded.canRedo)
        assertEquals("text", oracle.text(text), Tracks.textFormat(midend.state))
        val reloaded = Midend.fromSave(Tracks, SgtpSave.parse(loaded.state))
        assertEquals(text, reloaded.serialise().serialise())
        assertEquals(midend.id(), reloaded.id())
    }

    private data class Geom(val tileSize: Int, val tile: Int, val border: Int)

    private fun tileGeometry(tileSize: Int): Geom {
        val tile = (tileSize / 6) * 6
        val maxBorder = tile / 8
        var border = maxBorder
        if (border <= 5) border = minOf(tileSize % 6, maxBorder)
        return Geom(tileSize, tile, border)
    }

    private fun cellX(x: Int, geom: Geom): Int =
        (x + 1) * geom.tile + geom.border + geom.tile / 2

    private fun cellY(y: Int, geom: Geom): Int =
        (y + 1) * geom.tile + geom.border + geom.tile / 2

    private fun click(midend: Midend, x: Int, y: Int, geom: Geom) {
        midend.processKey(cellX(x, geom), cellY(y, geom), Buttons.LEFT_BUTTON, geom.tileSize)
        midend.processKey(cellX(x, geom), cellY(y, geom), Buttons.LEFT_RELEASE, geom.tileSize)
    }

    private fun fullPathDesc(state: Tracks.State): String {
        val w = state.width
        val h = state.height
        val out = StringBuilder()
        for (i in 0 until w * h) {
            val f = (state.sflags[i] ushr 16) and 15
            if (f != 0) {
                out.append(if (f < 10) '0' + f else 'A' + (f - 10))
            } else {
                out.append('a')
            }
        }
        for (x in 0 until w) {
            out.append(',')
            if (x == state.numbers.colS) out.append('S')
            out.append(state.numbers.values[x])
        }
        for (y in 0 until h) {
            out.append(',')
            if (y == state.numbers.rowS) out.append('S')
            out.append(state.numbers.values[w + y])
        }
        return out.toString()
    }

    private fun withMove(save: String, move: String): String {
        val states = SgtpSave.parse(save).value("NSTATES")!!.toInt()
        return save
            .replace("NSTATES :1:$states", "NSTATES :1:${states + 1}")
            .replace("STATEPOS:1:$states", "STATEPOS:1:${states + 1}") +
            "MOVE    :${move.length}:$move\n"
    }

    private companion object {
        const val SEED = "12345"
    }
}
