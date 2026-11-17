package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: map.
 *
 * The engine-only paths: the parameter quirks and guards, the two random
 * streams, the auxiliary answer, the move grammar, the completion flag,
 * the solver error strings, the key labels, the preferences, the shared
 * geometry, and the drawstate lifecycle. The oracle comparisons live in
 * the `:parity` module.
 */
class MapTest {

    private fun params(text: String): Map.Params = Map.decodeParams(text) as Map.Params

    private fun state(desc: String, text: String = "5x2n5"): Map.State =
        Map.newState(params(text), desc) as Map.State

    // ------------------------------------------------------------------
    // Parameters.
    // ------------------------------------------------------------------

    @Test
    fun `the default and the six presets match upstream`() {
        val d = Map.defaultParams() as Map.Params
        assertEquals(20, d.w)
        assertEquals(15, d.h)
        assertEquals(30, d.n)
        assertEquals(1, d.difficulty)
        assertEquals("20x15n30", Map.encodeParams(d, false))
        assertEquals("20x15n30dn", Map.encodeParams(d, true))

        val expected = listOf(
            Triple("20x15, 30 regions, Easy", "20x15n30de", 0),
            Triple("20x15, 30 regions, Normal", "20x15n30dn", 1),
            Triple("20x15, 30 regions, Hard", "20x15n30dh", 2),
            Triple("20x15, 30 regions, Unreasonable", "20x15n30du", 3),
            Triple("30x25, 75 regions, Normal", "30x25n75dn", 1),
            Triple("30x25, 75 regions, Hard", "30x25n75dh", 2),
        )
        val presets = Map.presets()
        assertEquals(6, presets.size)
        for (i in expected.indices) {
            assertEquals(expected[i].first, presets[i].title)
            assertEquals(expected[i].second, presets[i].params)
            assertEquals(expected[i].third, params(presets[i].params).difficulty)
        }
    }

    @Test
    fun `decode follows the C quirks`() {
        // `20x15` has no region count and uses w*h/8.
        assertEquals(37, params("20x15").n)
        // A dot is consumed with the digits but ends the number.
        val dotted = params("20x15n3.5")
        assertEquals(3, dotted.n)
        assertEquals(15, dotted.h)
        // The digit skip does not skip a sign, so x is never read.
        val negative = params("-4x5")
        assertEquals(-4, negative.w)
        assertEquals(-4, negative.h)
        assertEquals(1, negative.difficulty)
        // A bare d and an unknown character both leave the difficulty.
        assertEquals(1, params("20x15n30d").difficulty)
        assertEquals(1, params("20x15n30dz").difficulty)
        assertEquals(3, params("20x15n30du").difficulty)
        // The first matching difficulty character wins.
        assertEquals(0, params("20x15n30de").difficulty)
        // An overflow saturates, the port's defined result. The oracle
        // is platform undefined here, so no parity test uses this value.
        assertEquals(Int.MAX_VALUE, params("99999999999999999999").w)
        assertEquals(Int.MIN_VALUE, params("-99999999999999999999").w)
    }

    @Test
    fun `encode writes a plain d for an out-of-range difficulty`() {
        // C reads map_diffchars[diff] out of bounds. The port writes the
        // bare d, as unruly does for its own out-of-range case.
        assertEquals(
            "20x15n30d",
            Map.encodeParams(Map.Params(20, 15, 30, 4), full = true),
        )
        assertEquals(
            "20x15n30d",
            Map.encodeParams(Map.Params(20, 15, 30, -1), full = true),
        )
    }

    @Test
    fun `validation follows the C order and messages`() {
        assertEquals(
            "Width and height must be at least two",
            Map.validateParams(Map.Params(1, 15, 30, 1), true),
        )
        assertEquals(
            "Width and height must be at least two",
            Map.validateParams(Map.Params(-4, -4, 30, 1), true),
        )
        assertEquals(
            "Width times height must not be unreasonably large",
            Map.validateParams(Map.Params(32768, 32768, 5, 1), true),
        )
        assertEquals(
            "Must have at least five regions",
            Map.validateParams(Map.Params(20, 15, 4, 1), true),
        )
        assertEquals(
            "Too many regions to fit in grid",
            Map.validateParams(Map.Params(20, 15, 999, 1), true),
        )
        assertNull(Map.validateParams(Map.Params(20, 15, 30, 1), true))
        // The exact C area boundary: 32767^2 fits in INT_MAX/2, 32768^2
        // does not. No test allocates either board.
        assertNull(Map.validateParams(Map.Params(32767, 32767, 5, 1), true))
        assertNotNull(Map.validateParams(Map.Params(32768, 32768, 5, 1), true))
        // `full` is ignored.
        assertNull(Map.validateParams(Map.Params(20, 15, 30, 1), false))
    }

    @Test
    fun `the generation guard rejects a board it cannot build`() {
        // The exact boundaries, without allocating a board of that size.
        Map.requirePortableBoard(1024, 1024, 5)
        assertEquals(
            Map.AREA_TOO_LARGE,
            runCatching { Map.requirePortableBoard(1025, 1024, 5) }
                .exceptionOrNull()?.message,
        )
        Map.requirePortableBoard(4096, 2, 4096)
        assertEquals(
            Map.REGIONS_TOO_LARGE,
            runCatching { Map.requirePortableBoard(4096, 2, 4097) }
                .exceptionOrNull()?.message,
        )
        // Validation keeps the C behavior for the huge area.
        assertNull(Map.validateParams(Map.Params(1025, 1024, 5, 1), true))
    }

    @Test(timeout = 120_000)
    fun `a refused generation draws no random number`() {
        val random = RandomState.fromSeed("guard")
        val before = random.encodeState()
        val error = runCatching {
            Map.newDesc(Map.Params(1025, 1024, 5, 1), random)
        }.exceptionOrNull()
        assertEquals(Map.AREA_TOO_LARGE, error?.message)
        assertEquals(before, random.encodeState())

        val regionError = runCatching {
            Map.newDesc(Map.Params(4096, 2, 4097, 1), random)
        }.exceptionOrNull()
        assertEquals(Map.REGIONS_TOO_LARGE, regionError?.message)
        assertEquals(before, random.encodeState())
    }

    @Test
    fun `newState refuses a huge board before it allocates`() {
        assertEquals(
            Map.AREA_TOO_LARGE,
            runCatching { Map.newState(Map.Params(1025, 1024, 5, 1), "x") }
                .exceptionOrNull()?.message,
        )
        assertEquals(
            Map.REGIONS_TOO_LARGE,
            runCatching { Map.newState(Map.Params(4096, 2, 4097, 1), "x") }
                .exceptionOrNull()?.message,
        )
        // validateDesc uses the same fixed message, so a game id cannot
        // reach the allocation.
        assertEquals(
            Map.AREA_TOO_LARGE,
            Map.validateDesc(Map.Params(1025, 1024, 5, 1), "x"),
        )
        assertEquals(
            Map.REGIONS_TOO_LARGE,
            Map.validateDesc(Map.Params(4096, 2, 4097, 1), "x"),
        )
    }

    // ------------------------------------------------------------------
    // Description.
    // ------------------------------------------------------------------

    @Test
    fun `validateDesc reports the C messages in order`() {
        val p = params("20x15n30")
        assertEquals(
            "Unexpected character in edge list",
            Map.validateDesc(p, "A,212a30a2g22d1a02c21"),
        )
        assertEquals(
            "Too much data in edge list",
            Map.validateDesc(p, "z".repeat(30) + ",212a30a2g22d1a02c21"),
        )
        assertEquals(
            "Too little data in edge list",
            Map.validateDesc(p, "a,212a30a2g22d1a02c21"),
        )
        assertEquals(
            "Expected comma before clue list",
            Map.validateDesc(p, "ddbehafbbafbabccabkaaacabaabccbadahacbcbbbjaaaaaabaaeeaaabdbjcabcaiajcfbdabacblabahcecabzbaiclbaboagbcacdedabaaeacebdcacagaaacaahfbgcaaadefbcabbacaeabbaabaaefbaaabaaffbcodgdjad"),
        )
        assertEquals(
            "Unexpected character in clue list",
            Map.validateDesc(p, "ddbehafbbafbabccabkaaacabaabccbadahacbcbbbjaaaaaabaaeeaaabdbjcabcaiajcfbdabacblabahcecabzbaiclbaboagbcacdedabaaeacebdcacagaaacaahfbgcaaadefbcabbacaeabbaabaaefbaaabaaffbcodgdjad,A"),
        )
        assertEquals(
            "Too little data in clue list",
            Map.validateDesc(p, "ddbehafbbafbabccabkaaacabaabccbadahacbcbbbjaaaaaabaaeeaaabdbjcabcaiajcfbdabacblabahcecabzbaiclbaboagbcacdedabaaeacebdcacagaaacaahfbgcaaadefbcabbacaeabbaabaaefbaaabaaffbcodgdjad,aaaa"),
        )
        assertEquals(
            "Too much data in clue list",
            Map.validateDesc(p, "ddbehafbbafbabccabkaaacabaabccbadahacbcbbbjaaaaaabaaeeaaabdbjcabcaiajcfbdabacblabahcecabzbaiclbaboagbcacdedabaaeacebdcacagaaacaahfbgcaaadefbcabbacaeabbaabaaefbaaabaaffbcodgdjad,212a30a2g22d1a02c210"),
        )
        // A wrong region count is reported before the clue list.
        val defaultDesc = Midend.newGame(Map, "20x15n30dn", "12345").desc
        assertEquals(
            null,
            Map.validateDesc(p, defaultDesc),
        )
        assertEquals(
            "Edge list defines the wrong number of regions",
            Map.validateDesc(params("20x15n31"), defaultDesc),
        )
    }

    @Test(timeout = 120_000)
    fun `the same seed gives the same description and the same stream`() {
        val p = params("20x15n30dn")
        val a = RandomState.fromSeed("stream")
        val b = RandomState.fromSeed("stream")
        val da = Map.newDesc(p, a)
        val db = Map.newDesc(p, b)
        assertEquals(da.text, db.text)
        assertEquals(da.auxInfo, db.auxInfo)
        assertEquals(a.encodeState(), b.encodeState())
        // A second call continues the same stream, so the generator
        // consumes a fixed number of draws per attempt.
        val second = Map.newDescWithStats(p, a)
        val secondAgain = Map.newDescWithStats(p, b)
        assertEquals(second.description.text, secondAgain.description.text)
        assertEquals(a.encodeState(), b.encodeState())
    }

    @Test(timeout = 120_000)
    fun `the aux is the full four-colouring in region order`() {
        for (preset in Map.presets()) {
            val p = params(preset.params)
            val generated = Map.newDescWithStats(p, RandomState.fromSeed("12345"))
            val aux = assertNotNull(generated.description.auxInfo)
            assertTrue(aux.startsWith("S;"))
            val parts = aux.substring(2).split(';')
            assertEquals(p.n, parts.size)
            for (i in parts.indices) {
                val (colour, region) = parts[i].split(':').map { it.toInt() }
                assertEquals(i, region)
                assertTrue(colour in 0..3)
            }
            assertEquals(aux, AuxInfo.decode(AuxInfo.encode(aux)))
            // The auxiliary answer is not the reduced clue set.
            val state = Map.newState(p, generated.description.text)
            assertEquals(
                "S",
                Map.solve(state, aux)!!.substring(0, 1),
            )
        }
    }

    @Test(timeout = 120_000)
    fun `newState is deterministic and the description text seeds the geometry`() {
        val p = params("20x15n30")
        val desc = Midend.newGame(Map, "20x15n30dn", "12345").desc
        val a = Map.newState(p, desc) as Map.State
        val b = Map.newState(p, desc) as Map.State
        assertContentEquals(a.shared.map, b.shared.map)
        assertContentEquals(a.shared.graph, b.shared.graph)
        assertContentEquals(a.shared.edgex, b.shared.edgex)
        assertContentEquals(a.shared.edgey, b.shared.edgey)
        assertContentEquals(a.shared.regionx, b.shared.regionx)
        assertContentEquals(a.shared.regiony, b.shared.regiony)

        // The same edge list with a different clue list decodes to the
        // same board but paints a different set of diagonals.
        val edge = desc.substringBefore(',')
        val other = Map.newState(p, "$edge,0zc") as Map.State
        assertFalse(
            a.shared.map.contentEquals(other.shared.map),
            "a different description text must be able to split differently",
        )
        // The pre-smoothing graph is the same board, so the region count
        // and adjacency are unchanged.
        assertContentEquals(a.shared.graph, other.shared.graph)
    }

    @Test(timeout = 120_000)
    fun `the retry fixtures pin the gate and the difficulty drop`() {
        val hard = Map.newDescWithStats(params("20x15n30dh"), RandomState.fromSeed("0"))
        assertEquals(4, hard.stats.attempts)
        assertEquals(3, hard.stats.gateRejections)
        assertEquals(0, hard.stats.difficultyDrops)
        assertEquals(2, hard.stats.finalMindiff)

        val unreachable = Map.newDescWithStats(params("20x15n30du"), RandomState.fromSeed("0"))
        assertEquals(4, unreachable.stats.attempts)
        assertEquals(3, unreachable.stats.gateRejections)
        assertEquals(0, unreachable.stats.difficultyDrops)
        assertEquals(3, unreachable.stats.finalMindiff)

        // n < 9 lets the generator drop the minimum difficulty after 51
        // rejected attempts. The 52nd attempt is accepted at Easy.
        val dropped = Map.newDescWithStats(params("5x2n5dh"), RandomState.fromSeed("0"))
        assertEquals(52, dropped.stats.attempts)
        assertEquals(51, dropped.stats.gateRejections)
        assertEquals(1, dropped.stats.difficultyDrops)
        assertEquals(0, dropped.stats.finalMindiff)
        // The same three seeds all take the drop path.
        for (seed in listOf("1", "2", "3")) {
            val s = Map.newDescWithStats(params("5x2n5dh"), RandomState.fromSeed(seed))
            assertEquals(1, s.stats.difficultyDrops)
            assertEquals(0, s.stats.finalMindiff)
        }
        // Easy accepts the first attempt because the sanity gate draws no
        // deduction and the clue set is never complete.
        val easy = Map.newDescWithStats(params("20x15n30de"), RandomState.fromSeed("0"))
        assertEquals(1, easy.stats.attempts)
    }

    // ------------------------------------------------------------------
    // Moves.
    // ------------------------------------------------------------------

    @Test
    fun `the move grammar accepts the C forms`() {
        val s = state("fh,0123a")

        assertEquals(s, Map.executeMove(s, ""))
        val marked = Map.executeMove(s, "S") as Map.State
        assertTrue(marked.cheated)
        assertContentEquals(s.colouring, marked.colouring)
        assertTrue((Map.executeMove(s, "S;") as Map.State).cheated)
        assertEquals(0, (Map.executeMove(s, "0:0") as Map.State).colouring[0])
        assertEquals(2, (Map.executeMove(s, "2:3") as Map.State).colouring[3])
        assertEquals(-1, (Map.executeMove(s, "C:4") as Map.State).colouring[4])
        assertEquals(1, (Map.executeMove(s, "p0:4") as Map.State).pencil[4])
        assertEquals(0, (Map.executeMove(s, "p0:4;p0:4") as Map.State).pencil[4])
        assertEquals(0, (Map.executeMove(s, "p0:4;pC:4") as Map.State).pencil[4])
        // A trailing semicolon is accepted.
        assertEquals(2, (Map.executeMove(s, "2:4;") as Map.State).colouring[4])
        // pS is accepted as S because C consumes the p first.
        assertTrue((Map.executeMove(s, "pS") as Map.State).cheated)

        // The 30-region board accepts whitespace and a sign in the index.
        val big = Map.newState(params("20x15n30"), Midend.newGame(Map, "20x15n30dn", "1").desc)
        assertEquals(1, (Map.executeMove(big, "1: 5") as Map.State).colouring[5])
        assertEquals(1, (Map.executeMove(big, "1:+5") as Map.State).colouring[5])
        assertEquals(1, (Map.executeMove(big, "1:5;") as Map.State).colouring[5])
    }

    @Test
    fun `the move grammar rejects the C NULL forms`() {
        val s = state("fh,0123a")
        for (move in listOf(
            ";1:5", "1:5;;", "1:5p2:5", "1:5,2:5", "junk", "p", "pC",
            "4:0", "1:5", "1:99", "1:", "1:x", "1:-1", "S0:1",
            "p1:1", // a pencil clause on a coloured clue
        )) {
            assertNull(Map.executeMove(s, move), move)
        }
        // 5x2n5 has five regions, so region 5 is out of range.
        assertNull(Map.executeMove(s, "1:5"))
        // Overflow saturates and then fails the range test.
        assertNull(Map.executeMove(s, "1:4294967296"))
        assertNull(Map.executeMove(s, "1:-4294967296"))
    }

    @Test
    fun `completion is checked once and stays`() {
        val open = state("fh,0123a")
        assertFalse(open.completed)
        assertEquals(0, Map.status(open))

        val completed = Map.executeMove(open, "0:4") as Map.State
        assertTrue(completed.completed)
        assertEquals(1, Map.status(completed))
        assertFalse(completed.cheated)

        // A later move can break the board without clearing the flag.
        val broken = Map.executeMove(completed, "C:0") as Map.State
        assertTrue(broken.completed)
        assertEquals(1, Map.status(broken))

        // A fully-clued valid board is not completed until a move runs.
        val full = state("fh,01010")
        assertFalse(full.completed)
        assertTrue((Map.executeMove(full, "S") as Map.State).completed)
    }

    @Test
    fun `the solver uses the initial clues and the C error strings`() {
        // With an auxiliary answer, solve returns it unchanged.
        val midend = Midend.newGame(Map, "20x15n30dn", "12345")
        val aux = assertNotNull(midend.auxInfo)
        assertEquals(aux, Map.solve(midend.state, aux))
        val solved = Map.executeMove(midend.state, aux) as Map.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)

        // Without it, the move is a diff from the current colouring.
        val diff = assertNotNull(Map.solve(midend.state, null))
        assertTrue(diff.startsWith("S"))
        assertTrue(diff.length > 1)
        val applied = Map.executeMove(midend.state, diff) as Map.State
        assertTrue(applied.completed)
        assertTrue(applied.cheated)

        // A unique, fully-clued board returns the bare S.
        val full = state("fh,01010")
        assertEquals("S", Map.solve(full, null))
        assertEquals(null, Map.solveError(full, null))

        // Two adjacent equal clues are inconsistent.
        val inconsistent = state("fh,00000")
        assertNull(Map.solve(inconsistent, null))
        assertEquals("Puzzle is inconsistent", Map.solveError(inconsistent, null))

        // The 0123a board has two valid colourings.
        val ambiguous = state("fh,0123a")
        assertNull(Map.solve(ambiguous, null))
        assertEquals(
            "Unable to find a unique solution for this puzzle",
            Map.solveError(ambiguous, null),
        )
    }

    @Test
    fun `a clue overwritten by a move is restored by the solve diff`() {
        // C asserts that a changed region is not immutable and aborts. The
        // port includes the region, so the move repairs the clue.
        val start = state("fh,01010")
        val overwritten = Map.executeMove(start, "2:0") as Map.State
        assertEquals(2, overwritten.colouring[0])
        val move = assertNotNull(Map.solve(overwritten, null))
        assertEquals("S;0:0", move)
        val repaired = Map.executeMove(overwritten, move) as Map.State
        assertEquals(0, repaired.colouring[0])
        assertTrue(repaired.completed)
        assertTrue(repaired.cheated)
    }

    @Test
    fun `the completion event ignores a cheated solve`() {
        val midend = Midend.fromGameId(Map, "5x2n5:fh,0123a")
        val tile = 20
        // Press on region 0 (a clue) and release on region 4.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(30, 30, Buttons.LEFT_BUTTON, tile),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(110, 30, Buttons.LEFT_RELEASE, tile),
        )
        assertEquals("0:4", midend.serialise().value("MOVE"))
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())

        // A solve sets the status but never raises the event.
        val solved = Midend.fromGameId(Map, "5x2n5:fh,01010")
        assertTrue(solved.solve())
        assertEquals(1, solved.status())
        assertFalse(solved.takeCompletedEvent())
        assertTrue((solved.state as Map.State).cheated)
    }

    // ------------------------------------------------------------------
    // Keys, UI, preferences.
    // ------------------------------------------------------------------

    @Test
    fun `the two requested keys use the left-right arrow mode`() {
        val request = Map.requestKeys(Map.defaultParams())
        assertEquals(2, request.labels.size)
        assertEquals(76, request.labels[0].button)
        assertEquals("Regions", request.labels[0].name)
        assertFalse(request.labels[0].needsArrows)
        assertEquals(67, request.labels[1].button)
        assertEquals("Colours", request.labels[1].name)
        assertFalse(request.labels[1].needsArrows)
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, request.arrowMode)
        // The Android full-cursor hint does not change a map request.
        assertEquals(request, Map.requestKeys(Map.defaultParams(), fullCursor = true))
    }

    @Test
    fun `the key labels cover every branch`() {
        val s = state("fh,0123a")
        val ui = Map.newUi(s) as Map.Ui

        // Hidden cursor.
        assertEquals("", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.curVisible = true
        // No drag yet.
        assertEquals("Pick", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("Pick", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))

        val blank = findCursorForRegion(s, region = 4, quadrant = Map.RE)
        assertNotNull(blank)
        ui.curX = blank.first
        ui.curY = blank.second
        ui.curLastMove = Buttons.CURSOR_RIGHT

        // A pick on a clue region is immutable.
        val clue = findCursorForRegion(s, region = 1, quadrant = Map.RE)
        assertNotNull(clue)
        ui.curX = clue.first
        ui.curY = clue.second
        ui.dragColour = 0
        ui.dragPencil = 0
        ui.curMoved = true
        assertEquals("Cancel", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))

        // A blank target and a solid source.
        ui.curX = blank.first
        ui.curY = blank.second
        ui.curMoved = true
        assertEquals("Fill", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("Stipple", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))
        ui.curMoved = false
        assertEquals("Clear", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.dragPencil = 1
        assertEquals("Cancel", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.dragPencil = 0

        // A pencil source.
        ui.dragColour = -1
        ui.dragPencil = 1
        ui.curMoved = true
        assertEquals("Stipple", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        ui.dragPencil = 0
        assertEquals("Clear", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))

        // SELECT2 on a coloured target cancels.
        ui.dragColour = 0
        ui.curMoved = true
        val withColour = Map.executeMove(s, "1:4") as Map.State
        assertEquals(
            "Cancel",
            Map.currentKeyLabel(ui, withColour, Buttons.CURSOR_SELECT2),
        )
        // An out-of-grid cursor returns the empty string.
        ui.curX = 99
        assertEquals("", Map.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        // A non-select key returns the empty string.
        ui.curX = blank.first
        assertEquals("", Map.currentKeyLabel(ui, s, Buttons.CURSOR_LEFT))
    }

    @Test
    fun `the UI defaults and the preferences are not saved`() {
        val midend = Midend.newGame(Map, "20x15n30dn", "12345")
        val ui = midend.ui as Map.Ui
        assertEquals(-2, ui.dragColour)
        assertEquals(0, ui.dragPencil)
        assertEquals(-1, ui.dragX)
        assertEquals(-1, ui.dragY)
        assertEquals(0, ui.showLabels)
        assertFalse(ui.largeStipples)
        assertEquals(0, ui.curX)
        assertEquals(0, ui.curY)
        assertFalse(ui.curVisible)
        assertFalse(ui.curMoved)
        assertEquals(0, ui.curLastMove)
        assertEquals(0, ui.flashType)

        val before = midend.serialise()
        ui.showLabels = 1
        ui.largeStipples = true
        ui.flashType = 1
        val after = midend.serialise()
        assertEquals(before, after)
        assertNull(after.value("UI"))

        // A fresh UI keeps the C defaults.
        val fresh = Map.newUi(midend.state) as Map.Ui
        assertEquals(0, fresh.showLabels)
        assertFalse(fresh.largeStipples)
        assertEquals(0, fresh.flashType)
    }

    @Test
    fun `the label keys toggle the drawing preference`() {
        val s = state("fh,0123a")
        val ui = Map.newUi(s) as Map.Ui
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, 'l'.code, 20))
        assertEquals(1, ui.showLabels)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, 'L'.code, 20))
        assertEquals(0, ui.showLabels)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, 'c'.code, 20))
        assertEquals(2, ui.showLabels)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, 'C'.code, 20))
        assertEquals(0, ui.showLabels)
    }

    @Test
    fun `a real drag and a right drag build the C move strings`() {
        var s = state("fh,0123a")
        var ui = Map.newUi(s) as Map.Ui

        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 30, 30, Buttons.LEFT_BUTTON, 20))
        assertEquals(0, ui.dragColour)
        assertEquals(0, ui.dragPencil)
        assertFalse(ui.curVisible)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 70, 30, Buttons.LEFT_DRAG, 20))
        assertEquals(70, ui.dragX)
        val move = Map.interpretMove(s, ui, 110, 30, Buttons.LEFT_RELEASE, 20)
        assertEquals(MoveResult.Move("0:4"), move)
        assertEquals(-2, ui.dragColour)
        s = Map.executeMove(s, (move as MoveResult.Move).text) as Map.State
        assertTrue(s.completed)

        // A right drag from a colour to a blank toggles one pencil bit.
        val right = state("fh,0123a")
        ui = Map.newUi(right) as Map.Ui
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(right, ui, 30, 30, Buttons.RIGHT_BUTTON, 20))
        assertEquals(MoveResult.Move("p0:4"), Map.interpretMove(right, ui, 110, 30, Buttons.RIGHT_RELEASE, 20))
        // A second right drag toggles the same bit back.
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(right, ui, 30, 30, Buttons.RIGHT_BUTTON, 20))
        assertEquals(MoveResult.Move("p0:4"), Map.interpretMove(right, ui, 110, 30, Buttons.RIGHT_RELEASE, 20))

        // A drop on an immutable clue, a border, and the same region are
        // UI updates with no move. A nearby real drag is the control.
        val open = state("fh,0123a")
        ui = Map.newUi(open) as Map.Ui
        Map.interpretMove(open, ui, 30, 30, Buttons.LEFT_BUTTON, 20)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(open, ui, 30, 30, Buttons.LEFT_RELEASE, 20))
        Map.interpretMove(open, ui, 30, 30, Buttons.LEFT_BUTTON, 20)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(open, ui, 10, 10, Buttons.LEFT_RELEASE, 20))
        Map.interpretMove(open, ui, 30, 30, Buttons.LEFT_BUTTON, 20)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(open, ui, 50, 30, Buttons.LEFT_RELEASE, 20))
        Map.interpretMove(open, ui, 30, 30, Buttons.LEFT_BUTTON, 20)
        assertEquals(MoveResult.Move("0:4"), Map.interpretMove(open, ui, 110, 30, Buttons.LEFT_RELEASE, 20))
    }

    @Test
    fun `a cursor pick and drop uses the epsilon quadrant`() {
        val s = state("fh,0123a")
        val ui = Map.newUi(s) as Map.Ui

        // Put the cursor on a cell whose bottom region is clue 0.
        val clue0 = findCursorForRegion(s, region = 0, quadrant = Map.BE)
        assertNotNull(clue0)
        ui.curX = clue0.first
        ui.curY = clue0.second
        ui.curLastMove = 0
        // A select with a hidden cursor only shows it.
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_SELECT, 20))
        assertTrue(ui.curVisible)
        // The second select picks region 0 (a clue of colour 0).
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_SELECT, 20))
        assertEquals(0, ui.dragColour)
        assertFalse(ui.curMoved)

        // Find the cursor position that selects region 4 on the right.
        val target = findCursorForRegion(s, region = 4, quadrant = Map.RE)
        assertNotNull(target)
        ui.curX = target.first
        ui.curY = target.second
        ui.curLastMove = Buttons.CURSOR_RIGHT
        ui.curMoved = true
        assertEquals(
            MoveResult.Move("0:4"),
            Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_SELECT, 20),
        )

        // A double select on a blank target is a no-op.
        val s2 = state("fh,0123a")
        val ui2 = Map.newUi(s2) as Map.Ui
        ui2.curVisible = true
        ui2.curX = target.first
        ui2.curY = target.second
        ui2.curLastMove = Buttons.CURSOR_RIGHT
        ui2.dragColour = 0
        ui2.curMoved = false
        assertEquals(
            MoveResult.UiUpdate,
            Map.interpretMove(s2, ui2, 0, 0, Buttons.CURSOR_SELECT, 20),
        )
        // A double select on a coloured region clears it.
        val coloured = Map.executeMove(s2, "1:4") as Map.State
        val ui3 = Map.newUi(coloured) as Map.Ui
        ui3.curVisible = true
        ui3.curX = target.first
        ui3.curY = target.second
        ui3.curLastMove = Buttons.CURSOR_RIGHT
        ui3.dragColour = 0
        ui3.curMoved = false
        assertEquals(
            MoveResult.Move("C:4"),
            Map.interpretMove(coloured, ui3, 0, 0, Buttons.CURSOR_SELECT, 20),
        )
    }

    @Test
    fun `arrows clamp at the edge and every arrow is a UI update`() {
        val s = state("fh,0123a")
        val ui = Map.newUi(s) as Map.Ui
        for (i in 0 until 10) {
            assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_LEFT, 20))
        }
        assertEquals(0, ui.curX)
        for (i in 0 until 10) {
            assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_DOWN, 20))
        }
        assertEquals(1, ui.curY)
        assertEquals(Buttons.CURSOR_DOWN, ui.curLastMove)
        assertTrue(ui.curMoved)
        assertTrue(ui.curVisible)
        // A modified arrow is unused.
        assertEquals(
            MoveResult.Unused,
            Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL, 20),
        )
    }

    @Test
    fun `unchanged input and unknown keys are unused`() {
        val s = state("fh,0123a")
        val ui = Map.newUi(s) as Map.Ui
        // A lone release with no press.
        assertEquals(MoveResult.Unused, Map.interpretMove(s, ui, 110, 30, Buttons.LEFT_RELEASE, 20))
        // A digit, tab, backspace, and an unknown key.
        for (key in listOf('1'.code, '\t'.code, '\b'.code, 'z'.code, 0x7F)) {
            assertEquals(MoveResult.Unused, Map.interpretMove(s, ui, 0, 0, key, 20))
        }
        // A drag into the border ends with a UI update and no move.
        Map.interpretMove(s, ui, 30, 30, Buttons.LEFT_BUTTON, 20)
        assertEquals(MoveResult.UiUpdate, Map.interpretMove(s, ui, 5, 5, Buttons.LEFT_RELEASE, 20))
        assertEquals(-2, ui.dragColour)
    }

    @Test
    fun `the cursor location and visibility use the tile rectangle`() {
        val s = state("fh,0123a")
        val ui = Map.newUi(s) as Map.Ui
        assertNull(Map.cursorLocation(ui, s, 20))
        Map.setCursorVisible(ui, true)
        assertEquals(Game.CursorRect(20, 20, 20, 20), Map.cursorLocation(ui, s, 20))
        Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_RIGHT, 20)
        Map.interpretMove(s, ui, 0, 0, Buttons.CURSOR_DOWN, 20)
        assertEquals(Game.CursorRect(40, 40, 20, 20), Map.cursorLocation(ui, s, 20))
        Map.setCursorVisible(ui, false)
        assertNull(Map.cursorLocation(ui, s, 20))
    }

    // ------------------------------------------------------------------
    // State, palette, drawstate.
    // ------------------------------------------------------------------

    @Test
    fun `copy shares the geometry and copies the mutable state`() {
        val a = state("fh,0123a")
        val b = a.copy() as Map.State
        assertSame(a.shared, b.shared)
        a.colouring[4] = 2
        a.pencil[4] = 3
        a.completed = true
        a.cheated = true
        assertNotEquals(a, b)
        assertEquals(-1, b.colouring[4])
        assertEquals(0, b.pencil[4])
        assertFalse(b.completed)
        assertFalse(b.cheated)
        assertNotEquals(a.hashCode(), b.hashCode())
        assertEquals(a, (a.copy() as Map.State))
        a.completed = false
        a.cheated = false
        a.colouring[4] = -1
        a.pencil[4] = 0
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `the palette and the size match the frozen trace`() {
        val colours = Map.colours()
        assertEquals(27, colours.size)
        val expected = listOf(
            0.8f, 0.8f, 0.8f,
            0.0f, 0.0f, 0.0f,
            0.627f, 0.157f, 0.133f,
            0.290f, 0.698f, 0.290f,
            0.322f, 0.690f, 0.788f,
            0.710f, 0.698f, 0.098f,
            1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f,
            0.0f, 0.0f, 0.0f,
        )
        assertEquals(expected, colours.toList())
        // A shell background replaces only colour 0.
        val dark = Map.colours(floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(0.1f, dark[0])
        assertEquals(0.2f, dark[1])
        assertEquals(0.3f, dark[2])
        assertEquals(0.0f, dark[3])
        assertEquals(0.627f, dark[6])

        assertEquals(Size(441, 341), Map.computeSize(Map.defaultParams(), 20))
        assertEquals(Size(141, 81), Map.computeSize(params("5x2n5"), 20))
        assertEquals(Size(4 * 48 + 97, 2 * 48 + 97), Map.computeSize(params("4x2n5"), 48))
    }

    @Test
    fun `the drawstate owns one blitter and redraws only dirty cells`() {
        val midend = Midend.fromGameId(Map, "5x2n5:fh,0123a")
        val sink = RecordingSink()
        midend.recordNewGameTrace(sink)
        assertEquals(1, sink.blitterNew)
        assertEquals(0, sink.blitterFree)
        assertEquals(23 to 23, sink.blitterSizes.single())
        val ds = midend.drawState as Map.DrawState
        assertEquals(10, ds.drawn.size)
        assertTrue(ds.started)
        // A fresh cache starts with every cell word at 0xFFFF.
        val freshSink = RecordingSink()
        val fresh = Map.newDrawState(midend.state, 20, freshSink) as Map.DrawState
        assertTrue(fresh.drawn.all { it == 0xFFFF })
        assertFalse(fresh.started)
        assertEquals(1, freshSink.blitterNew)
        Map.freeDrawState(fresh, freshSink)
        assertEquals(1, freshSink.blitterFree)

        // A second redraw on the same cache draws no cell.
        val before = sink.ops.count { it == "clip" }
        midend.recordRedraw(sink)
        assertEquals(before, sink.ops.count { it == "clip" })

        // A forced redraw frees and recreates the blitter.
        midend.forceRedraw()
        midend.recordRedraw(sink)
        assertEquals(1, sink.blitterFree)
        assertEquals(2, sink.blitterNew)

        // The explicit free releases the second blitter.
        midend.freeDrawState()
        assertEquals(2, sink.blitterFree)
    }

    @Test
    fun `a missing canonical point is skipped instead of aborting`() {
        // The C error-marker code asserts that every graph edge has a
        // canonical point and that the point is inside the grid. The
        // port defines a missing point as "draw no marker".
        val s = Map.executeMove(state("fh,0a0a0"), "0:1") as Map.State
        s.shared.edgex.fill(-1)
        s.shared.edgey.fill(-1)
        val sink = RecordingSink()
        val ds = Map.newDrawState(s, 20, sink)
        Map.redraw(s, Map.newUi(s), ds, sink, 20)
        assertTrue(sink.ops.contains("clip"), "the board still draws")
        assertTrue(
            sink.polys.none { it.second == 6 },
            "no error diamond is drawn",
        )
    }

    @Test
    fun `the colour labels and the stipple style change the drawing`() {
        val midend = Midend.fromGameId(Map, "5x2n5:fh,0123a")
        val ui = midend.ui as Map.Ui

        ui.showLabels = 1
        val regions = RecordingSink()
        midend.recordNewGameTrace(regions)
        assertTrue(regions.texts.any { it == "0" })
        assertTrue(regions.texts.any { it == "3" })

        ui.showLabels = 2
        val colours = RecordingSink()
        midend.forceRedraw()
        midend.recordRedraw(colours)
        assertTrue(colours.texts.any { it == "A" })
        assertTrue(colours.texts.any { it == "D" })

        // A pencil mark draws a dot with radius T/7 or T/4.
        val marked = Midend.fromGameId(Map, "5x2n5:fh,0123a")
        val markedUi = marked.ui as Map.Ui
        markedUi.showLabels = 0
        val base = RecordingSink()
        marked.recordNewGameTrace(base)
        marked.processKey(30, 30, Buttons.RIGHT_BUTTON, 20)
        marked.processKey(110, 30, Buttons.RIGHT_RELEASE, 20)
        // The drag blob is gone; the cell now has a pencil dot.
        marked.forceRedraw()
        val small = RecordingSink()
        marked.recordRedraw(small)
        assertTrue(small.circleRadii.contains(20 / 7))
        assertFalse(small.circleRadii.contains(20 / 4))
        markedUi.largeStipples = true
        marked.forceRedraw()
        val large = RecordingSink()
        marked.recordRedraw(large)
        assertTrue(large.circleRadii.contains(20 / 4))
    }

    @Test
    fun `a same-colour move marks the conflicting cell`() {
        // Regions 0 and 1 are adjacent columns and region 1 is blank.
        // Copying the clue colour 0 into region 1 is an error. No state
        // flag changes, and the board is not complete.
        val midend = Midend.fromGameId(Map, "5x2n5:fh,0a0a0")
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(30, 30, Buttons.LEFT_BUTTON, 20),
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(50, 30, Buttons.LEFT_RELEASE, 20),
        )
        assertEquals(0, midend.status())
        val state = midend.state as Map.State
        assertEquals(0, state.colouring[1])
        assertFalse(state.completed)
        val sink = RecordingSink()
        midend.recordNewGameTrace(sink)
        // The error diamond is a 4-point polygon in the error colour.
        val poly = sink.polys.filter { it.second == 6 }
        assertTrue(poly.isNotEmpty())
        assertTrue(poly.all { it.first.size == 8 })
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private fun findCursorForRegion(
        state: Map.State,
        region: Int,
        quadrant: Int,
    ): Pair<Int, Int>? {
        val w = state.params.w
        val h = state.params.h
        val wh = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (state.shared.map[quadrant * wh + y * w + x] == region) return x to y
            }
        }
        return null
    }

    private class RecordingSink : DrawSink {
        val ops = ArrayList<String>()
        val polys = ArrayList<Pair<IntArray, Int>>()
        val texts = ArrayList<String>()
        val circleRadii = ArrayList<Int>()
        var blitterNew = 0
        var blitterFree = 0
        val blitterSizes = ArrayList<Pair<Int, Int>>()

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
            ops += "rect"
        }

        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {
            ops += "line"
        }

        override fun thickLine(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            thickness: Float,
            colour: Int,
        ) {
            ops += "thick_line"
        }

        override fun circle(cx: Int, cy: Int, radius: Int, fillColour: Int, outlineColour: Int) {
            ops += "circle"
            circleRadii += radius
        }

        override fun thickCircle(
            cx: Int,
            cy: Int,
            radius: Int,
            thickness: Float,
            fillColour: Int,
            outlineColour: Int,
        ) {
            ops += "thick_circle"
        }

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {
            ops += "poly"
            polys += points.copyOf() to fillColour
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
            ops += "text"
            texts += text
        }

        override fun clip(x: Int, y: Int, width: Int, height: Int) {
            ops += "clip"
        }

        override fun unclip() {
            ops += "unclip"
        }

        override fun update(x: Int, y: Int, width: Int, height: Int) {
            ops += "update"
        }

        override fun status(text: String) {
            ops += "status"
        }

        override fun inertiaFollow(isSolved: Boolean) {}

        override fun blitterNew(width: Int, height: Int): Int {
            blitterNew++
            blitterSizes += width to height
            return blitterNew
        }

        override fun blitterFree(id: Int) {
            blitterFree++
        }

        override fun blitterSave(id: Int, x: Int, y: Int) {
            ops += "blitter_save"
        }

        override fun blitterLoad(id: Int, x: Int, y: Int) {
            ops += "blitter_load"
        }
    }
}
