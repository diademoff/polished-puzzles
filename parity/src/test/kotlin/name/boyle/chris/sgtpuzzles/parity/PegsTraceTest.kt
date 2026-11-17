package name.boyle.chris.sgtpuzzles.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Pegs
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import java.io.File

/**
 * Level: strict. Game: pegs.
 *
 * The first redraw of a drawstate is compared with the oracle and with
 * the frozen traces: the two window updates, the four relief sweeps, the
 * per-cell tiles, the circle radii, and the palette.
 *
 * The warm redraw has no oracle source. Every oracle trace path creates
 * a fresh drawstate (`traceNew`, `trace`, and `midend_force_redraw` after
 * a key sequence), so C cannot record a second redraw on the same
 * drawstate. The warm path is therefore pinned Kotlin-only with an exact
 * operation list, as `work/specs/pegs.md` section 8 recommends. It is
 * the only test that exercises the per-cell cache, the `started` flag,
 * and the blitter erase.
 */
class PegsTraceTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the frozen traces match the oracle for every preset`() =
        OracleClient().use { oracle ->
            val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
            val paramsList = oracle.presets("pegs").map { it.params }.distinct().sorted()

            for (params in paramsList) {
                val midend = Midend.newGame(Pegs, params, SEED)
                val actual = TraceNormalizer.normalize(KotlinTraceSink.trace(midend), upstream)
                val expected = TraceNormalizer.normalize(
                    oracle.traceNew("pegs", params, SEED),
                    upstream,
                )
                assertLines("trace for '$params'", expected, actual)

                val golden = File(testdata, "trace/pegs/${GenTraces.fileName(params)}.trace")
                assertEquals("frozen trace for '$params'", golden.readText(), actual)

                // The first redraw allocates exactly one blitter.
                assertEquals(
                    "blitter_new count for '$params'",
                    1,
                    actual.lines().count { it.startsWith("blitter_new ") },
                )
                assertEquals(
                    "blitter_free count for '$params'",
                    0,
                    actual.lines().count { it.startsWith("blitter_free ") },
                )
            }
        }

    @Test
    fun `a moved state re-emits the relief`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = saveText(
            "5x7cross",
            "OPPPOOPPPOPPPPPPPHPPPPPPPOPPPOOPPPO",
            listOf("2,1-2,3"),
        )
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(Midend.fromSave(Pegs, SgtpSave.parse(save))),
            upstream,
        )
        val expected = TraceNormalizer.normalize(oracle.trace(save), upstream)
        assertLines("moved state trace", expected, actual)

        // A fresh drawstate has `started = false`, so the relief is
        // drawn again even though the state is not new.
        assertTrue("the moved trace re-emits the relief", actual.lines().count { it.startsWith("poly ") } >= 52)
        assertEquals(
            "the moved trace has one blitter",
            1,
            actual.lines().count { it.startsWith("blitter_new ") },
        )
    }

    @Test
    fun `sized traces match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("pegs", "5x7cross", SEED).state
        // Exact-fit viewports for the four tile sizes in the test plan.
        val cases = listOf(
            48 to (288 to 384),
            63 to (377 to 503),
            82 to (492 to 656),
            100 to (600 to 800),
        )
        for ((tile, view) in cases) {
            val expected = TraceNormalizer.normalize(
                oracle.pointsSized(
                    save,
                    emptyList(),
                    width = view.first,
                    height = view.second,
                    recordTrace = true,
                ).trace!!,
                upstream,
            )
            val midend = Midend.newGame(Pegs, "5x7cross", SEED)
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend, tile),
                upstream,
            )
            assertLines("trace at tile $tile", expected, actual)
            assertEquals(
                "the header reports the drawn size at tile $tile",
                "size ${5 * tile + 2 * (tile / 2)} ${7 * tile + 2 * (tile / 2)}",
                actual.lines().first { it.startsWith("size ") },
            )
        }
    }

    @Test
    fun `cursor and jumping tiles match the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("pegs", "5x7cross", SEED).state

        // The cursor starts at (0,2). Two rights and one down put it on
        // the centre hole; two downs put it on a peg, and select arms a
        // jump.
        val sequences = listOf(
            listOf(Buttons.CURSOR_RIGHT, Buttons.CURSOR_RIGHT, Buttons.CURSOR_DOWN),
            listOf(Buttons.CURSOR_DOWN, Buttons.CURSOR_DOWN, Buttons.CURSOR_SELECT),
        )
        for (sequence in sequences) {
            val expected = TraceNormalizer.normalize(
                oracle.trace(save, sequence = sequence),
                upstream,
            )
            val midend = Midend.fromSave(Pegs, SgtpSave.parse(save))
            for (key in sequence) {
                midend.processKey(0, 0, key, Pegs.preferredTileSize)
            }
            val actual = TraceNormalizer.normalize(
                KotlinTraceSink.stateTrace(midend),
                upstream,
            )
            assertLines("cursor trace $sequence", expected, actual)

            if (sequence.size == 3 && sequence[2] == Buttons.CURSOR_SELECT) {
                // A jumping peg uses the cursor colour outside and the
                // peg colour inside. The cursor is on (0,4).
                assertTrue("the jumping peg is drawn", actual.lines().containsAll(
                        listOf(
                            "circle 32 164 11 4 4",
                            "circle 32 164 8 3 3",
                        ),
                    ))
            } else {
                // A cursor on the centre hole uses the highlight colour.
                assertTrue("the hole cursor is drawn", actual.lines().contains("circle 98 131 8 1 1"))
            }
        }
    }

    @Test
    fun `a drag in progress matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("pegs", "5x7cross", SEED).state
        val view = 197 to 263
        val points = listOf(
            OracleClient.Point(98, 65, Buttons.LEFT_BUTTON),
            OracleClient.Point(150, 120, Buttons.LEFT_DRAG),
        )
        val expected = TraceNormalizer.normalize(
            oracle.pointsSized(
                save,
                points,
                width = view.first,
                height = view.second,
                recordTrace = true,
            ).trace!!,
            upstream,
        )

        val midend = Midend.fromSave(Pegs, SgtpSave.parse(save))
        midend.processKey(98, 65, Buttons.LEFT_BUTTON, TILE)
        midend.processKey(150, 120, Buttons.LEFT_DRAG, TILE)
        val actual = TraceNormalizer.normalize(
            KotlinTraceSink.stateTrace(midend, TILE),
            upstream,
        )

        assertLines("drag frame trace", expected, actual)
        // The frame is a fresh drawstate, so it re-emits the relief and
        // saves the sprite at the new pointer.
        assertTrue(
            "the sprite is saved",
            actual.lines().contains("blitter_save 1 134 104"),
        )
    }

    @Test
    fun `a warm drag frame matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("pegs", "5x7cross", SEED).state
        val points = listOf(
            OracleClient.Point(98, 65, Buttons.LEFT_BUTTON),
            OracleClient.Point(150, 120, Buttons.LEFT_DRAG),
        )
        // The press starts a drag at (2,1); the drag moves the sprite.
        val expected = normalizedWarm(
            oracle.traceWarm(save, points, width = 197, height = 263).trace!!,
            upstream,
        )

        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val actualTrace = KotlinTraceSink.warmTrace(midend, points)
        val actualOps = (actualTrace.field("ops") as JsonValue.Arr).items.size
        val actual = normalizedWarm(actualTrace, upstream)
        assertLines("warm drag trace", expected, actual)

        assertFalse(
            "a warm trace allocates no blitter",
            actual.lines().any { it.startsWith("blitter_new ") },
        )
        assertTrue(
            "the previous drag sprite is restored",
            actual.lines().contains("blitter_load 1 134 104"),
        )
        assertTrue(
            "the drag sprite is saved at the pointer",
            actual.lines().contains("blitter_save 1 134 104"),
        )
        val firstOps = (
            KotlinTraceSink.trace(Midend.newGame(Pegs, "5x7cross", SEED))
                .field("ops") as JsonValue.Arr
            ).items.size
        assertTrue(
            "the warm trace is shorter than the first ($actualOps < $firstOps)",
            actualOps < firstOps,
        )
    }

    @Test
    fun `a second warm drag frame matches the oracle`() = OracleClient().use { oracle ->
        val upstream = UpstreamLock.commit(File(testdata.parentFile, "UPSTREAM.lock"))
        val save = oracle.newGame("pegs", "5x7cross", SEED).state
        val firstPoints = listOf(
            OracleClient.Point(98, 65, Buttons.LEFT_BUTTON),
            OracleClient.Point(150, 120, Buttons.LEFT_DRAG),
        )
        val secondPoints = firstPoints + OracleClient.Point(160, 130, Buttons.LEFT_DRAG)

        val first = normalizedWarm(
            oracle.traceWarm(save, firstPoints, width = 197, height = 263).trace!!,
            upstream,
        )
        val second = normalizedWarm(
            oracle.traceWarm(save, secondPoints, width = 197, height = 263).trace!!,
            upstream,
        )
        val kotlinFirst = normalizedWarm(
            KotlinTraceSink.warmTrace(Midend.newGame(Pegs, "5x7cross", SEED), firstPoints),
            upstream,
        )
        val kotlinSecond = normalizedWarm(
            KotlinTraceSink.warmTrace(Midend.newGame(Pegs, "5x7cross", SEED), secondPoints),
            upstream,
        )
        assertLines("first warm drag frame", first, kotlinFirst)
        assertLines("second warm drag frame", second, kotlinSecond)

        assertTrue(
            "the first frame restores the sprite at the first drag",
            kotlinFirst.lines().contains("blitter_load 1 134 104"),
        )
        assertTrue(
            "the second frame restores the sprite after the second drag",
            kotlinSecond.lines().contains("blitter_load 1 144 114"),
        )
        assertFalse(
            "both frames are warm",
            kotlinSecond.lines().any { it.startsWith("blitter_new ") },
        )
    }

    @Test
    fun `a warm redraw after a move draws only the changed cells`() {
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val sink = OpSink()
        midend.recordNewGameTrace(sink)
        val drawState = midend.drawState
        val firstCount = sink.ops.size
        assertEquals(357, firstCount)

        // A real drag moves (2,1) over (2,2) into (2,3).
        assertEquals(
            KeyOutcome.SomeEffect,
            drag(midend, centre(2), centre(1), centre(2), centre(3)),
        )
        assertSame("a move keeps the drawstate", drawState, midend.drawState)

        sink.ops.clear()
        midend.recordRedraw(sink)

        assertEquals(
            "the warm trace after one move",
            listOf(
                "start_draw",
                // Source (2,1) is now a hole.
                "rect 82 49 33 33 0",
                "circle 98 65 8 2 2",
                "update 82 49 33 33",
                // Middle (2,2) is now a hole.
                "rect 82 82 33 33 0",
                "circle 98 98 8 2 2",
                "update 82 82 33 33",
                // Destination (2,3) is now a peg.
                "rect 82 115 33 33 0",
                "circle 98 131 11 3 3",
                "circle 98 131 8 3 3",
                "update 82 115 33 33",
                "end_draw",
            ),
            sink.ops,
        )
        assertTrue("the warm trace is shorter than the first", sink.ops.size < firstCount)
        assertTrue(
            "the warm trace allocates nothing",
            sink.ops.none { it.startsWith("blitter_new") || it.startsWith("blitter_free") },
        )
    }

    @Test
    fun `a drag frame saves the sprite and the next frame erases it`() {
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val sink = OpSink()
        midend.recordNewGameTrace(sink)
        val firstCount = sink.ops.size

        // Press on (2,1). The source is blanked and the sprite is saved
        // at the pressed pixel minus half a tile.
        sink.ops.clear()
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(centre(2), centre(1), Buttons.LEFT_BUTTON, TILE),
        )
        midend.recordRedraw(sink)
        assertEquals("start_draw", sink.ops.first())
        assertEquals("end_draw", sink.ops.last())
        assertTrue(
            "the source is blanked to a hole",
            sink.ops.containsAll(listOf("circle 98 65 8 2 2")),
        )
        assertEquals(
            "the sprite is saved at the pointer",
            1,
            sink.ops.count { it == "blitter_save 1 82 49" },
        )
        assertTrue(
            "the sprite draws a peg over the saved area",
            sink.ops.containsAll(listOf("circle 98 65 11 3 3", "circle 98 65 8 3 3")),
        )

        // Second frame: the old sprite is erased first, no cell is
        // dirty, and the sprite is saved at the new pointer.
        sink.ops.clear()
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(150, 120, Buttons.LEFT_DRAG, TILE),
        )
        midend.recordRedraw(sink)
        assertEquals(
            "the second drag frame",
            listOf(
                "start_draw",
                "blitter_load 1 82 49",
                "update 82 49 33 33",
                "blitter_save 1 134 104",
                "circle 150 120 11 3 3",
                "circle 150 120 8 3 3",
                "update 134 104 33 33",
                "end_draw",
            ),
            sink.ops,
        )
        assertTrue("a drag frame is shorter than the first", sink.ops.size < firstCount)
        assertTrue(
            "the second frame erases the old sprite",
            sink.ops.contains("blitter_load 1 82 49"),
        )

        // A release with a bad length cancels the drag: the next frame
        // erases the sprite and restores the source peg.
        sink.ops.clear()
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(150, 120, Buttons.LEFT_RELEASE, TILE),
        )
        midend.recordRedraw(sink)
        assertEquals("start_draw", sink.ops.first())
        assertEquals("blitter_load 1 134 104", sink.ops[1])
        assertTrue(
            "the released source is a peg again",
            sink.ops.containsAll(listOf("circle 98 65 11 3 3", "circle 98 65 8 3 3")),
        )
    }

    @Test
    fun `the drawstate allocates one blitter and a move keeps it`() {
        val midend = Midend.newGame(Pegs, "5x7cross", SEED)
        val sink = KotlinTraceSink()
        val first = midend.ensureDrawState(TILE, sink) as Pegs.DrawState
        assertEquals(
            listOf("blitter_new"),
            sink.ops.map { (it.fields["op"] as JsonValue.Str).value },
        )
        assertEquals(33L, (sink.ops[0].fields["w"] as JsonValue.Num).value)
        assertEquals(33L, (sink.ops[0].fields["h"] as JsonValue.Num).value)

        // The cache starts empty and the relief is not drawn yet.
        assertEquals(TILE, first.tileSize)
        assertTrue("the cache starts at 255", first.grid.all { it == 255 })
        assertFalse("the relief is not drawn yet", first.started)
        assertEquals(-1, first.bgColour)
        assertFalse(first.dragging)

        midend.recordRedraw(sink)
        sink.ops.clear()
        drag(midend, centre(2), centre(1), centre(2), centre(3))
        midend.recordRedraw(sink)

        assertSame("the drawstate survives the move", first, midend.drawState)
        val names = sink.ops.map { (it.fields["op"] as JsonValue.Str).value }
        assertEquals("a move allocates no blitter", 0, names.count { it == "blitter_new" })
        assertEquals("a move frees no blitter", 0, names.count { it == "blitter_free" })

        sink.ops.clear()
        assertSame("a second ensure keeps the cache", first, midend.ensureDrawState(TILE, sink))
        assertEquals(
            "a second ensure allocates nothing",
            emptyList<String>(),
            sink.ops.map { (it.fields["op"] as JsonValue.Str).value },
        )

        // The free goes through the sink that created the blitter.
        midend.freeDrawState()
        assertEquals(
            "the free releases the one blitter",
            listOf("blitter_free"),
            sink.ops.map { (it.fields["op"] as JsonValue.Str).value },
        )
    }

    private fun drag(midend: Midend, x0: Int, y0: Int, x1: Int, y1: Int): KeyOutcome {
        assertEquals(
            "the press must start a drag",
            KeyOutcome.SomeEffect,
            midend.processKey(x0, y0, Buttons.LEFT_BUTTON, TILE),
        )
        for (i in 1..4) {
            midend.processKey(
                x0 + (x1 - x0) * i / 4,
                y0 + (y1 - y0) * i / 4,
                Buttons.LEFT_DRAG,
                TILE,
            )
        }
        return midend.processKey(x1, y1, Buttons.LEFT_RELEASE, TILE)
    }

    private fun centre(index: Int): Int = TILE / 2 + index * TILE + TILE / 2

    private fun saveText(params: String, desc: String, moves: List<String>): String {
        val records = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", "Pegs"),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (1 + moves.size).toString()),
            SaveRecord("STATEPOS", (1 + moves.size).toString()),
        )
        for (move in moves) records += SaveRecord("MOVE", move)
        return SgtpSave.of(records).serialise()
    }

    /**
     * Normalizes a warm trace, which has no `blitter_new` by design.
     * Inject a dummy allocation for every blitter the trace uses, run
     * the normalizer (so a dangling id or a bad primitive still fails),
     * then drop the injected lines.
     */
    private fun normalizedWarm(trace: JsonValue.Obj, upstream: String): String {
        val ops = (trace.field("ops") as JsonValue.Arr).items.toMutableList()
        val ids = ops.asSequence()
            .filterIsInstance<JsonValue.Obj>()
            .mapNotNull { (it.fields["id"] as? JsonValue.Num)?.value }
            .distinct()
            .toList()
        ops.addAll(
            0,
            ids.map { id ->
                JsonValue.Obj(
                    linkedMapOf(
                        "op" to JsonValue.Str("blitter_new"),
                        "id" to JsonValue.Num(id),
                        "w" to JsonValue.Num(1),
                        "h" to JsonValue.Num(1),
                    ),
                )
            },
        )
        val patched = JsonValue.Obj(
            LinkedHashMap(trace.fields).apply { put("ops", JsonValue.Arr(ops)) },
        )
        return TraceNormalizer.normalize(patched, upstream)
            .lines()
            .filterNot { it.startsWith("blitter_new ") }
            .joinToString("\n", postfix = "\n")
    }

    private fun assertLines(what: String, expected: String, actual: String) {        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until minOf(e.size, a.size)) {
            if (e[i] != a[i]) {
                throw AssertionError(
                    "$what: first diff at line ${i + 1}:\n" +
                        "expected: ${e[i]}\nactual:   ${a[i]}",
                )
            }
        }
        assertEquals("$what: line count", e.size, a.size)
    }

    /**
     * Records the drawing operations as compact text. Only the
     * primitives that pegs uses are exercised.
     */
    private class OpSink : DrawSink {
        override fun inertiaFollow(isSolved: Boolean) {}

        val ops = ArrayList<String>()
        private var nextBlitterId = 1

        override fun changedState(canUndo: Boolean, canRedo: Boolean) {
            ops += "changed_state"
        }

        override fun startDraw() {
            ops += "start_draw"
        }

        override fun endDraw() {
            ops += "end_draw"
        }

        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {
            ops += "rect $x $y $width $height $colour"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line $x1 $y1 $x2 $y2 $colour"
        }

        override fun thickLine(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, colour: Int) {}

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "circle $cx $cy $radius $fillColour $outlineColour"
        }
        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}
        override fun thickPoly(
            points: IntArray,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly ${points.joinToString(" ")} $fillColour $outlineColour"
        }

        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {
            ops += "text $x $y $font $size $align $colour $text"
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip $x $y $width $height"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update $x $y $width $height"
        }

        override fun status(text: String) {
            ops += "status $text"
        }

        override fun blitterNew(width: Int, height: Int): Int {
            val id = nextBlitterId++
            ops += "blitter_new $id $width $height"
            return id
        }

        override fun blitterFree(id: Int) {
            ops += "blitter_free $id"
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save $id $x $y"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load $id $x $y"
        }
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 33
    }
}
