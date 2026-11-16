package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: net.
 *
 * The parts of the port that do not need the oracle: the description
 * layout, the wrapping re-derivation, the loose move grammar, the
 * sticky completion flag, the status text, the UI record, the keyboard
 * helpers, the platform seed, and the C undefined-behaviour decisions.
 */
class NetTest {

    private fun state(params: String, desc: String): Net.State =
        Net.newState(Net.decodeParams(params), desc) as Net.State

    private fun move(state: Net.State, text: String): Net.State? =
        Net.executeMove(state, text) as Net.State?

    private fun statusText(midend: Midend): String = midend.statusText()

    private fun click(midend: Midend, x: Int, y: Int, button: Int) {
        midend.processKey(x, y, button, 32)
    }

    @Test
    fun `every Android preset is unique and has no barriers`() {
        val expected = listOf(
            Triple(5, 5, false),
            Triple(7, 7, false),
            Triple(9, 9, false),
            Triple(11, 11, false),
            Triple(5, 5, true),
            Triple(7, 7, true),
            Triple(9, 9, true),
            Triple(11, 11, true),
        )
        assertEquals(expected.size, Net.presets().size)
        for ((index, preset) in Net.presets().withIndex()) {
            val params = Net.decodeParams(preset.params) as Net.Params
            assertEquals(expected[index].first, params.width, "$index width")
            assertEquals(expected[index].second, params.height, "$index height")
            assertEquals(expected[index].third, params.wrapping, "$index wrapping")
            assertTrue(params.unique, "$index unique")
            assertEquals(0.0f, params.barrierProbability, "$index barrier")
        }
    }

    @Test
    fun `a description builds the expected tiles and barriers`() {
        // Cell 0 is R, then a vertical barrier to its right. Cell 1 is
        // U, then a horizontal barrier below it. The rest are single
        // wires. The border pass adds the four outer edges.
        val s = state("3x3", "1v2h3456789")
        assertEquals(1, s.tileAt(0, 0))
        assertEquals(2, s.tileAt(1, 0))
        assertEquals(0x02 or 0x04 or 0x01, s.barrierAt(0, 0))
        assertEquals(0x02 or 0x04 or 0x08, s.barrierAt(1, 0))
        assertEquals(0x02, s.barrierAt(1, 1))
        assertEquals(0x04, s.barrierAt(0, 1))
        // The bottom and right borders.
        assertEquals(0x08, s.barrierAt(0, 2) and 0x08)
        assertEquals(0x01, s.barrierAt(2, 0) and 0x01)

        // A marker on the last column or the last row wraps its other
        // side onto the border that is already set.
        val plain = state("3x3", "123456789")
        val wrapped = state("3x3", "123456789vh")
        assertEquals(plain.barrierAt(0, 0), wrapped.barrierAt(0, 0))
        assertEquals(0x08 or 0x01, wrapped.barrierAt(2, 2) and 0x09)
        assertEquals(0x04, wrapped.barrierAt(0, 2) and 0x04)
        assertEquals(0x02, wrapped.barrierAt(2, 0) and 0x02)
    }

    @Test
    fun `wrapping is re-derived from the barriers`() {
        // Every right border has its v, and every bottom border its h.
        // The other sides wrap onto those same edges, so the board is
        // de-facto non-wrapping even though the parameter says w.
        val s = state("3x3w", "123v456v7h8h9vh")
        assertFalse(s.wrapping)

        val midend = Midend.fromGameId(Net, "3x3w:123v456v7h8h9vh")
        val before = midend.serialise().serialise()
        val outcome = midend.processKey(
            0, 0,
            Buttons.CURSOR_RIGHT or Buttons.MOD_SHFT,
            32,
        )
        assertEquals(KeyOutcome.Unused, outcome)
        assertEquals(before, midend.serialise().serialise())

        // A generated wrapping board keeps wrapping true.
        val generated = Midend.newGame(Net, "5x5w", "12345")
        assertTrue((generated.state as Net.State).wrapping)
        assertEquals(0x00, (generated.state as Net.State).barrierAt(0, 0) and 0x09)
    }

    @Test
    fun `the move grammar matches C`() {
        val base = state("3x3", "123456789")
        for (text in listOf(
            "A1,1",
            "C1,1",
            "F1,1",
            "L1,1",
            "A0,0;L0,0",
            "A0,0A1,1",
            "A 0, 0",
            "A+0,+0",
            "A0,0;",
            "A0,0L1,1",
            "S",
            "J",
            "S;L0,0",
            "S;A0,0;C0,0",
        )) {
            assertNotNull(move(base, text), "accepts '$text'")
        }

        for (text in listOf(
            "",
            ";A0,0",
            "X1,1",
            "A9,9",
            "A0,0;;L1,1",
            "A0,0x",
            "A-1,0",
        )) {
            assertNull(move(base, text), "rejects '$text'")
        }

        val rotated = move(base, "A0,0")!!
        assertEquals(2, rotated.tileAt(0, 0))
        assertEquals(1, rotated.lastRotateDir)
        assertEquals(0, rotated.lastRotateX)
        assertEquals(0, rotated.lastRotateY)

        val flipped = move(base, "F0,0")!!
        assertEquals(4, flipped.tileAt(0, 0))
        assertEquals(2, flipped.lastRotateDir)

        // The three animation fields are cleared for a J or S move.
        val jumbled = move(base, "J")!!
        assertEquals(0, jumbled.lastRotateDir)
        assertEquals(0, jumbled.lastRotateX)
        assertEquals(0, jumbled.lastRotateY)
        assertTrue(move(base, "S")!!.usedSolve)
    }

    @Test
    fun `a solved description loads open and a lock completes it`() {
        // The AUXINFO of the pinned 5x5w game is a solved spanning tree.
        val desc = AuxInfo.decode("4c93bc8fb2d66ef80fc3ea157428c5fd2bcf4226645e5ba945")
        val s = state("5x5w", desc)
        assertFalse(s.completed)
        assertEquals(0, Net.status(s))

        val locked = move(s, "L0,0")!!
        assertTrue(locked.completed)
        assertEquals(1, Net.status(locked))

        val ui = Net.newUi(locked)
        assertEquals("COMPLETED! ", Net.statusText(locked, ui))
    }

    @Test
    fun `the completion event fires once, and not for a solve`() {
        val desc = AuxInfo.decode("4c93bc8fb2d66ef80fc3ea157428c5fd2bcf4226645e5ba945")
        val midend = Midend.fromSave(Net, SgtpSave.parse(saveText("5x5w", desc)))
        // A load raises no event; a live lock that completes the board
        // raises exactly one.
        assertFalse(midend.takeCompletedEvent())
        click(midend, 5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON)
        assertTrue(midend.takeCompletedEvent())
        assertFalse(midend.takeCompletedEvent())

        // A fresh solved description and a solve move: completed, but
        // midend_solve ignores changed_state's return value, so no
        // completion event is raised.
        val solving = Midend.fromSave(Net, SgtpSave.parse(saveText("5x5w", desc)))
        assertTrue(solving.solve())
        assertTrue((solving.state as Net.State).usedSolve)
        assertFalse(solving.takeCompletedEvent())
        assertEquals(1, solving.status())
        assertEquals("Auto-solved. ", solving.statusText())
    }

    @Test
    fun `undo and redo across a rotate, a lock, a jumble, and a solve`() {
        val midend = Midend.newGame(Net, "5x5", "12345")
        val initial = midend.state.copy()

        click(midend, 5 + 16, 5 + 16, Buttons.LEFT_BUTTON)
        click(midend, 5 + 16, 5 + 16, Buttons.LEFT_RELEASE)
        assertEquals(2, midend.stateCount)

        click(midend, 5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON)
        assertEquals(3, midend.stateCount)

        click(midend, 0, 0, 'J'.code)
        assertEquals(4, midend.stateCount)
        val jumbled = midend.state.copy()
        assertEquals(3, midend.serialise().values("MOVE").size)

        assertTrue(midend.solve())
        assertEquals(5, midend.stateCount)
        val solved = midend.state.copy()
        assertTrue((midend.state as Net.State).usedSolve)

        assertTrue(midend.undo())
        assertEquals(jumbled, midend.state)
        assertTrue(midend.undo())
        assertTrue(midend.undo())
        assertTrue(midend.undo())
        assertEquals(initial, midend.state)
        assertFalse(midend.canUndo)

        assertTrue(midend.redo())
        assertTrue(midend.redo())
        assertTrue(midend.redo())
        assertTrue(midend.redo())
        assertEquals(solved, midend.state)
        assertFalse(midend.canRedo)
    }

    @Test
    fun `the status text variants are the C patterns`() {
        val generated = Midend.newGame(Net, "5x5", "12345")
        assertEquals("Active: 9/25", statusText(generated))

        val solvedDesc = AuxInfo.decode("4c93bc8fb2d66ef80fc3ea157428c5fd2bcf4226645e5ba945")
        val solved = Midend.fromSave(Net, SgtpSave.parse(saveText("5x5w", solvedDesc)))
        assertEquals("Active: 25/25", statusText(solved))
        val completed = Midend.fromSave(
            Net,
            SgtpSave.parse(saveText("5x5w", solvedDesc, moves = listOf("L0,0"))),
        )
        assertEquals("COMPLETED! ", statusText(completed))

        // A source move to a blank cell omits the counter. A 3x3 board
        // with a blank centre is a hand-written description; its source
        // default is (1,1).
        val blank = state("3x3", "123406789")
        val ui = Net.newUi(blank) as Net.Ui
        assertEquals(1, ui.cx)
        assertEquals(1, ui.cy)
        assertEquals("", Net.statusText(blank, ui))

        // A source move changes only the UI, so the text follows it.
        val movedSource = Midend.fromGameId(Net, "3x3:123456789")
        val before = movedSource.state.copy()
        val outcome = movedSource.processKey(
            0, 0,
            Buttons.CURSOR_RIGHT or Buttons.MOD_CTRL,
            32,
        )
        assertEquals(KeyOutcome.SomeEffect, outcome)
        assertEquals(before, movedSource.state)
        assertEquals(1, movedSource.stateCount)
        assertEquals(2, (movedSource.ui as Net.Ui).cx)

        // An empty board has no counter because the source is blank.
        val empty = state("3x3", "000000000")
        assertEquals("", Net.statusText(empty, Net.newUi(empty)))
    }

    @Test
    fun `every preset solves with and without AUXINFO`() {
        for (params in Net.presets().map { it.params }) {
            val generated = Midend.newGame(Net, params, "12345")
            val desc = generated.serialise().value("DESC")!!
            val aux = generated.serialise().value("AUXINFO")!!.let { AuxInfo.decode(it) }

            val fromAux = Net.solveAttempt(generated.state, aux)
            assertNull(fromAux.error, "'$params' aux error")
            assertTrue(fromAux.move!!.startsWith("S"), "'$params' aux move")

            val fromSolver = Net.solveAttempt(generated.state, null)
            assertNull(fromSolver.error, "'$params' solver error")
            assertTrue(fromSolver.move!!.startsWith("S"))
            assertTrue(fromSolver.move.length >= 1)

            // The solver fallback keeps LOCKED marks, so it may be a
            // partial answer; it is still a valid move.
            assertNotNull(Net.executeMove(generated.state, fromSolver.move))

            // A solve after real moves diffs the current state, not the
            // initial one.
            val played = Midend.fromSave(Net, generated.serialise())
            click(played, 5 + 16, 5 + 16, Buttons.LEFT_BUTTON)
            click(played, 5 + 16, 5 + 16, Buttons.LEFT_RELEASE)
            assertTrue(played.solve())
            val solved = played.state as Net.State
            assertTrue(solved.usedSolve)
            assertTrue(solved.completed)
            assertNotNull(desc)
        }
    }

    @Test
    fun `a hand-written unsolvable description returns null`() {
        // A cross in the corner of a 1x2 board has a wire on every
        // closed border edge, so every orientation is inconsistent.
        val s = state("1x2", "f1")
        val attempt = Net.solveAttempt(s, null)
        assertEquals(null, attempt.move)
        assertEquals("No solution exists for this puzzle", attempt.error)
        assertNull(Net.solve(s, null))

        val midend = Midend.fromGameId(Net, "1x2:f1")
        assertFalse(midend.solve())
    }

    @Test
    fun `a non-rotation AUXINFO is defined, not an abort`() {
        // C asserts at net.c:1862. The port returns no move and a
        // message. The low nibble 1 is not a rotation of 0.
        val s = state("3x3", "123456789")
        val attempt = Net.solveAttempt(s, "000000000")
        assertEquals(null, attempt.move)
        assertEquals(
            "The auxiliary answer is not a rotation of the description",
            attempt.error,
        )

        // A short AUXINFO is read as zero characters, not past its end.
        // An all-blank board has no rotation to reject.
        val blank = state("3x3", "000000000")
        val short = Net.solveAttempt(blank, "0")
        assertNull(short.error)
        assertTrue(short.move!!.startsWith("S"))
        assertEquals(9, short.move.count { it == 'L' })
    }

    @Test
    fun `requestKeys is ARROWS_ONLY with J and ASDF`() {
        val request = Net.requestKeys(Net.defaultParams())
        assertEquals(ArrowMode.ARROWS_ONLY, request.arrowMode)
        assertEquals(
            listOf("Jumble", "Anticlockwise", "Lock", "Clockwise", "Flip"),
            request.labels.map { it.name },
        )
        assertEquals(
            listOf('J'.code, 'A'.code, 'S'.code, 'D'.code, 'F'.code),
            request.labels.map { it.button },
        )
        assertEquals(
            listOf(false, true, true, true, true),
            request.labels.map { it.needsArrows },
        )
        // The full-cursor flag is ignored.
        assertEquals(request, Net.requestKeys(Net.defaultParams(), true))
    }

    @Test
    fun `currentKeyLabel covers all four cases`() {
        val s = state("3x3", "123456789")
        val ui = Net.newUi(s) as Net.Ui
        ui.curX = 0
        ui.curY = 0
        assertEquals("Rotate", Net.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))
        assertEquals("Lock", Net.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT2))
        assertEquals("", Net.currentKeyLabel(ui, s, 'a'.code))

        ui.curVisible = false
        assertEquals("Rotate", Net.currentKeyLabel(ui, s, Buttons.CURSOR_SELECT))

        val locked = move(s, "L0,0")!!
        assertEquals("", Net.currentKeyLabel(ui, locked, Buttons.CURSOR_SELECT))
        assertEquals("Unlock", Net.currentKeyLabel(ui, locked, Buttons.CURSOR_SELECT2))
    }

    @Test
    fun `the UI record text and decoder preserve the base UI`() {
        val s = state("5x5", "1863151ea86e7e3c7abc21a32")
        val base = Net.newUi(s, ByteArray(16) { (it + 9).toByte() }) as Net.Ui
        base.curX = 3
        base.curY = 1
        base.curVisible = true
        base.unlockedLoops = false
        assertEquals("O0,0;C2,2", Net.encodeUi(base))

        val decoded = Net.decodeUi(s, "O1,2;C3,4", base) as Net.Ui
        assertEquals(1, decoded.orgX)
        assertEquals(2, decoded.orgY)
        assertEquals(3, decoded.cx)
        assertEquals(4, decoded.cy)
        assertEquals(3, decoded.curX)
        assertEquals(1, decoded.curY)
        assertTrue(decoded.curVisible)
        assertFalse(decoded.unlockedLoops)
        assertEquals(base.rs.encodeState(), decoded.rs.encodeState())

        // A partial match applies nothing.
        val partial = Net.decodeUi(s, "O1,2", base) as Net.Ui
        assertEquals(0, partial.orgX)
        assertEquals(0, partial.orgY)
        assertEquals(2, partial.cx)
        assertEquals(2, partial.cy)

        // Out-of-range pairs are ignored independently.
        val outOfRange = Net.decodeUi(s, "O9,0;C3,9", base) as Net.Ui
        assertEquals(0, outOfRange.orgX)
        assertEquals(0, outOfRange.orgY)
        assertEquals(2, outOfRange.cx)
        assertEquals(2, outOfRange.cy)

        // `%d` skips white space and accepts a sign; trailing junk is
        // ignored because the match already has four numbers.
        val spaced = Net.decodeUi(s, "O 1, 2;C+3,4junk", base) as Net.Ui
        assertEquals(1, spaced.orgX)
        assertEquals(2, spaced.orgY)
        assertEquals(3, spaced.cx)
        assertEquals(4, spaced.cy)
    }

    @Test
    fun `newUi defaults`() {
        val s = state("7x5", "0".repeat(35))
        val seed = ByteArray(16) { (it + 3).toByte() }
        val ui = Net.newUi(s, seed) as Net.Ui
        assertEquals(0, ui.orgX)
        assertEquals(0, ui.orgY)
        assertEquals(3, ui.cx)
        assertEquals(2, ui.cy)
        assertEquals(3, ui.curX)
        assertEquals(2, ui.curY)
        assertFalse(ui.curVisible)
        assertTrue(ui.unlockedLoops)
        assertEquals(-1, ui.dragTileX)
        assertEquals(-1, ui.dragTileY)
        assertEquals(RandomState.fromSeed(seed).encodeState(), ui.rs.encodeState())

        // The one-argument form uses the oracle's NUL seed.
        val default = Net.newUi(s) as Net.Ui
        assertEquals(RandomState.fromSeed(byteArrayOf(0)).encodeState(), default.rs.encodeState())
    }

    @Test
    fun `the platform seed changes only the Jumble`() {
        val seed = ByteArray(16) { (it + 42).toByte() }
        val nul = Midend.newGame(Net, "5x5", "12345", byteArrayOf(0))
        val other = Midend.newGame(Net, "5x5", "12345", seed)

        assertEquals(nul.id(), other.id())
        assertEquals(
            nul.serialise().serialise(),
            other.serialise().serialise(),
        )

        click(nul, 0, 0, 'J'.code)
        click(other, 0, 0, 'J'.code)
        val nulMove = nul.serialise().values("MOVE").single()
        val otherMove = other.serialise().values("MOVE").single()
        assertEquals(
            "J;A0,0;A2,0;F3,0;A4,0;F0,1;F1,1;F2,1;C3,1;C4,1;" +
                "F0,2;C1,2;A2,2;C3,2;A0,3;C1,3;F0,4;A1,4;A2,4;F3,4;A4,4",
            nulMove,
        )
        assertNotEquals(nulMove, otherMove)
        // The pinned second seed, so a change to the random draw order
        // is visible.
        assertEquals(
            "J;C0,0;C1,0;C3,0;C1,1;A3,1;A4,1;C0,2;F1,2;F3,2;" +
                "C2,3;A3,3;F4,3;C0,4;A1,4;A2,4;A3,4;C4,4",
            otherMove,
        )
    }

    @Test
    fun `the text form is absent`() {
        assertFalse(Net.canFormatAsText)
        val midend = Midend.newGame(Net, "5x5", "12345")
        assertEquals("", midend.textFormat())
    }

    @Test
    fun `the origin and source moves wrap and the origin is refused without wrapping`() {
        val midend = Midend.fromGameId(Net, "5x5w:8642ec1e6b11ae6cac23c395b")
        val ui = midend.ui as Net.Ui
        assertEquals(0, ui.orgX)

        // A wrapping origin move wraps backwards.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(
                0, 0,
                Buttons.CURSOR_LEFT or Buttons.MOD_SHFT,
                32,
            ),
        )
        assertEquals(4, ui.orgX)
        assertEquals(0, ui.orgY)

        // Shift and control together move both.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(
                0, 0,
                Buttons.CURSOR_UP or Buttons.MOD_SHFT or Buttons.MOD_CTRL,
                32,
            ),
        )
        assertEquals(4, ui.orgY)
        assertEquals(2, ui.cx)
        assertEquals(1, ui.cy)

        // A cursor move makes the cursor visible.
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.CURSOR_RIGHT, 32),
        )
        assertTrue(ui.curVisible)
        assertEquals(3, ui.curX)
    }

    @Test
    fun `a real rotation changes the board and the count while an inert click changes neither`() {
        val midend = Midend.fromGameId(Net, "5x5:1863151ea86e7e3c7abc21a32")
        val before = midend.serialise().serialise()
        val beforeTiles = (midend.state as Net.State).tiles.copyOf()

        // A press in the letterbox is inert.
        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, Buttons.LEFT_BUTTON, 32))
        assertEquals(before, midend.serialise().serialise())

        // A press in the LINE_THICK input band is inert.
        assertEquals(KeyOutcome.Unused, midend.processKey(5 + 31, 5 + 16, Buttons.LEFT_BUTTON, 32))
        assertEquals(before, midend.serialise().serialise())

        // A real press is inert; the release commits.
        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(5 + 16, 5 + 16, Buttons.LEFT_BUTTON, 32),
        )
        assertEquals(before, midend.serialise().serialise())
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(5 + 16, 5 + 16, Buttons.LEFT_RELEASE, 32),
        )
        assertEquals(2, midend.stateCount)
        assertNotEquals(beforeTiles.toList(), (midend.state as Net.State).tiles.toList())
        assertEquals(listOf("A0,0"), midend.serialise().values("MOVE"))

        // A rotate key on a locked tile returns Unused but makes the
        // cursor visible, as C does.
        val locked = Midend.fromGameId(Net, "5x5:1863151ea86e7e3c7abc21a32")
        click(locked, 5 + 16, 5 + 16, Buttons.MIDDLE_BUTTON)
        val ui = locked.ui as Net.Ui
        ui.curX = 0
        ui.curY = 0
        val count = locked.stateCount
        assertEquals(KeyOutcome.Unused, locked.processKey(0, 0, 'a'.code, 32))
        assertTrue(ui.curVisible)
        assertEquals(count, locked.stateCount)
    }

    @Test
    fun `a jumble of a fully locked board is the valid no-op J`() {
        val midend = Midend.fromGameId(Net, "3x3:123456789")
        for (y in 0 until 3) {
            for (x in 0 until 3) {
                click(midend, 5 + x * 32 + 16, 5 + y * 32 + 16, Buttons.MIDDLE_BUTTON)
            }
        }
        assertEquals(10, midend.stateCount)
        click(midend, 0, 0, 'J'.code)
        assertEquals(listOf("J"), midend.serialise().values("MOVE").takeLast(1))
        assertEquals(11, midend.stateCount)
    }

    @Test
    fun `the seam flags and the geometry are the C values`() {
        assertEquals("Net", Net.name)
        assertEquals("net", Net.backendName)
        assertEquals(32, Net.preferredTileSize)
        assertTrue(Net.canSolve)
        assertTrue(Net.wantsStatusBar)
        assertFalse(Net.canFormatAsText)
        assertEquals(Size(169, 169), Net.computeSize(Net.decodeParams("5x5"), 32))
        assertEquals(Size(511, 511), Net.computeSize(Net.decodeParams("5x5"), 100))

        val s = state("5x5", "1863151ea86e7e3c7abc21a32")
        val ds = Net.newDrawState(s, 32, KotlinDrawSink) as Net.DrawState
        assertEquals(7 * 7, ds.visible.size)
        assertEquals(7 * 7, ds.toDraw.size)
        assertTrue(ds.visible.all { it == -1 })
        assertTrue(ds.toDraw.all { it == 0 })
    }

    /** A sink that only counts operations, for the drawstate test. */
    private object KotlinDrawSink : DrawSink {
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun startDraw() {}
        override fun endDraw() {}
        override fun rect(x: Int, y: Int, width: Int, height: Int, colour: Int) {}
        override fun line(x1: Int, y1: Int, x2: Int, y2: Int, colour: Int) {}
        override fun thickLine(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            thickness: Float,
            colour: Int,
        ) {}

        override fun circle(
            cx: Int,
            cy: Int,
            radius: Int,
            fillColour: Int,
            outlineColour: Int,
        ) {}

        override fun poly(points: IntArray, fillColour: Int, outlineColour: Int) {}
        override fun text(
            x: Int,
            y: Int,
            font: Int,
            size: Int,
            align: Int,
            colour: Int,
            text: String,
        ) {}

        override fun clip(x: Int, y: Int, width: Int, height: Int) {}
        override fun unclip() {}
        override fun update(x: Int, y: Int, width: Int, height: Int) {}
        override fun status(text: String) {}
        override fun inertiaFollow(isSolved: Boolean) {}
        override fun blitterNew(width: Int, height: Int): Int = 0
        override fun blitterFree(id: Int) {}
        override fun blitterSave(id: Int, x: Int, y: Int) {}
        override fun blitterLoad(id: Int, x: Int, y: Int) {}
    }

    @Test
    fun `the palette derives the border and the locked tile from the background`() {
        val colours = Net.colours(floatArrayOf(0.8f, 0.8f, 0.8f))
        assertEquals(24, colours.size)
        val expected = floatArrayOf(
            0.8f, 0.8f, 0.8f,
            0.6f, 0.6f, 0.6f,
            0.4f, 0.4f, 0.4f,
            0f, 0f, 0f,
            0f, 0f, 1f,
            0f, 1f, 1f,
            1f, 0f, 0f,
            1f, 0f, 0f,
        )
        for (i in expected.indices) assertEquals(expected[i], colours[i], "channel $i")
        assertEquals(32, Net.preferredTileSize)
    }

    private fun saveText(
        params: String,
        desc: String,
        moves: List<String> = emptyList(),
        seed: String = "12345",
    ): String {
        val records = listOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Net.name),
            SaveRecord("PARAMS", params),
            SaveRecord("CPARAMS", params),
            SaveRecord("SEED", seed),
            SaveRecord("DESC", desc),
            SaveRecord("NSTATES", (moves.size + 1).toString()),
            SaveRecord("STATEPOS", (moves.size + 1).toString()),
        ) + moves.map { SaveRecord("MOVE", it) }
        return SgtpSave.of(records).serialise()
    }
}
