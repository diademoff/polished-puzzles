package name.boyle.chris.sgtpuzzles.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict, JVM. Every committed oracle trace.
 *
 * Feeds the canonical draw trace into [BoardRecorder], which implements
 * the same `ViewCallbacks` seam as the production renderer, and compares
 * the recorded operations with the trace. This proves that the Kotlin
 * model represents every primitive of every game and preset without a
 * device.
 *
 * Operations outside the drawing seam (`update`, `status`, line style,
 * and activity callbacks) are skipped; the engine handles them in the
 * activity, not in the renderer.
 */
class BoardRecorderTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every oracle trace round-trips through the renderer model`() {
        val files = File(testdata, "trace").walkTopDown()
            .filter { it.isFile && it.extension == "trace" }
            .sorted()
            .toList()
        assertTrue("no trace goldens under ${testdata.path}", files.isNotEmpty())

        for (file in files) {
            val expected = ArrayList<DrawOp>()
            val recorder = BoardRecorder()
            var frame: BoardFrame? = null
            var boardW = 0
            var boardH = 0
            recorder.onFrame = { frame = it }

            for (line in file.readLines()) {
                val fields = split(line)
                when (fields.firstOrNull()) {
                    "start_draw" -> recorder.startDraw()
                    "end_draw" -> recorder.endDraw()
                    "rect" -> recorder.fillRect(
                        fields.int(1), fields.int(2), fields.int(3), fields.int(4), fields.int(5),
                    ).also {
                        expected += DrawOp.FillRect(
                            fields.int(1), fields.int(2), fields.int(3), fields.int(4), fields.int(5),
                        )
                    }

                    "line" -> {
                        val thickness = 1f
                        val op = DrawOp.Line(
                            thickness,
                            fields.int(1).toFloat(),
                            fields.int(2).toFloat(),
                            fields.int(3).toFloat(),
                            fields.int(4).toFloat(),
                            fields.int(5),
                        )
                        expected += op
                        recorder.drawLine(op.thickness, op.x1, op.y1, op.x2, op.y2, op.colour)
                    }

                    "thick_line" -> {
                        val thickness = fields.int(1) / 1000f
                        val op = DrawOp.Line(
                            thickness,
                            fields.int(2).toFloat(),
                            fields.int(3).toFloat(),
                            fields.int(4).toFloat(),
                            fields.int(5).toFloat(),
                            fields.int(6),
                        )
                        expected += op
                        recorder.drawLine(op.thickness, op.x1, op.y1, op.x2, op.y2, op.colour)
                    }

                    "poly", "thick_poly" -> {
                        val thick = fields[0] == "thick_poly"
                        val base = if (thick) 1 else 0
                        val thickness = if (thick) fields.int(1) / 1000f else 1f
                        val fill = fields.int(base + 1)
                        val outline = fields.int(base + 2)
                        val count = fields.int(base + 3)
                        val points = (0 until count * 2).map { fields.int(base + 4 + it) }
                        val op = DrawOp.Poly(thickness, points, 0, 0, outline, fill)
                        expected += op
                        recorder.drawPoly(thickness, points.toIntArray(), 0, 0, outline, fill)
                    }

                    "circle", "thick_circle" -> {
                        val thick = fields[0] == "thick_circle"
                        val base = if (thick) 1 else 0
                        val thickness = if (thick) fields.int(1) / 1000f else 1f
                        val op = if (thick) {
                            DrawOp.Circle(
                                thickness,
                                fields.int(2) / 1000f,
                                fields.int(3) / 1000f,
                                fields.int(4) / 1000f,
                                fields.int(6),
                                fields.int(5),
                            )
                        } else {
                            DrawOp.Circle(
                                thickness,
                                fields.int(1).toFloat(),
                                fields.int(2).toFloat(),
                                fields.int(3).toFloat(),
                                fields.int(5),
                                fields.int(4),
                            )
                        }
                        expected += op
                        recorder.drawCircle(op.thickness, op.x, op.y, op.r, op.line, op.fill)
                    }

                    "text" -> {
                        val flags = fields.int(5) or (if (fields.int(3) == 0) 0x10 else 0)
                        val text = unquote(fields[7])
                        val op = DrawOp.Text(
                            fields.int(1), fields.int(2), flags, fields.int(4), fields.int(6), text,
                        )
                        expected += op
                        recorder.drawText(op.x, op.y, op.flags, op.size, op.colour, op.text)
                    }

                    "clip" -> {
                        val op = DrawOp.Clip(
                            fields.int(1), fields.int(2), fields.int(3), fields.int(4),
                        )
                        expected += op
                        recorder.clipRect(op.x, op.y, op.w, op.h)
                    }

                    "unclip" -> {
                        // The trace does not record the board bounds; the
                        // frontend restores the clip to the whole board.
                        val op = DrawOp.Unclip(0, 0, boardW, boardH)
                        expected += op
                        recorder.unClip(op.left, op.top, op.right, op.bottom)
                    }

                    "blitter_new" -> recorder.allocate(
                        fields.int(1), fields.int(2), fields.int(3),
                    )

                    "blitter_free" -> recorder.blitterFree(fields.int(1))
                    "blitter_save" -> recorder.blitterSave(
                        fields.int(1), fields.int(2), fields.int(3),
                    )

                    "blitter_load" -> {
                        val op = DrawOp.BlitterLoad(fields.int(1), fields.int(2), fields.int(3))
                        expected += op
                        recorder.blitterLoad(op.id, op.x, op.y)
                    }

                    "update", "status", "line_width", "line_dotted",
                    "changed_state", "purging_states", "inertia_follow" -> Unit

                    "size" -> {
                        boardW = fields.int(1)
                        boardH = fields.int(2)
                    }

                    else -> {
                        // Other header lines before start_draw.
                    }
                }
            }

            assertEquals("renderer model for ${file.path}", expected, frame?.ops)
        }
    }

    private fun List<String>.int(index: Int): Int = this[index].toInt()

    /** Splits a line into fields, keeping a quoted string as one field. */
    private fun split(line: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == ' ') {
                i++
                continue
            }
            val start = i
            if (line[i] == '"') {
                i++
                while (i < line.length) {
                    when (line[i]) {
                        '\\' -> i += 2
                        '"' -> {
                            i++
                            break
                        }
                        else -> i++
                    }
                }
            } else {
                while (i < line.length && line[i] != ' ') i++
            }
            out += line.substring(start, i)
        }
        return out
    }

    private fun unquote(value: String): String {
        require(value.length >= 2 && value.first() == '"' && value.last() == '"') {
            "not a quoted string: $value"
        }
        val out = StringBuilder()
        var i = 1
        while (i < value.length - 1) {
            val c = value[i++]
            if (c != '\\') {
                out.append(c)
                continue
            }
            when (val escape = value[i++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    out.append(value.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }

                else -> error("unknown escape \\$escape in $value")
            }
        }
        return out.toString()
    }
}
