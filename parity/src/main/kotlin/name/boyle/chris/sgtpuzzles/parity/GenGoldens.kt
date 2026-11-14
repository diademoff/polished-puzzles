package name.boyle.chris.sgtpuzzles.parity

import java.io.File
import java.security.MessageDigest

/**
 * Freezes `params + seed -> DESC` for every game.
 *
 * Usage (normally through tools/gen-goldens.sh):
 *
 *   genGoldens <testdata dir> <UPSTREAM.lock>
 *
 * Output:
 *
 *   testdata/golden/<game>/desc.json
 *   testdata/manifest.json
 *
 * The output is deterministic: games and entries are sorted, there are
 * no timestamps, and the seed is fixed. Review the diff before you
 * commit it, and never regenerate a golden to make a red test green.
 */
object GenGoldens {

    const val SEED = "12345"
    const val PROTOCOL = 1

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: genGoldens <testdata dir> <UPSTREAM.lock>" }
        val testdata = File(args[0])
        val lock = File(args[1])
        val upstreamCommit = UpstreamLock.commit(lock)

        // The output must be a clean function of the current oracle.
        File(testdata, "golden").deleteRecursively()

        val files = sortedMapOf<String, String>()

        OracleClient().use { oracle ->
            val games = oracle.games().sorted()
            for (game in games) {
                val params = linkedSetOf("")
                oracle.presets(game).mapTo(params) { it.params }

                val entries = params.sorted().map { p ->
                    val state = try {
                        oracle.newGame(game, p, SEED)
                    } catch (e: OracleException) {
                        throw IllegalStateException("$game params='$p' seed=$SEED: ${e.message}", e)
                    }
                    Entry(p, state.id)
                }

                val text = renderGame(game, upstreamCommit, entries)
                val relative = "golden/$game/desc.json"
                val file = File(testdata, relative)
                file.parentFile.mkdirs()
                file.writeText(text)
                files[relative] = sha256(text)
            }
        }

        val manifest = renderManifest(upstreamCommit, files)
        File(testdata, "manifest.json").writeText(manifest)

        println("goldens: ${files.size} games, upstream ${upstreamCommit.take(7)}")
    }

    data class Entry(val params: String, val id: String)

    private fun renderGame(game: String, upstreamCommit: String, entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"game\": ").append(Json.quote(game)).append(",\n")
        sb.append("  \"seed\": ").append(Json.quote(SEED)).append(",\n")
        sb.append("  \"level\": \"semantic\",\n")
        sb.append("  \"oracle_protocol\": ").append(PROTOCOL).append(",\n")
        sb.append("  \"upstream_commit\": ").append(Json.quote(upstreamCommit)).append(",\n")
        sb.append("  \"entries\": [\n")
        entries.forEachIndexed { index, entry ->
            sb.append("    {\"params\": ").append(Json.quote(entry.params))
            sb.append(", \"id\": ").append(Json.quote(entry.id)).append('}')
            sb.append(if (index == entries.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderManifest(upstreamCommit: String, files: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"oracle_protocol\": ").append(PROTOCOL).append(",\n")
        sb.append("  \"upstream_commit\": ").append(Json.quote(upstreamCommit)).append(",\n")
        sb.append("  \"files\": {\n")
        files.entries.forEachIndexed { index, (path, hash) ->
            sb.append("    ").append(Json.quote(path)).append(": ").append(Json.quote(hash))
            sb.append(if (index == files.size - 1) "\n" else ",\n")
        }
        sb.append("  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
