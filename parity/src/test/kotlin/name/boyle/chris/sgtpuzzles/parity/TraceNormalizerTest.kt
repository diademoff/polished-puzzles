package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict. The normalizer rules from
 * `docs/adr/0003-strict-draw-traces.md`.
 *
 * These tests do not need the oracle: they pin the canonical form and
 * the validation, so a broken producer fails with a clear message
 * instead of producing a silent golden change.
 */
class TraceNormalizerTest {

    @Test
    fun `every operation has a canonical line`() {
        val trace = trace(
            """
            {"op":"start_draw"},
            {"op":"rect","x":0,"y":0,"w":24,"h":24,"c":0},
            {"op":"text","x":12,"y":12,"font":1,"size":12,"align":257,"c":1,"text":"1"},
            {"op":"line","x1":0,"y1":0,"x2":24,"y2":24,"c":1},
            {"op":"poly","pts":[0,0],"fill":1,"outline":1},
            {"op":"thick_poly","t":1500,"pts":[24,24],"fill":2,"outline":3},
            {"op":"circle","cx":12,"cy":12,"r":5,"fill":-1,"outline":1},
            {"op":"thick_circle","t":1000,"cx":12,"cy":12,"r":5,"fill":-1,"outline":1},
            {"op":"update","x":0,"y":0,"w":24,"h":24},
            {"op":"clip","x":0,"y":0,"w":24,"h":12},
            {"op":"unclip"},
            {"op":"status","text":"Moves: 0"},
            {"op":"blitter_new","id":1,"w":10,"h":20},
            {"op":"blitter_save","id":1,"x":2,"y":3},
            {"op":"blitter_load","id":1,"x":2,"y":3},
            {"op":"blitter_free","id":1},
            {"op":"line_width","t":1500},
            {"op":"line_dotted","dotted":true},
            {"op":"changed_state","canundo":true,"canredo":false},
            {"op":"purging_states"},
            {"op":"thick_line","t":1500,"x1":0,"y1":0,"x2":24,"y2":24,"c":1},
            {"op":"inertia_follow","solved":false},
            {"op":"end_draw"}
            """.trimIndent(),
        )

        val expected = """
            traceSchema 1
            game "Test"
            id "1x1:0"
            params "1x1"
            size 24 24
            palette 0,0,0 1000,1000,1000
            start_draw
            rect 0 0 24 24 0
            text 12 12 1 12 257 1 "1"
            line 0 0 24 24 1
            poly 1 1 1 0 0
            thick_poly 1500 2 3 1 24 24
            circle 12 12 5 -1 1
            thick_circle 1000 12 12 5 -1 1
            update 0 0 24 24
            clip 0 0 24 12
            unclip
            status "Moves: 0"
            blitter_new 1 10 20
            blitter_save 1 2 3
            blitter_load 1 2 3
            blitter_free 1
            line_width 1500
            line_dotted true
            changed_state true false
            purging_states
            thick_line 1500 0 0 24 24 1
            inertia_follow false
            end_draw
        """.trimIndent() + "\n"

        assertEquals(expected, TraceNormalizer.normalize(trace))
    }

    @Test
    fun `an unknown operation is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(
                trace("""{"op":"start_draw"},{"op":"sparkle"},{"op":"end_draw"}"""),
            )
        }
        assertTrue(error.message!!.contains("sparkle"))
    }

    @Test
    fun `another schema is rejected`() {
        val obj = Json.parse(
            """{"traceSchema":2,"game":"Test","id":"1x1:0","params":"1x1",""" +
                """"w":24,"h":24,"palette":[[0,0,0]],"ops":[{"op":"start_draw"},{"op":"end_draw"}]}""",
        ) as JsonValue.Obj
        val error = assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(obj)
        }
        assertTrue(error.message!!.contains("traceSchema"))
    }

    @Test
    fun `unbalanced clip and draw are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(
                trace("""{"op":"start_draw"},{"op":"clip","x":0,"y":0,"w":1,"h":1},{"op":"end_draw"}"""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(
                trace("""{"op":"start_draw"},{"op":"unclip"},{"op":"end_draw"}"""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(trace("""{"op":"start_draw"}"""))
        }
    }

    @Test
    fun `blitter references are checked`() {
        assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(
                trace("""{"op":"start_draw"},{"op":"blitter_save","id":3,"x":0,"y":0},{"op":"end_draw"}"""),
            )
        }
    }

    @Test
    fun `a blitter may outlive the redraw`() {
        // The drawstate owns its blitters: the first draw allocates one,
        // and the sequence continues in a later redraw. An open blitter
        // at the end of a trace is valid.
        val text = TraceNormalizer.normalize(
            trace(
                """{"op":"start_draw"},{"op":"blitter_new","id":1,"w":4,"h":4},""" +
                    """{"op":"end_draw"}""",
            ),
        )
        assertTrue(text.contains("blitter_new 1 4 4"))
    }

    @Test
    fun `a malformed palette is rejected`() {
        val obj = Json.parse(
            """{"traceSchema":1,"game":"Test","id":"1x1:0","params":"1x1",""" +
                """"w":24,"h":24,"palette":[[0,0]],"ops":[{"op":"start_draw"},{"op":"end_draw"}]}""",
        ) as JsonValue.Obj
        assertThrows(IllegalArgumentException::class.java) {
            TraceNormalizer.normalize(obj)
        }
    }

    private fun trace(ops: String): JsonValue.Obj = Json.parse(
        """{"traceSchema":1,"game":"Test","id":"1x1:0","params":"1x1",""" +
            """"w":24,"h":24,"palette":[[0,0,0],[1000,1000,1000]],"ops":[$ops]}""",
    ) as JsonValue.Obj
}
