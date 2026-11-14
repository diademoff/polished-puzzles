package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: fifteen.
 *
 * Compares the Kotlin drawing primitives with the frozen trace and with
 * a fresh oracle trace. The trace is the whole drawing contract:
 * geometry, order, palette roles, text, clip, and blitter operations.
 */
class FifteenTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the Kotlin trace matches the frozen oracle trace`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val paramsList = oracle.presets("fifteen").map { it.params }.distinct().sorted()

        for (params in paramsList) {
            val midend = Midend.newGame(Fifteen, params, SEED)
            val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
            val expected = TraceNormalizer.normalize(
                oracle.traceNew("fifteen", params, SEED),
                upstream,
            )

            assertEquals("oracle and Kotlin differ for '$params'", expected, actual)

            val golden = File(testdata, "trace/fifteen/${GenTraces.fileName(params)}.trace")
            assertEquals("frozen trace for '$params'", golden.readText(), actual)
        }
    }

    private companion object {
        const val SEED = "12345"
    }
}
