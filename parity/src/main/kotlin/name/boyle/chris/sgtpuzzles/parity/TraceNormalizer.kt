package name.boyle.chris.sgtpuzzles.parity

/**
 * Canonical form of a draw trace, `traceSchema: 1`.
 *
 * The trace contract is in `docs/adr/0003-strict-draw-traces.md`. The
 * oracle and the future Compose renderer both produce a JSON trace; this
 * object validates it and writes one operation per line, so a golden is
 * readable in a diff.
 *
 * Numbers are already integers in milli-units. The normalizer rejects a
 * malformed trace instead of guessing: an unknown operation, a dangling
 * blitter, an unbalanced clip, or an unbalanced draw is a producer bug.
 */
object TraceNormalizer {

    const val SCHEMA = 1

    fun normalize(trace: JsonValue.Obj, upstream: String? = null): String {
        val schema = int(trace, "traceSchema")
        require(schema == SCHEMA) {
            "unsupported traceSchema $schema; this code reads $SCHEMA"
        }
        val out = StringBuilder()
        out.append("traceSchema ").append(SCHEMA).append('\n')
        out.append("game ").append(Json.quote(string(trace, "game"))).append('\n')
        out.append("id ").append(Json.quote(string(trace, "id"))).append('\n')
        out.append("params ").append(Json.quote(string(trace, "params"))).append('\n')
        if (upstream != null) {
            out.append("upstream ").append(Json.quote(upstream)).append('\n')
        }

        val width = int(trace, "w")
        val height = int(trace, "h")
        require(width > 0 && height > 0) { "trace size must be positive: $width x $height" }
        out.append("size ").append(width).append(' ').append(height).append('\n')

        val palette = array(trace, "palette")
        require(palette.isNotEmpty()) { "trace palette is empty" }
        out.append("palette")
        for (entry in palette) {
            val rgb = (entry as? JsonValue.Arr)?.items
                ?: throw IllegalArgumentException("palette entry is not an array")
            require(rgb.size == 3) { "palette entry must have three channels: $rgb" }
            val channels = rgb.map { channel ->
                val value = (channel as? JsonValue.Num)?.value
                    ?: throw IllegalArgumentException("palette channel is not a number: $channel")
                require(value in 0..1000) { "palette channel $value is outside 0..1000" }
                value
            }
            out.append(' ').append(channels.joinToString(","))
        }
        out.append('\n')

        val ops = array(trace, "ops")
        require(ops.isNotEmpty()) { "trace has no operations" }

        val liveBlitters = mutableSetOf<Long>()
        var clipDepth = 0
        var drawing = false

        for ((index, item) in ops.withIndex()) {
            val op = item as? JsonValue.Obj
                ?: throw IllegalArgumentException("operation $index is not an object")
            val name = string(op, "op")
            val line = when (name) {
                "start_draw" -> {
                    require(!drawing) { "start_draw at $index while already drawing" }
                    drawing = true
                    "start_draw"
                }
                "end_draw" -> {
                    require(drawing) { "end_draw at $index without start_draw" }
                    drawing = false
                    "end_draw"
                }
                "text" -> listOf(
                    "text", int(op, "x"), int(op, "y"), int(op, "font"),
                    int(op, "size"), int(op, "align"), int(op, "c"),
                    Json.quote(string(op, "text")),
                ).joinToString(" ")

                "rect" -> listOf(
                    "rect", int(op, "x"), int(op, "y"), int(op, "w"),
                    int(op, "h"), int(op, "c"),
                ).joinToString(" ")

                "line" -> listOf(
                    "line", int(op, "x1"), int(op, "y1"), int(op, "x2"),
                    int(op, "y2"), int(op, "c"),
                ).joinToString(" ")

                "poly" -> polygon("poly", op, withThickness = null)
                "thick_poly" -> polygon("thick_poly", op, withThickness = int(op, "t"))

                "circle" -> listOf(
                    "circle", int(op, "cx"), int(op, "cy"), int(op, "r"),
                    int(op, "fill"), int(op, "outline"),
                ).joinToString(" ")

                "thick_circle" -> listOf(
                    "thick_circle", int(op, "t"), int(op, "cx"), int(op, "cy"),
                    int(op, "r"), int(op, "fill"), int(op, "outline"),
                ).joinToString(" ")

                "update" -> listOf(
                    "update", int(op, "x"), int(op, "y"), int(op, "w"), int(op, "h"),
                ).joinToString(" ")

                "clip" -> {
                    clipDepth++
                    listOf(
                        "clip", int(op, "x"), int(op, "y"), int(op, "w"), int(op, "h"),
                    ).joinToString(" ")
                }
                "unclip" -> {
                    require(clipDepth > 0) { "unclip at $index without clip" }
                    clipDepth--
                    "unclip"
                }

                "status" -> "status " + Json.quote(string(op, "text"))

                "blitter_new" -> {
                    val id = long(op, "id")
                    require(id > 0) { "blitter id must be positive: $id" }
                    require(liveBlitters.add(id)) { "blitter $id is allocated twice" }
                    val w = int(op, "w")
                    val h = int(op, "h")
                    require(w > 0 && h > 0) { "blitter $id has an empty size: $w x $h" }
                    "blitter_new $id $w $h"
                }
                "blitter_free" -> {
                    val id = liveBlitter(op, "blitter_free", liveBlitters)
                    liveBlitters.remove(id)
                    "blitter_free $id"
                }
                "blitter_save" -> {
                    val id = liveBlitter(op, "blitter_save", liveBlitters)
                    "blitter_save $id ${int(op, "x")} ${int(op, "y")}"
                }
                "blitter_load" -> {
                    val id = liveBlitter(op, "blitter_load", liveBlitters)
                    "blitter_load $id ${int(op, "x")} ${int(op, "y")}"
                }

                "line_width" -> "line_width " + int(op, "t")
                "line_dotted" -> "line_dotted " + bool(op, "dotted")
                "changed_state" ->
                    "changed_state ${bool(op, "canundo")} ${bool(op, "canredo")}"
                "purging_states" -> "purging_states"
                "thick_line" -> listOf(
                    "thick_line", int(op, "t"), int(op, "x1"), int(op, "y1"),
                    int(op, "x2"), int(op, "y2"), int(op, "c"),
                ).joinToString(" ")
                "inertia_follow" -> "inertia_follow " + bool(op, "solved")

                else -> throw IllegalArgumentException(
                    "unknown trace operation '$name' at $index; bump traceSchema",
                )
            }
            out.append(line).append('\n')
        }

        require(!drawing) { "trace ends inside start_draw" }
        require(clipDepth == 0) { "trace ends with $clipDepth open clip region(s)" }
        return out.toString()
    }

    private fun polygon(name: String, op: JsonValue.Obj, withThickness: Int?): String {
        val coords = array(op, "pts").map { item ->
            (item as? JsonValue.Num)?.value
                ?: throw IllegalArgumentException("$name point is not a number: $item")
        }
        require(coords.size % 2 == 0) { "$name has an odd number of coordinates" }
        require(coords.isNotEmpty()) { "$name has no points" }

        val out = StringBuilder(name)
        if (withThickness != null) out.append(' ').append(withThickness)
        out.append(' ').append(int(op, "fill"))
        out.append(' ').append(int(op, "outline"))
        out.append(' ').append(coords.size / 2)
        for (value in coords) out.append(' ').append(value)
        return out.toString()
    }

    private fun liveBlitter(op: JsonValue.Obj, name: String, live: Set<Long>): Long {
        val id = long(op, "id")
        require(id in live) { "$name refers to blitter $id, which is not allocated" }
        return id
    }

    private fun long(op: JsonValue.Obj, key: String): Long {
        val value = op.field(key) as? JsonValue.Num
            ?: throw IllegalArgumentException("operation '${op.field("op")}' needs a number '$key'")
        return value.value
    }

    private fun int(op: JsonValue.Obj, key: String): Int {
        val value = long(op, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "'$key' is outside the int range: $value" }
        return value.toInt()
    }

    private fun string(op: JsonValue.Obj, key: String): String {
        val value = op.field(key) as? JsonValue.Str
            ?: throw IllegalArgumentException("operation '${op.field("op")}' needs a string '$key'")
        return value.value
    }

    private fun bool(op: JsonValue.Obj, key: String): Boolean {
        val value = op.field(key) as? JsonValue.Bool
            ?: throw IllegalArgumentException("operation '${op.field("op")}' needs a boolean '$key'")
        return value.value
    }

    private fun array(op: JsonValue.Obj, key: String): List<JsonValue> {
        val value = op.field(key) as? JsonValue.Arr
            ?: throw IllegalArgumentException("field '$key' is not an array: ${op.fields}")
        return value.items
    }
}
