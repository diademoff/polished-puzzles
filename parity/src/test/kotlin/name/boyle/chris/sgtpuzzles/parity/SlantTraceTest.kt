package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: slant.
 *
 * Compares the Kotlin drawing primitives with the frozen trace and with
 * a fresh oracle trace. Slant uses lines, circles, clips, and text, so
 * this is also the first strict check of those DrawSink operations.
 *
 * The cursor and the grounded fade live in `game_ui`, not in the save
 * file, so those cases send the same keys and preferences to both sides
 * inside one request.
 */
class SlantTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("slant").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Slant, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("slant", params, SEED),
                upstream,
            )

            if (expected != actual) {
                val e = expected.lines()
                val a = actual.lines()
                for (i in 0 until minOf(e.size, a.size)) {
                    if (e[i] != a[i]) {
                        org.junit.Assert.fail(
                            "first diff for '$params' line ${i + 1}:\n" +
                                "expected: ${e[i]}\nactual:   ${a[i]}",
                        )
                    }
                }
                org.junit.Assert.fail(
                    "length differs for '$params': expected ${e.size}, actual ${a.size}",
                )
            }

            val golden = File(testdata, "trace/slant/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    @Test
    fun `the cursor cell matches the oracle after a cursor key`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val params = "5x5dh"
        val tile = Slant.preferredTileSize
        val midend = Midend.newGame(Slant, params, SEED)
        val ui = midend.ui as Slant.Ui
        assertFalse("the cursor starts hidden", ui.curVisible)
        val save = midend.serialise().serialise()
        val id = midend.id()
        val canUndo = midend.canUndo
        val canRedo = midend.canRedo

        assertEquals(
            "the cursor key must be used",
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, tile),
        )
        assertTrue("the cursor must be visible", ui.curVisible)
        assertEquals("cursor x", 1, ui.curX)
        assertEquals("cursor y", 0, ui.curY)

        assertEquals("the cursor must not change the save", save, midend.serialise().serialise())
        assertEquals("the cursor must not change the id", id, midend.id())
        assertEquals("the cursor must not change undo", canUndo, midend.canUndo)
        assertEquals("the cursor must not change redo", canRedo, midend.canRedo)

        val actual = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
        val expected = TraceNormalizer.normalize(
            oracle.trace(
                midend.serialise().serialise(),
                sequence = listOf(Buttons.CURSOR_RIGHT),
            ),
            upstream,
        )
        assertEquals("cursor trace", expected, actual)

        // Cell (1, 0) is filled with COL_CURSOR, which is colour 6.
        val cursorRect = "rect ${2 * tile} $tile $tile $tile $CURSOR"
        assertTrue(
            "the cursor cell is not drawn: $cursorRect",
            actual.lines().any { it == cursorRect },
        )

        // The same state without a visible cursor must draw differently.
        val plain = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(Midend.newGame(Slant, params, SEED)),
            upstream,
        )
        assertFalse(
            "no cursor cell without a visible cursor",
            plain.lines().any { it == cursorRect },
        )
        assertNotEquals("the cursor must change the trace", plain, actual)
    }

    @Test
    fun `the grounded fade matches the oracle with the preference on and off`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val params = "5x5dh"
            val tile = Slant.preferredTileSize
            val midend = Midend.newGame(Slant, params, SEED)
            assertEquals(
                "the slash key must be used",
                KeyOutcome.SomeEffect,
                midend.processKey(0, 0, '/'.code, tile),
            )

            val state = midend.state as Slant.State
            val squares = state.params.width * state.params.height
            assertTrue(
                "the state must have a BORDER_EDGE error",
                (0 until squares).any { state.errors[it] and BORDER_EDGE != 0 },
            )

            val save = midend.serialise().serialise()
            val ui = midend.ui as Slant.Ui
            assertFalse("the fade starts off", ui.fadeGrounded)

            val off = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            assertEquals(
                "fade off",
                TraceNormalizer.normalize(oracle.trace(save), upstream),
                off,
            )
            assertFalse(
                "no grounded line is drawn when the fade is off",
                off.lines().any { it.startsWith("line ") && it.endsWith(" $GROUNDED") },
            )

            ui.fadeGrounded = true
            val on = TraceNormalizer.normalize(KotlinTraceSink.stateTrace(midend), upstream)
            assertEquals(
                "fade on",
                TraceNormalizer.normalize(
                    oracle.trace(save, prefs = "fade-grounded=true\n"),
                    upstream,
                ),
                on,
            )
            assertNotEquals("the fade must change the trace", off, on)
            assertTrue(
                "no grounded line is drawn when the fade is on",
                on.lines().any { it.startsWith("line ") && it.endsWith(" $GROUNDED") },
            )
        }

    private companion object {
        const val SEED = "12345"

        /** `COL_CURSOR` from the Slant palette, and the tile fill it selects. */
        const val CURSOR = 6

        /** `COL_GROUNDED` from the Slant palette. */
        const val GROUNDED = 8

        /** `BORDER_EDGE` from `slant.c`; it is not part of the save file. */
        const val BORDER_EDGE = 4
    }
}
