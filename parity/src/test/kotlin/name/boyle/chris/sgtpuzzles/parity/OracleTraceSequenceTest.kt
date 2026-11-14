package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Oracle protocol.
 *
 * A `trace` request with an explicit `seq` replays keys before the
 * recorded redraw. The keys may create a drawstate with blitters, and
 * the replacement drawstate frees it first. That free belongs to a
 * drawstate that is not in the trace, so the trace must start with
 * `blitter_new`, as a fresh trace does.
 */
class OracleTraceSequenceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `a sequence trace starts with a new blitter, not a stale free`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val params = oracle.presets("inertia").first().params
            val state = oracle.newGame("inertia", params, SEED).state

            val plain = oracle.trace(state)
            // CURSOR_DOWN moves the player, so the state and the trace
            // really change, and the test cannot pass on an ignored key.
            val moved = oracle.trace(state, sequence = listOf(Buttons.CURSOR_DOWN))

            val ops = moved.ops().map { it.string("op") }
            val firstDraw = ops.indexOf("start_draw")
            assertTrue("the trace must start a draw: ${ops.take(8)}", firstDraw >= 0)
            assertFalse(
                "a stale blitter_free comes before the first start_draw: " +
                    ops.take(firstDraw + 1),
                ops.take(firstDraw).contains("blitter_free"),
            )
            assertTrue(
                "the new drawstate must allocate a blitter: ${ops.take(firstDraw + 1)}",
                ops.take(firstDraw).contains("blitter_new"),
            )

            val plainTrace = TraceNormalizer.normalize(plain, upstream)
            val movedTrace = TraceNormalizer.normalize(moved, upstream)
            assertNotEquals("the sequence must change the trace", plainTrace, movedTrace)
        }

    private fun JsonValue.Obj.ops(): List<JsonValue.Obj> =
        (field("ops") as JsonValue.Arr).items.map { it as JsonValue.Obj }

    private companion object {
        const val SEED = "12345"
    }
}
