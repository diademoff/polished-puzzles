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

    data class Layout(val width: Int, val height: Int)

    /** `midend_size(..., user_size = true)` for a view of [width] x [height]. */
    fun layout(state: String, width: Int, height: Int): Layout {
        val response = request(
            mapOf("op" to "layout", "state" to state, "w" to width, "h" to height),
        )
        return Layout(response.number("w").toInt(), response.number("h").toInt())
    }

    /** Resizes to the view first, then sends one game-local key. */
    fun keySized(
        state: String,
        x: Int,
        y: Int,
        k: Int,
        width: Int,
        height: Int,
    ): GameState = GameState.of(
        request(
            mapOf(
                "op" to "keySized",
                "state" to state,
                "w" to width,
                "h" to height,
                "x" to x,
                "y" to y,
                "k" to k,
            ),
        ),
    )

    /**
     * Resizes to the view, then drags from `(x0, y0)` to `(x1, y1)`:
     * `DOWN`, [steps] interpolated `DRAG`s, then `UP`. This is the same
     * sequence the Android shell sends for a swipe.
     */
    fun gestureSized(
        state: String,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        steps: Int,
        width: Int,
        height: Int,
    ): GameState = GameState.of(
        request(
            mapOf(
                "op" to "gestureSized",
                "state" to state,
                "w" to width,
                "h" to height,
                "x0" to x0,
                "y0" to y0,
                "x1" to x1,
                "y1" to y1,
                "steps" to steps,
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

    /**
     * One pointer click in view coordinates. The oracle subtracts the
     * letterbox origin, as the Android shell does.
     */
    data class Point(val x: Int, val y: Int, val button: Int)

    data class PointResult(val state: GameState, val trace: JsonValue.Obj?)

    /**
     * Replays several pointer clicks inside one midend, so a select and
     * a remove see the same `game_ui`. When [width] and [height] are
     * given, the midend is sized to that viewport first. With
     * [recordTrace], the response also holds one final redraw whose
     * status line is captured exactly once.
     */
    fun pointsSized(
        state: String,
        points: List<Point>,
        width: Int? = null,
        height: Int? = null,
        recordTrace: Boolean = false,
    ): PointResult {
        val fields = linkedMapOf<String, Any>(
            "op" to "pointsSized",
            "state" to state,
            "points" to points.joinToString(";") { "${it.x},${it.y},${it.button}" },
        )
        if (width != null && height != null) {
            fields["w"] = width
            fields["h"] = height
        }
        if (recordTrace) fields["trace"] = 1
        val response = request(fields)
        return PointResult(
            state = GameState.of(response),
            trace = response.fields["trace"] as? JsonValue.Obj,
        )
    }

    /**
     * Applies [points] to one midend with recording off, then records
     * one redraw on the same drawstate. This is the warm path: the
     * first draw and the input update the cache and the blitter, and
     * the returned trace contains only the final redraw. Optional
     * [width] and [height] size the midend to a viewport and subtract
     * its letterbox origin, as [pointsSized] does.
     */
    fun traceWarm(
        state: String,
        points: List<Point>,
        width: Int? = null,
        height: Int? = null,
    ): PointResult {
        val fields = linkedMapOf<String, Any>(
            "op" to "traceWarm",
            "state" to state,
            "points" to points.joinToString(";") { "${it.x},${it.y},${it.button}" },
        )
        if (width != null && height != null) {
            fields["w"] = width
            fields["h"] = height
        }
        val response = request(fields)
        return PointResult(
            state = GameState.of(response),
            trace = response.fields["trace"] as? JsonValue.Obj,
        )
    }

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

    data class DivvyState(
        val roots: List<Int>,
        val sizes: List<Int>,
        val attempts: Int,
        val state: String,
    )

    /**
     * Runs `divvy_rectangle` from a seed or an encoded generator state.
     * The response carries the canonical element and class size of every
     * cell, the attempt count, and the encoded state after the call.
     */
    fun divvy(
        w: Int,
        h: Int,
        k: Int,
        seed: String? = null,
        state: String? = null,
    ): DivvyState {
        val fields = linkedMapOf<String, Any>("op" to "divvy", "w" to w, "h" to h, "k" to k)
        if (seed != null) fields["seed"] = seed
        if (state != null) fields["state"] = state
        val response = request(fields)
        return DivvyState(
            roots = response.numberList("roots").map { it.toInt() },
            sizes = response.numberList("sizes").map { it.toInt() },
            attempts = response.number("attempts").toInt(),
            state = response.string("state"),
        )
    }

    data class LayDominoState(
        val grid: List<Int>,
        val preallocGrid: List<Int>,
        val attempts: Int,
        val state: String,
        val preallocState: String,
    )

    /**
     * Runs `domino_layout` and, from the same seed, `domino_layout_prealloc`.
     * The response carries both grids, the attempt count (always one,
     * because laydomino never retries), and the encoded state after each
     * call.
     */
    fun layDomino(
        w: Int,
        h: Int,
        seed: String? = null,
        state: String? = null,
    ): LayDominoState {
        val fields = linkedMapOf<String, Any>("op" to "laydomino", "w" to w, "h" to h)
        if (seed != null) fields["seed"] = seed
        if (state != null) fields["state"] = state
        val response = request(fields)
        return LayDominoState(
            grid = response.numberList("grid").map { it.toInt() },
            preallocGrid = response.numberList("preallocGrid").map { it.toInt() },
            attempts = response.number("attempts").toInt(),
            state = response.string("state"),
            preallocState = response.string("preallocState"),
        )
    }

    /** One `latin_generate` or `latin_generate_rect` result. */
    data class LatinGenerated(
        val order: Int,
        val w: Int,
        val h: Int,
        val grid: List<Int>,
        val state: String,
    )

    /** One generic `latin_solver` run: the difficulty and the grid after it. */
    data class LatinSolved(val order: Int, val diff: Int, val grid: List<Int>)

    /** Runs `latin_generate` from a seed or an encoded generator state. */
    fun latinGenerate(o: Int, seed: String? = null, state: String? = null): LatinGenerated =
        latinGenerateRequest(mapOf("kind" to "generate", "o" to o), seed, state)

    /** Runs `latin_generate_rect` from a seed or an encoded generator state. */
    fun latinGenerateRect(
        w: Int,
        h: Int,
        seed: String? = null,
        state: String? = null,
    ): LatinGenerated =
        latinGenerateRequest(mapOf("kind" to "generateRect", "w" to w, "h" to h), seed, state)

    private fun latinGenerateRequest(
        fields: Map<String, Any>,
        seed: String?,
        state: String?,
    ): LatinGenerated {
        val body = linkedMapOf<String, Any>("op" to "latin")
        body.putAll(fields)
        if (seed != null) body["seed"] = seed
        if (state != null) body["state"] = state
        val response = request(body)
        return LatinGenerated(
            order = response.number("order").toInt(),
            w = response.number("w").toInt(),
            h = response.number("h").toInt(),
            grid = response.numberList("grid").map { it.toInt() },
            state = response.string("state"),
        )
    }

    /**
     * Runs the generic `latin_solver` with the five difficulty levels
     * equal to 0..4. The grid is modified in place by C, so the response
     * carries the grid after the call.
     */
    fun latinSolve(o: Int, grid: List<Int>, maxDiff: Int): LatinSolved {
        val response = request(
            mapOf(
                "op" to "latin",
                "kind" to "solve",
                "o" to o,
                "grid" to grid.joinToString(" "),
                "maxdiff" to maxDiff,
            ),
        )
        return LatinSolved(
            order = response.number("order").toInt(),
            diff = response.number("diff").toInt(),
            grid = response.numberList("grid").map { it.toInt() },
        )
    }

    /** Runs `latin_check`; true means the grid is accepted as a Latin square. */
    fun latinCheck(o: Int, grid: List<Int>): Boolean =
        request(
            mapOf(
                "op" to "latin",
                "kind" to "check",
                "o" to o,
                "grid" to grid.joinToString(" "),
            ),
        ).bool("isLatin")

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
    fun requestKeys(state: String, fullCursor: Boolean = false): RequestKeys {
        val fields = linkedMapOf<String, Any>("op" to "requestkeys", "state" to state)
        if (fullCursor) fields["fullCursor"] = 1
        val response = request(fields)
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
     *
     * An optional [prefs] sets game preferences first, in the
     * preferences file format, so a mode that only the game_ui holds
     * (for example palisade's `cursor-mode=full`) can be compared.
     */
    fun keys(
        state: String,
        sequence: List<Int>,
        prefs: String? = null,
    ): GameState {
        val fields = linkedMapOf<String, Any>(
            "op" to "keys",
            "state" to state,
            "seq" to sequence.joinToString(" "),
        )
        if (prefs != null) fields["prefs"] = prefs
        return GameState.of(request(fields))
    }

    /**
     * Records one redraw of [state] as draw operations. The trace
     * contract is in `docs/adr/0003-strict-draw-traces.md`.
     *
     * An optional [sequence] replays keys inside the recording midend,
     * so a trace can show frontend state such as a visible cursor. An
     * optional [prefs] sets game preferences first, in the preferences
     * file format (`kw=value` lines). Both fields are absent from a
     * plain trace, which therefore keeps its old behaviour.
     */
    fun trace(
        state: String,
        sequence: List<Int> = emptyList(),
        prefs: String? = null,
    ): JsonValue.Obj {
        val fields = linkedMapOf<String, Any>("op" to "trace", "state" to state)
        if (sequence.isNotEmpty()) fields["seq"] = sequence.joinToString(" ")
        if (prefs != null) fields["prefs"] = prefs
        return request(fields).field("trace") as JsonValue.Obj
    }

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
