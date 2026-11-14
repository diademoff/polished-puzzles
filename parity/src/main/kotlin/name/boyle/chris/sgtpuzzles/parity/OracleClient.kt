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
            // The engine reads tuning variables from the environment.
            // Goldens must not depend on the machine, so remove every
            // variable that changes presets, defaults, colours, or
            // drawing. The list comes from getenv/getenv_bool in the
            // vendored tree.
            environment().keys.removeIf {
                it.endsWith("_PRESETS") ||
                    it.endsWith("_DEFAULT") ||
                    it.contains("_COLOUR_") ||
                    it == "DEBUG_PUZZLES" ||
                    it.startsWith("PUZZLES_") ||
                    it.startsWith("FIFTEEN_") ||
                    it.startsWith("FLOOD_") ||
                    it.startsWith("LIGHTUP_") ||
                    it.startsWith("LOOPY_") ||
                    it.startsWith("MAP_ALTERNATIVE_") ||
                    it.startsWith("PEARL_") ||
                    it.startsWith("RANGE_") ||
                    it.startsWith("SIGNPOST_") ||
                    it.startsWith("SLANT_") ||
                    it.startsWith("TOWERS_")
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

    /** Re-reads a save file. Fails when upstream rejects the save. */
    fun id(state: String): GameState = GameState.of(request(mapOf("op" to "id", "state" to state)))

    fun undo(state: String): GameState = GameState.of(request(mapOf("op" to "undo", "state" to state)))

    fun redo(state: String): GameState = GameState.of(request(mapOf("op" to "redo", "state" to state)))

    fun solve(state: String): GameState = GameState.of(request(mapOf("op" to "solve", "state" to state)))

    fun status(state: String): GameState = GameState.of(request(mapOf("op" to "status", "state" to state)))

    data class TreeResult(val results: List<Int>, val items: List<String>)

    /** Runs a sorted `tree234.c` script and returns every operation result. */
    fun tree(ops: String): TreeResult {
        val response = request(mapOf("op" to "tree", "ops" to ops))
        return TreeResult(
            results = response.numberList("results").map { it.toInt() },
            items = response.stringList("items"),
        )
    }

    data class MatchingResult(
        val count: Int,
        val outL: IntArray,
        val outR: IntArray,
        val adjacency: Array<IntArray>,
    ) {
        override fun equals(other: Any?): Boolean =
            other is MatchingResult &&
                count == other.count &&
                outL.contentEquals(other.outL) &&
                outR.contentEquals(other.outR) &&
                adjacency.size == other.adjacency.size &&
                adjacency.indices.all { adjacency[it].contentEquals(other.adjacency[it]) }

        override fun hashCode(): Int = count
    }

    /** Runs `matching.c`; [adjacency] is one list of right vertices per left vertex. */
    fun matching(
        leftCount: Int,
        rightCount: Int,
        adjacency: List<List<Int>>,
        seed: String? = null,
    ): MatchingResult {
        val fields = linkedMapOf<String, Any>(
            "op" to "matching",
            "nl" to leftCount,
            "nr" to rightCount,
            "adj" to adjacency.joinToString(";") { it.joinToString(" ") },
        )
        if (seed != null) fields["seed"] = seed
        val response = request(fields)
        val adjText = response.string("adj")
        val lists = if (leftCount == 0) {
            emptyArray()
        } else {
            adjText.split(';').map { part ->
                part.split(' ').filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
            }.toTypedArray()
        }
        return MatchingResult(
            count = response.number("count").toInt(),
            outL = response.numberList("outL").map { it.toInt() }.toIntArray(),
            outR = response.numberList("outR").map { it.toInt() }.toIntArray(),
            adjacency = lists,
        )
    }

    data class LoopResult(
        val anyLoop: Boolean,
        val loopEdges: List<Pair<Int, Int>>,
        val bridges: List<Bridge>,
    )

    data class Bridge(val u: Int, val v: Int, val uVertices: Int, val vVertices: Int)

    /** Runs `findloop.c` on an undirected edge list such as "0-1 1-2". */
    fun findLoop(n: Int, edges: String): LoopResult {
        val response = request(mapOf("op" to "findloop", "n" to n, "edges" to edges))
        val loopEdges = response.stringList("loopEdges").map { text ->
            val (u, v) = text.split(',').map { it.toInt() }
            u to v
        }
        val bridges = response.stringList("bridges").map { text ->
            val (u, v, uv, vv) = text.split(',').map { it.toInt() }
            Bridge(u, v, uv, vv)
        }
        return LoopResult(response.bool("anyLoop"), loopEdges, bridges)
    }

    data class DsfState(
        val roots: List<Int>,
        val sizes: List<Int>,
        val mins: List<Int>,
        val inverses: List<Int>,
    )

    /** Runs a `dsf.c` merge script and returns the state of every element. */
    fun dsf(kind: String, n: Int, ops: String): DsfState {
        val response = request(mapOf("op" to "dsf", "kind" to kind, "n" to n, "ops" to ops))
        return DsfState(
            roots = response.numberList("roots").map { it.toInt() },
            sizes = response.numberList("sizes").map { it.toInt() },
            mins = response.numberList("mins").map { it.toInt() },
            inverses = response.numberList("inverses").map { it.toInt() },
        )
    }

    /** Runs a `tdq.c` script and returns every removal, including -1. */
    fun tdq(n: Int, ops: String): List<Int> =
        request(mapOf("op" to "tdq", "n" to n, "ops" to ops))
            .numberList("removed")
            .map { it.toInt() }

    /** Upstream `arraysort()` order for an integer list. */
    fun sort(values: List<Long>, mode: String): List<Long> =
        request(
            mapOf(
                "op" to "sort",
                "values" to values.joinToString(" "),
                "mode" to mode,
            ),
        ).numberList("values")

    /** Upstream `combi.c` order for every `r`-subset of `0 until n`. */
    fun combinations(r: Int, n: Int): List<List<Int>> =
        request(mapOf("op" to "combi", "r" to r, "n" to n))
            .stringList("combinations")
            .map { text -> text.split(',').map { it.toInt() } }

    /** `random_bits` from a seed or from an encoded generator state. */
    fun randomBits(
        bits: Int,
        count: Int,
        seed: String? = null,
        state: String? = null,
    ): RandomStream = randomStream("randomBits", seed, state, "bits" to bits, "count" to count)

    /** `random_upto` from a seed or from an encoded generator state. */
    fun randomUpto(
        limit: Long,
        count: Int,
        seed: String? = null,
        state: String? = null,
    ): RandomStream = randomStream("randomUpto", seed, state, "limit" to limit, "count" to count)

    private fun randomStream(
        op: String,
        seed: String?,
        state: String?,
        vararg extra: Pair<String, Any>,
    ): RandomStream {
        val fields = linkedMapOf<String, Any>("op" to op)
        if (seed != null) fields["seed"] = seed
        if (state != null) fields["state"] = state
        for ((key, value) in extra) fields[key] = value
        val response = request(fields)
        val values = (response.field("values") as JsonValue.Arr).items.map {
            (it as JsonValue.Num).value
        }
        return RandomStream(values, response.string("state"))
    }

    fun text(state: String): String = request(mapOf("op" to "text", "state" to state)).string("text")

    data class RequestKeys(val arrowMode: Int, val labels: List<KeyLabel>)

    data class KeyLabel(val label: String, val button: Int, val needsArrows: Boolean)

    /** `midend_request_keys`: the extra keys and the arrow mode. */
    fun requestKeys(state: String): RequestKeys {
        val response = request(mapOf("op" to "requestkeys", "state" to state))
        val labels = (response.field("labels") as JsonValue.Arr).items.map {
            val item = it as JsonValue.Obj
            KeyLabel(item.string("label"), item.number("button").toInt(), item.bool("needsArrows"))
        }
        return RequestKeys(response.number("arrowMode").toInt(), labels)
    }

    /**
     * Replays a sequence of key codes inside one midend. Cursor games
     * keep the cursor in the game_ui, which is not part of the save
     * file: separate `key` requests would reset it between keys.
     */
    fun keys(state: String, sequence: List<Int>): GameState =
        GameState.of(
            request(
                mapOf(
                    "op" to "keys",
                    "state" to state,
                    "seq" to sequence.joinToString(" "),
                ),
            ),
        )

    /**
     * Records one redraw of [state] as draw operations. The trace
     * contract is in `docs/adr/0003-strict-draw-traces.md`.
     */
    fun trace(state: String): JsonValue.Obj =
        request(mapOf("op" to "trace", "state" to state)).field("trace") as JsonValue.Obj

    /**
     * Creates a new game and records its first redraw in one request.
     *
     * This avoids the save-file round trip, which some upstream presets
     * cannot survive: for example `pegs 5x5random` serialises an invalid
     * long-term parameter string. A trace describes a game, a params
     * string, and a seed, so it does not need the save format.
     */
    fun traceNew(game: String, params: String, seed: String): JsonValue.Obj =
        request(
            mapOf(
                "op" to "traceNew",
                "game" to game,
                "params" to params,
                "seed" to seed,
            ),
        ).field("trace") as JsonValue.Obj

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

/** Values from one random stream, plus the encoded generator state after them. */
data class RandomStream(val values: List<Long>, val state: String)

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
