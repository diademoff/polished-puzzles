package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict. Every game and preset.
 *
 * Compares the oracle draw trace with `testdata/trace/<game>/<params>.trace`.
 * The compared stream is the whole drawing contract: coordinates,
 * palette roles, text, order, clips, and blitters. See
 * `docs/adr/0003-strict-draw-traces.md`.
 *
 * A difference is one of three things:
 *
 * 1. the oracle build changed (compiler, compile definitions, source);
 * 2. upstream changed a game's drawing;
 * 3. a golden was edited by hand.
 *
 * Find the cause. Do not regenerate a trace to make this test pass.
 */
class StrictTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the frozen traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        for (game in oracle.games().sorted()) {
            val dir = File(testdata, "trace/$game")
            assertTrue("missing trace directory for $game", dir.isDirectory)

            val files = dir.listFiles()
                ?.filter { it.isFile && it.extension == "trace" }
                ?.sortedBy { it.name }
                ?: emptyList()
            assertTrue("$game has no trace goldens", files.isNotEmpty())

            val expectedParams = oracle.presets(game).map { it.params }.distinct().sorted()
            val frozenParams = files.map { paramsOf(it) }.sorted()
            assertEquals(
                "$game: every preset needs a trace",
                expectedParams,
                frozenParams,
            )

            for (file in files) {
                val params = paramsOf(file)
                val actual = TraceNormalizer.normalize(
                    oracle.traceNew(game, params, GenTraces.SEED),
                    upstream,
                )
                assertEquals("trace for $game '$params'", file.readText(), actual)
            }
        }
    }

    private fun paramsOf(file: File): String {
        val line = file.useLines { lines ->
            lines.firstOrNull { it.startsWith("params ") }
        } ?: error("${file.path} has no params line")
        return (Json.parse(line.removePrefix("params ")) as JsonValue.Str).value
    }
}
