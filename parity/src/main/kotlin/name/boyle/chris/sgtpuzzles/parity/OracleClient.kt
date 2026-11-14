package name.boyle.chris.sgtpuzzles.parity

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A client for the host oracle (tools/oracle).
 *
 * One oracle process serves one client. The protocol is stateless: a
 * request that needs a game carries the whole serialised save file.
 *
 * A dead or hung oracle is a test failure, not a skip. [request] throws
 * when the process exits, times out, or writes a malformed line.
 */
class OracleClient(
    command: List<String> = defaultCommand(),
) : AutoCloseable {

    companion object {
        /** A hung oracle must fail the test, not the CI job timeout. */
        private val TIMEOUT_SECONDS =
            System.getProperty("oracle.timeoutSeconds", "30").toLong()

        /** The oracle path comes from -Doracle, set by the Gradle build. */
        fun defaultCommand(): List<String> {
            val path = System.getProperty("oracle")
                ?: error("set -Poracle=build/oracle/puzzles-oracle")
            // A missing binary is a test failure, not a skip.
            check(java.io.File(path).canExecute()) {
                "oracle not found at $path; run tools/oracle/build.sh"
            }
            return listOf(path)
        }
    }

    private val process: Process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .apply {
            // The engine reads <GAME>_PRESETS and <GAME>_DEFAULT from the
            // environment. Goldens must not depend on the machine.
            environment().keys.removeIf {
                it.endsWith("_PRESETS") || it.endsWith("_DEFAULT")
            }
        }
        .start()
    private val input = BufferedReader(InputStreamReader(process.inputStream))
    private val output = BufferedWriter(OutputStreamWriter(process.outputStream))
    private val errors = process.errorStream.bufferedReader()
    private val reader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oracle-reader").apply { isDaemon = true }
    }

    fun games(): List<String> = request(mapOf("op" to "games")).stringList("games")

    fun presets(game: String): List<Preset> =
        (request(mapOf("op" to "presets", "game" to game)).field("presets") as JsonValue.Arr)
            .items.map {
                val preset = it as JsonValue.Obj
                Preset(preset.string("title"), preset.string("params"))
            }

    fun newGame(game: String, params: String, seed: String): GameState =
        GameState.of(
            request(
                mapOf(
                    "op" to "new",
                    "game" to game,
                    "params" to params,
                    "seed" to seed,
                ),
            ),
        )

    fun key(state: String, x: Int, y: Int, k: Int): GameState =
        GameState.of(
            request(
                mapOf(
                    "op" to "key",
                    "state" to state,
                    "x" to x,
                    "y" to y,
                    "k" to k,
                ),
            ),
        )

    fun undo(state: String): GameState = GameState.of(request(mapOf("op" to "undo", "state" to state)))

    fun redo(state: String): GameState = GameState.of(request(mapOf("op" to "redo", "state" to state)))

    fun solve(state: String): GameState = GameState.of(request(mapOf("op" to "solve", "state" to state)))

    fun status(state: String): GameState = GameState.of(request(mapOf("op" to "status", "state" to state)))

    fun text(state: String): String = request(mapOf("op" to "text", "state" to state)).string("text")

    /**
     * Sends one request and returns the response. Throws on an error
     * response. String values are quoted; Int and Long values are sent
     * as bare JSON numbers.
     */
    fun request(fields: Map<String, Any>): JsonValue.Obj {
        val line = fields.entries.joinToString(",", "{", "}") { (k, v) ->
            val encoded = when (v) {
                is Int, is Long -> v.toString()
                is String -> Json.quote(v)
                else -> error("unsupported request value for '$k': ${v::class}")
            }
            "${Json.quote(k)}:$encoded"
        }
        output.write(line)
        output.newLine()
        output.flush()

        val response = try {
            reader.submit(Callable { input.readLine() }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            process.destroyForcibly()
            throw IllegalStateException("oracle did not answer within $TIMEOUT_SECONDS s", e)
        } catch (e: ExecutionException) {
            throw IllegalStateException("oracle read failed", e.cause)
        } ?: throw IllegalStateException(
            "oracle exited without a response; stderr: ${readErrors()}",
        )

        val obj = Json.parse(response) as JsonValue.Obj
        if (obj.fields["ok"] == JsonValue.Bool(false)) {
            throw OracleException(obj.string("error"))
        }
        return obj
    }

    private fun readErrors(): String =
        if (errors.ready()) errors.readText() else "(empty)"

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        reader.shutdownNow()
    }
}

class OracleException(message: String) : RuntimeException(message)

data class Preset(val title: String, val params: String)

data class GameState(
    val state: String,
    val id: String,
    val game: String,
    val width: Int,
    val height: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val status: Int,
) {
    companion object {
        fun of(obj: JsonValue.Obj) = GameState(
            state = obj.string("state"),
            id = obj.string("id"),
            game = obj.string("game"),
            width = obj.number("w").toInt(),
            height = obj.number("h").toInt(),
            canUndo = obj.bool("canundo"),
            canRedo = obj.bool("canredo"),
            status = obj.number("status").toInt(),
        )
    }
}
