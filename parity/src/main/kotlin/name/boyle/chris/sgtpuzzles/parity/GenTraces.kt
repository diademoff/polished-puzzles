package name.boyle.chris.sgtpuzzles.parity

import java.io.File

/**
 * Freezes the strict draw trace of every preset of the games whose
 * renderer exists.
 *
 * Usage (normally through tools/gen-traces.sh):
 *
 *   genTraces <testdata dir> <UPSTREAM.lock>
 *
 * Output:
 *
 *   testdata/trace/<game>/<params>.trace
 *
 * The files are canonical text from [TraceNormalizer]. They are
 * deterministic: the seed is fixed, presets are sorted, and there are no
 * timestamps. The header carries the upstream commit. Review the diff
 * before you commit it, and never regenerate a trace to make a red test
 * green.
 */
object GenTraces {

    const val SEED = "12345"

    /** Games with a frozen trace. Add a game when its renderer lands. */
    val GAMES = listOf("fifteen")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: genTraces <testdata dir> <UPSTREAM.lock>" }
        val testdata = File(args[0])
        val upstream = UpstreamLock.commit(File(args[1]))
        val root = File(testdata, "trace")
        root.deleteRecursively()

        var files = 0
        OracleClient().use { oracle ->
            for (game in GAMES) {
                val presets = oracle.presets(game).map { it.params }.distinct().sorted()
                require(presets.isNotEmpty()) { "$game has no presets" }
                for (params in presets) {
                    val state = oracle.newGame(game, params, SEED)
                    val text = TraceNormalizer.normalize(oracle.trace(state.state), upstream)
                    val file = File(root, "$game/${fileName(params)}.trace")
                    file.parentFile.mkdirs()
                    file.writeText(text)
                    files++
                }
            }
        }
        println("traces: $files files for ${GAMES.joinToString()}, upstream ${upstream.take(7)}")
    }

    /** A params string can contain characters that are not file-safe. */
    fun fileName(params: String): String =
        params.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
