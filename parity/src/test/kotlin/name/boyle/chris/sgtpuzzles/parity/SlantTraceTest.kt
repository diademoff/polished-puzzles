package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Level: strict. Game: slant.
 *
 * Compares the Kotlin drawing primitives with the frozen trace and with
 * a fresh oracle trace. Slant uses lines, circles, clips, and text, so
 * this is also the first strict check of those DrawSink operations.
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

    private companion object {
        const val SEED = "12345"
    }
}
