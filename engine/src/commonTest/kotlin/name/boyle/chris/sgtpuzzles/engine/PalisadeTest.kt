package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: palisade.
 *
 * Kotlin-only pins for the rules that the oracle protocol cannot
 * express, plus the fixed vectors behind the parity suite: the
 * parameter quirks, the validation order, the run encoder, the move
 * parser, the solver, the cursor geometry, and the huge-area guard.
 */
class PalisadeTest {

    private companion object {
        const val TILE = 48
    }

    private fun params(text: String): Palisade.Params = Palisade.decodeParams(text) as Palisade.Params

    private fun state(desc: String, paramsText: String = "5x5n5"): Palisade.State =
        Palisade.newState(params(paramsText), desc) as Palisade.State

    private fun desc(paramsText: String, seed: String): String =
        Palisade.newDesc(params(paramsText), RandomState.fromSeed(seed)).text

    @Test
    fun `defaults and presets match the C tables`() {
        assertEquals(Palisade.Params(5, 5, 5), Palisade.defaultParams())
        val presets = Palisade.presets()
        assertEquals(4, presets.size)
        assertEquals("5 x 5, regions of size 5", presets[0].title)
        assertEquals("5x5n5", presets[0].params)
        assertEquals("8 x 6, regions of size 6", presets[1].title)
        assertEquals("8x6n6", presets[1].params)
        assertEquals("10 x 8, regions of size 8", presets[2].title)
        assertEquals("10x8n8", presets[2].params)
        assertEquals("15 x 12, regions of size 10", presets[3].title)
        assertEquals("15x12n10", presets[3].params)

        // `encode_params` ignores `full`, so short and long are equal.
        for (preset in presets) {
            val p = Palisade.decodeParams(preset.params)
            assertEquals(preset.params, Palisade.encodeParams(p, false))
            assertEquals(preset.params, Palisade.encodeParams(p, true))
        }
    }

    @Test
    fun `decode quirks match the oracle`() {
        fun decode(text: String): Palisade.Params = params(text)

        assertEquals(Palisade.Params(8, 8, 8), decode("8"))
        assertEquals(Palisade.Params(8, 6, 8), decode("8x6"))
        assertEquals(Palisade.Params(8, 6, 0), decode("8x6n"))
        assertEquals(Palisade.Params(8, 6, 6), decode("8x6n6x"))
        assertEquals(Palisade.Params(8, 8, 8), decode("+8x6n6"))
        assertEquals(Palisade.Params(8, 8, 8), decode("8X6N6"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5n5 "))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5n05"))
        assertEquals(Palisade.Params(5, 5, 5), decode("005x5n5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5n+5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5n 5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5n5.5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5n5n5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5x5n5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5n5"))
        assertEquals(Palisade.Params(5, 5, 5), decode("5x5x"))
        assertEquals(Palisade.Params(5, 5, 0), decode("5x5n"))
        assertEquals(Palisade.Params(0, 5, 5), decode("x5n5"))
    }

    @Test
    fun `validation messages are exact and ordered`() {
        fun message(text: String, full: Boolean = true): String? =
            Palisade.validateParams(params(text), full)

        assertEquals("Region size must be at least one", message("5x5n0"))
        assertEquals("Region size must be at least one", message("0x5n0"))
        assertEquals("Region size must be at least one", message("5x0n0"))
        assertEquals("Width must be at least one", message("0x5n5"))
        assertEquals("Height must be at least one", message("5x0n5"))
        assertEquals("Width times height must not be unreasonably large", message("46341x46341n5"))
        assertEquals("Region size must divide grid area", message("5x5n6"))
        assertEquals("Region size must divide grid area", message("5x5n2"))
        assertEquals("Region size must be less than the grid area", message("5x5n25"))
        assertEquals("Region size can't be two unless width or height is one", message("2x2n2"))
        assertNull(message("1x4n2"))
        assertNull(message("5x5n1"))
        assertNull(message("5x5n5"))

        // A short validation accepts the full-only cases.
        assertNull(message("5x5n25", full = false))
        assertNull(message("2x2n2", full = false))
    }

    @Test
    fun `the huge area is accepted by validation and guarded at generation`() {
        // C accepts 46340x46340: 2,147,395,600 is below INT_MAX.
        assertNull(Palisade.validateParams(Palisade.Params(46340, 46340, 5), true))
        // One more on each side is over INT_MAX.
        assertEquals(
            "Width times height must not be unreasonably large",
            Palisade.validateParams(Palisade.Params(46341, 46341, 5), true),
        )

        // The deviation D-007 boundary: 2^20 cells is still valid, and
        // generation refuses above it before any draw or allocation.
        assertNull(Palisade.validateParams(Palisade.Params(1024, 1024, 8), true))
        assertNull(Palisade.validateParams(Palisade.Params(1025, 1024, 8), true))
        val error = assertFailsWith<IllegalArgumentException> {
            Palisade.newDesc(Palisade.Params(1025, 1024, 8), RandomState.fromSeed("1"))
        }
        assertEquals("Width times height is too large for this build", error.message)
        assertFailsWith<IllegalArgumentException> {
            Palisade.newDesc(Palisade.Params(46340, 46340, 5), RandomState.fromSeed("1"))
        }
    }

    @Test
    fun `descriptions match the frozen preset vectors`() {
        val vectors = listOf(
            Triple("5x5n5", "1", "d21c1a3d3c2"),
            Triple("5x5n5", "2", "b1c23b2d33a2e2"),
            Triple("5x5n5", "3", "c1d3d2a1a1e32"),
            Triple("5x5n5", "12345", "a313d3d3f32a1"),
            Triple("5x5n5", "seed", "c13e13c2h2"),
            Triple("5x5n5", "another seed", "a23222b3a3d3d3"),
            Triple("5x5n5", "0", "a2b22b12c33a2a3a22b2"),
            Triple("5x5n5", "-7", "2d3d3a1b1a3d21"),
            Triple("5x5n5", "", "2a322b33h2e1"),
            Triple("8x6n6", "1", "i2d1b1b32c2221e3a212b12a3232"),
            Triple("8x6n6", "2", "a331c2a33a3a2c1s33b31"),
            Triple("8x6n6", "3", "a2b231a1d21a3c2g1c12f2e13"),
            Triple("8x6n6", "12345", "c22d21b1a11222c12b1d2d3c1c123"),
            Triple("8x6n6", "seed", "2b21c212232d2b1k2a1b3a2a2c1"),
            Triple("8x6n6", "another seed", "3321a2d1b12d32b3a1a1g3"),
            Triple("8x6n6", "0", "a1d1b3b121a1g22j12a12c1a2"),
            Triple("8x6n6", "-7", "a12b2a2b3a1a12d2a221e12g2b1c2"),
            Triple("8x6n6", "", "a1f2a2a3b12b2d21c1223d3a13"),
            Triple("10x8n8", "12345", "a1c122a3b3b2c31a3b223g02i1e3i1a0c1b1c1b13"),
            Triple("10x8n8", "1", "b12b211d2b22a2b1a21b33220b21e2c0m122a2f22a1"),
            Triple("10x8n8", "2", "a2e21a1c0e12b0a1i11l21c1a22a23332a1b1c1d2"),
            Triple("10x8n8", "3", "a112a1h312a0a222a3c2c10b20g223g2i2a1b2b11a2a1"),
            Triple("10x8n8", "seed", "3c11a2b113d0b1b11d1c1a2d2b2a1a33a1e2c1b331d2a2c231"),
            Triple("10x8n8", "-7", "a2a11a212a11e222a1f2k2c2c1a2d230c1a0a2d2a2a22"),
            Triple("15x12n10", "12345", "h12a33b32a1c012a33b20h33b1b33c3a1c2a1b3131a3a12a3d3a20c2211a2b3h11b21301f311b1a21e1b2a12211a310d33c111c1a1a1e2c2a2b2"),
            Triple("15x12n10", "1", "c1a2b2a1b2b11b1c0a2e3a1121a321b12a3d3d02b01d0c2c2c0d1a3c3d2c012c3a0f321b1a1j1c2c001a3b233g2b11a22a11j1"),
            Triple("15x12n10", "2", "a1m22c12a1b1a1g2b13a12b2c2a212a203c101d1d1a323a1a221a1302b1c31a3b2d3e33a3d3d11a3g1b3c03c11a2b3a21c1a23a21d2d22"),
            Triple("15x12n10", "3", "213b3b1g22a1b31a3g22b2211b3d2b21332b11e1c21a22111a01d3a12a2a0a1b22a211a121b213a203b11c1e1g1e2b223112111a323c1c2e1232c1"),
            Triple("15x12n10", "seed", "3f1e2a1c0a3c2c110a22e1a112f03222b22a221c23a20a2a22a1a2a2b1d12a1c22f1b1b3a11e1c3223e1e1a1c1a22c3b3a1a32220121a1b2a2c1"),
            Triple("15x12n10", "another seed", "c11l2212b3c22e12a11a102c10a2c1d0b21a122f0b3b3d2b2a2b0d3a0d2b1a2a32l222e112a231c2c3c023a11232b11e1b1a222a13"),
            Triple("15x12n10", "0", "33f123a3a2a1f1b0a1b1a0b12g1211d1a122e3a2a0a121a2d1a2a21c213221022a1e0f2213c2a310c223c2h1d0b11a1b2211b3a112b11"),
            Triple("15x12n10", "-7", "b112a21a311c3a1d1d2a11233c1e3c31230123c11a1b0a2b2d1b0o1f1f31c0g3a2a1b01a1b1b12a2a3c123f02b2222a0d2b111a2"),
            Triple("15x12n10", "", "b1i21b31a10g12c1i32e2b21g0a3203a02h31g1a22b3b1b2c22c2e00d1c101a2a2c3c332d132a23a2e1d12a13b23221b1"),
        )
        for ((paramsText, seed, expected) in vectors) {
            assertEquals(expected, desc(paramsText, seed), "$paramsText seed '$seed'")
        }
    }

    @Test
    fun `the numeric seed sweep matches the oracle`() {
        val fiveByFive = listOf(
            "a2b22b12c33a2a3a22b2", "d21c1a3d3c2", "b1c23b2d33a2e2",
            "c1d3d2a1a1e32", "e1c1h3a2c2", "d33a2a131a2a2d22a1",
            "b1c232a2d33g2", "2d32b1b1e3d1", "2b1a1e2221",
            "g133d2b22c13", "c2c1a1b3d2a2a2b2", "2a2d1d2b3a2c13",
            "2a21l11d3", "f33b2a33b13a32c3", "f21c2c1a2b2b2",
            "b1c33a2a33e32c22", "2c3d122b3d3a2b3", "23b2a2e3c2d1a23",
            "a2a221b3d3a33g2", "2b33b3c1d3a1a223", "a1e3b13d2e233",
        )
        for ((seed, expected) in fiveByFive.withIndex()) {
            assertEquals(expected, desc("5x5n5", seed.toString()), "seed $seed")
        }

        val eightBySix = listOf(
            "a1d1b3b121a1g22j12a12c1a2", "i2d1b1b32c2221e3a212b12a3232",
            "a331c2a33a3a2c1s33b31", "a2b231a1d21a3c2g1c12f2e13",
            "2c1c23b33b3a1a32e32c1a31e23", "b22b1c1a1a1a1h33b121b3b23a31b2a3",
            "e12g1b1k2d30b1a22c12", "d2a1d22a12i3h2233e23213",
            "f12e22a1c3f23a2d1b2a2a222312", "a22d211d23d2f33d22d1212a2b3",
            "a1b2c2b32f3221b3b1d33g212a1",
        )
        for ((seed, expected) in eightBySix.withIndex()) {
            assertEquals(expected, desc("8x6n6", seed.toString()), "seed $seed")
        }
    }

    @Test
    fun `the run encoder drops the trailing run and repeats z`() {
        assertEquals("", Palisade.encodeClues(IntArray(25) { -1 }))
        // A clue at the end keeps the run before it; the trailing run is
        // dropped.
        assertEquals("0", Palisade.encodeClues(IntArray(26) { if (it == 0) 0 else -1 }))
        assertEquals("a0", Palisade.encodeClues(IntArray(26) { if (it == 1) 0 else -1 }))
        assertEquals("z0", Palisade.encodeClues(IntArray(27) { if (it == 26) 0 else -1 }))
        assertEquals("za0", Palisade.encodeClues(IntArray(28) { if (it == 27) 0 else -1 }))
        assertEquals("zz0", Palisade.encodeClues(IntArray(53) { if (it == 52) 0 else -1 }))
        assertEquals("zza0", Palisade.encodeClues(IntArray(54) { if (it == 53) 0 else -1 }))
        assertEquals("1a3", Palisade.encodeClues(intArrayOf(1, -1, 3)))
        assertEquals("4", Palisade.encodeClues(intArrayOf(4)))
    }

    @Test
    fun `validate desc uses the ASCII grammar and the exact messages`() {
        val p = params("5x5n5")
        assertNull(Palisade.validateDesc(p, ""))
        assertNull(Palisade.validateDesc(p, "a313d3d3f32a1"))
        assertNull(Palisade.validateDesc(p, "a".repeat(25)))
        assertEquals(
            "Data describes too many squares",
            Palisade.validateDesc(p, "a".repeat(26)),
        )
        assertEquals(
            "Data describes too many squares",
            Palisade.validateDesc(p, "a".repeat(25) + "1"),
        )
        assertEquals(
            "Invalid (too large) number: '5'",
            Palisade.validateDesc(p, "5"),
        )
        assertEquals(
            "Invalid (too large) number: '9'",
            Palisade.validateDesc(p, "9"),
        )
        assertEquals(
            "Invalid character in data: '@'",
            Palisade.validateDesc(p, "@"),
        )
        assertEquals(
            "Invalid character in data: 'A'",
            Palisade.validateDesc(p, "A"),
        )
        assertEquals(
            "Invalid (unprintable) character in data",
            Palisade.validateDesc(p, "\t"),
        )
        // A 26-cell board accepts a z run, and 52 cells accept zz.
        assertNull(Palisade.validateDesc(params("1x26n2"), "z"))
        assertNull(Palisade.validateDesc(params("1x52n2"), "zz"))
    }

    @Test
    fun `new state decodes the description and the completed rule`() {
        val empty = state("") as Palisade.State
        assertEquals(25, empty.shared.clues.count { it == -1 })
        assertFalse(empty.completed)
        assertFalse(empty.cheated)
        assertEquals(1, empty.borderAt(0, 0) and 1)
        assertEquals(1, empty.borderAt(1, 0) and 1)

        // One clue, then 24 empty cells.
        val one = state("1") as Palisade.State
        assertEquals(1, one.shared.clues[0])
        assertEquals(1, one.shared.clues.count { it != -1 })

        // A full description that is already a solution still starts
        // open: `new_game` does not call `is_solved`.
        val solution = "1" + "a".repeat(24)
        assertFalse(state(solution).completed)

        // The k == w*h rule.
        assertTrue(state("", "5x5n25").completed)
        assertTrue(Midend.fromGameId(Palisade, "5x5n25:").status() == 1)

        // An upper-case run is C undefined behavior; this port rejects it.
        assertFailsWith<IllegalArgumentException> { state("A") }
        assertFailsWith<IllegalArgumentException> { state("z".repeat(2)) }
    }

    @Test
    fun `copy shares the clues and copies the borders`() {
        val original = state("1")
        val copy = original.copy() as Palisade.State
        assertSame(original.shared, copy.shared)
        copy.borders[0] = (copy.borders[0].toInt() xor 2).toByte()
        assertEquals(9, original.borderAt(0, 0))
        assertEquals(11, copy.borderAt(0, 0))
        assertEquals(original, state("1"))
        assertTrue(original != copy)
    }

    @Test
    fun `the move parser follows the C sscanf grammar`() {
        val base = state("")

        val plus = Palisade.executeMove(base, "F+0,+0,+2") as Palisade.State
        assertEquals(2, plus.borderAt(0, 0) and 2)

        // `%d` skips white space, the commas are literal.
        val spaced = Palisade.executeMove(base, "F 1, 2, 3") as Palisade.State
        assertEquals(3, spaced.borderAt(1, 2) and 3)

        // The OFF bit on the rim is accepted; an ON bit that leaves the
        // board is not.
        val offAtRim = Palisade.executeMove(base, "F0,0,16") as Palisade.State
        assertEquals(16, offAtRim.borderAt(0, 0) and 16)
        assertNull(Palisade.executeMove(base, "F0,0,8"))
        assertNull(Palisade.executeMove(base, "F0,0,1"))

        // The low eight bits of the flag survive.
        val huge = Palisade.executeMove(base, "F0,0,512") as Palisade.State
        assertEquals(base.borderAt(0, 0), huge.borderAt(0, 0))
        val all = Palisade.executeMove(base, "F1,1,255") as Palisade.State
        assertEquals(255, all.borderAt(1, 1))

        // A sign, a cell out of bounds, junk, and a leading space are bad.
        assertNull(Palisade.executeMove(base, "F5,0,2"))
        assertNull(Palisade.executeMove(base, "F0,0,2F1,0,8x"))
        assertNull(Palisade.executeMove(base, " F0,0,2"))
        assertNull(Palisade.executeMove(base, "F0,0,2,"))
        assertNull(Palisade.executeMove(base, "F0,0"))

        // An empty move is a valid copy, and it re-tests completion.
        val empty = Palisade.executeMove(base, "") as Palisade.State
        assertEquals(base, empty)
    }

    @Test
    fun `one-sided borders differ, and a solved empty move completes`() {
        val base = state("", "2x1n1")
        // The left cell's R bit separates the two cells.
        val left = Palisade.executeMove(base, "F0,0,2") as Palisade.State
        assertTrue(left.completed)
        // The right cell's L bit is ignored by the region graph.
        val right = Palisade.executeMove(base, "F1,0,8") as Palisade.State
        assertFalse(right.completed)
        // The completed board re-tests on an empty move and stays so.
        assertTrue((Palisade.executeMove(left, "") as Palisade.State).completed)
        // The OFF bit alone does not complete anything.
        assertFalse((Palisade.executeMove(base, "F0,0,16") as Palisade.State).completed)
    }

    @Test
    fun `both-bits edges are accepted and then ignored by the input`() {
        // Both sides of the edge between (0,0) and (1,0) have the ON
        // and the OFF bit. C accepts this through a hand-written move.
        val both = Palisade.executeMove(state(""), "F0,0,34F1,0,136") as Palisade.State
        assertEquals(34, both.borderAt(0, 0) and 34)
        assertEquals(136, both.borderAt(1, 0) and 136)

        // The cursor at (2,1) selects the left edge of (1,0). Value 6
        // matches no case and the both-bits guard returns Unused.
        val ui = Palisade.Ui()
        ui.show = true
        ui.x = 2
        ui.y = 1
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(both, ui, 0, 0, Buttons.CURSOR_SELECT, TILE),
        )

        // A click on the right band of (0,0) selects the same edge and
        // also falls through, after it moved and hid the cursor.
        val clicked = Palisade.Ui()
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(
                both,
                clicked,
                TILE / 2 + 40,
                TILE / 2 + 24,
                Buttons.LEFT_BUTTON,
                TILE,
            ),
        )
        assertEquals(2, clicked.x)
        assertEquals(1, clicked.y)
        assertFalse(clicked.show)
    }

    @Test
    fun `an S move sets the borders and cheats`() {
        val allA = Palisade.executeMove(state(""), "S" + "A".repeat(25)) as Palisade.State
        assertTrue(allA.completed)
        assertTrue(allA.cheated)
        assertEquals(1, Palisade.status(allA))
        assertEquals(0xE1, allA.borderAt(0, 0))
        assertNull(Palisade.executeMove(state(""), "S" + "A".repeat(24)))
        assertNull(Palisade.executeMove(state(""), "S" + "A".repeat(26)))
        assertNull(Palisade.executeMove(state(""), "S" + "A".repeat(24) + "\u0000"))

        // `S` ignores the clues and always completes.
        val contradiction = Palisade.executeMove(state("3"), "S" + "A".repeat(25)) as Palisade.State
        assertTrue(contradiction.completed)
    }

    @Test
    fun `the half-grid cursor sequence completes the puzzle`() {
        val midend = Midend.fromGameId(Palisade, "2x3n3:d33")
        assertEquals(0, midend.status())
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        for (key in sequence) {
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, key, TILE))
        }
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
        assertEquals(
            listOf("F1,0,8F0,0,2", "F1,1,8F0,1,2", "F1,2,8F0,2,2"),
            midend.serialise().values("MOVE"),
        )
        assertEquals(
            "+---+---+\n|   |   |\n+   +   +\n|   |   |\n+   +   +\n| 3 | 3 |\n+---+---+\n",
            midend.textFormat(),
        )
    }

    @Test
    fun `the full-grid cursor sequence makes the same board`() {
        val midend = Midend.fromGameId(Palisade, "2x3n3:d33")
        (midend.ui as Palisade.Ui).legacyCursor = true
        val sequence = listOf(
            'l'.code,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            'l'.code,
            Buttons.CURSOR_UP,
            'l'.code,
        )
        for (key in sequence) {
            assertEquals(KeyOutcome.SomeEffect, midend.processKey(0, 0, key, TILE))
        }
        assertEquals(1, midend.status())
        assertEquals(
            listOf("F0,0,2F1,0,8", "F0,2,2F1,2,8", "F0,1,2F1,1,8"),
            midend.serialise().values("MOVE"),
        )
        assertEquals(
            "+---+---+\n|   |   |\n+   +   +\n|   |   |\n+   +   +\n| 3 | 3 |\n+---+---+\n",
            midend.textFormat(),
        )
    }

    @Test
    fun `the HJKL aliases act as control and shift arrows`() {
        // In half-grid mode control and shift are ignored, so the letter
        // keys are plain cursor moves.
        val half = Midend.fromGameId(Palisade, "2x3n3:d33")
        val halfUi = half.ui as Palisade.Ui
        halfUi.show = true
        halfUi.x = 2
        halfUi.y = 1
        assertEquals(
            MoveResult.UiUpdate,
            Palisade.interpretMove(half.state, halfUi, 0, 0, 'l'.code, TILE),
        )
        assertEquals(3, halfUi.x)
        assertEquals(1, halfUi.y)

        // In full-grid mode `l` is CTRL+RIGHT and `L` is SHFT+RIGHT.
        val full = half.state as Palisade.State
        val fullUi = Palisade.Ui()
        fullUi.show = true
        fullUi.legacyCursor = true
        fullUi.x = 1
        fullUi.y = 1
        assertEquals(
            MoveResult.Move("F0,0,2F1,0,8"),
            Palisade.interpretMove(full, fullUi, 0, 0, 'l'.code, TILE),
        )
        // The edge is now ON, so shift acts as the right button and
        // clears it to OFF.
        val afterOn = full.copy() as Palisade.State
        afterOn.borders[0] = (afterOn.borders[0].toInt() or 2).toByte()
        assertEquals(
            MoveResult.Move("F0,0,2F1,0,8"),
            Palisade.interpretMove(afterOn, fullUi, 0, 0, 'L'.code, TILE),
        )
        // A plain arrow moves the full-grid cursor by one cell.
        fullUi.x = 1
        fullUi.y = 1
        assertEquals(
            MoveResult.UiUpdate,
            Palisade.interpretMove(full, fullUi, 0, 0, Buttons.CURSOR_RIGHT, TILE),
        )
        assertEquals(3, fullUi.x)
        assertEquals(1, fullUi.y)
        // At the right edge the cursor is clamped, and control+right has
        // no direction: C returns Unused and still shows the cursor.
        fullUi.legacyCursor = false
        val edgeUi = Palisade.Ui()
        edgeUi.legacyCursor = true
        edgeUi.x = 3
        edgeUi.y = 1
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(full, edgeUi, 0, 0, 'l'.code, TILE),
        )
        assertTrue(edgeUi.show)
    }

    @Test
    fun `a click selects the nearest edge and moves the cursor`() {
        val p = state("")
        // Cell (0,0) at tile 48: margin 24. The left band points out of
        // the board, so C returns Unused after it moved the cursor.
        val ui = Palisade.Ui()
        ui.x = 3
        ui.y = 3
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(p, ui, 24 + 8, 24 + 24 + 8, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(1, ui.x)
        assertEquals(1, ui.y)
        assertTrue(ui.show.not())
        val inside = Palisade.Ui()
        assertEquals(
            MoveResult.Move("F0,0,2F1,0,8"),
            Palisade.interpretMove(p, inside, 24 + 40, 24 + 24, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(2, inside.x)
        assertEquals(1, inside.y)
        assertFalse(inside.show)
        // The right button reverses the cycle and yields the OFF bit.
        assertEquals(
            MoveResult.Move("F0,0,32F1,0,128"),
            Palisade.interpretMove(p, Palisade.Ui(), 24 + 40, 24 + 24, Buttons.RIGHT_BUTTON, TILE),
        )
        // The center and the top-left corner both resolve to one edge.
        assertEquals(
            MoveResult.Move("F1,1,4F1,2,1"),
            Palisade.interpretMove(p, Palisade.Ui(), 24 + 48 + 24, 24 + 48 + 24, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(
            MoveResult.Move("F1,1,1F1,0,4"),
            Palisade.interpretMove(p, Palisade.Ui(), 24 + 48, 24 + 48, Buttons.LEFT_BUTTON, TILE),
        )
        // A click in the letterbox or outside the cell does nothing.
        val letterbox = Palisade.Ui()
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(p, letterbox, 0, 0, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(1, letterbox.x)
        assertEquals(1, letterbox.y)
        assertFalse(letterbox.show)
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(p, Palisade.Ui(), 24 + 5 * TILE, 24, Buttons.LEFT_BUTTON, TILE),
        )
        // Middle click, drag, and release are unused.
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(p, Palisade.Ui(), 24, 24, Buttons.MIDDLE_BUTTON, TILE),
        )
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(p, Palisade.Ui(), 24, 24, Buttons.LEFT_DRAG, TILE),
        )
        assertEquals(
            MoveResult.Unused,
            Palisade.interpretMove(p, Palisade.Ui(), 24, 24, Buttons.LEFT_RELEASE, TILE),
        )
    }

    @Test
    fun `the cursor geometry follows the half-grid types`() {
        // Tile 48: margin 24, line 4, center offset 26.
        val corner = Palisade.cursorGeometry(48, 0, 0, false)
        assertEquals(26, corner.centerX)
        assertEquals(26, corner.centerY)
        assertEquals(16, corner.width)
        assertEquals(16, corner.height)
        assertFalse(corner.corners)

        val left = Palisade.cursorGeometry(48, 0, 1, false)
        assertEquals(16, left.width)
        assertEquals(32, left.height)

        val top = Palisade.cursorGeometry(48, 1, 0, false)
        assertEquals(32, top.width)
        assertEquals(16, top.height)

        val center = Palisade.cursorGeometry(48, 1, 1, false)
        assertEquals(50, center.centerX)
        assertEquals(50, center.centerY)
        assertEquals(32, center.width)
        assertEquals(32, center.height)
        assertFalse(center.corners)

        // Only the full-grid tile center uses the corner marks.
        assertTrue(Palisade.cursorGeometry(48, 1, 1, true).corners)
        assertFalse(Palisade.cursorGeometry(48, 0, 1, true).corners)

        val ui = Palisade.Ui()
        assertNull(Palisade.cursorLocation(ui, state(""), 48))
        ui.show = true
        ui.x = 1
        ui.y = 1
        assertEquals(
            Game.CursorRect(34, 34, 32, 32),
            Palisade.cursorLocation(ui, state(""), 48),
        )
    }

    @Test
    fun `request keys depends on the full-cursor flag`() {
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT),
            Palisade.requestKeys(params("5x5n5"), fullCursor = false),
        )
        assertEquals(
            KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT),
            Palisade.requestKeys(params("5x5n5")),
        )
        assertEquals(
            KeyRequest(
                listOf(
                    KeyLabel('H'.code, "Left", true),
                    KeyLabel('J'.code, "Bottom", true),
                    KeyLabel('K'.code, "Top", true),
                    KeyLabel('L'.code, "Right", true),
                ),
                ArrowMode.ARROWS_ONLY,
            ),
            Palisade.requestKeys(params("5x5n5"), fullCursor = true),
        )
    }

    @Test
    fun `compute size follows the tile formula`() {
        val p = params("5x5n5")
        val expected = mapOf(
            1 to Size(6, 6),
            2 to Size(13, 13),
            10 to Size(61, 61),
            48 to Size(292, 292),
            63 to Size(382, 382),
            82 to Size(499, 499),
            100 to Size(609, 609),
        )
        for ((tile, size) in expected) {
            assertEquals(size, Palisade.computeSize(p, tile), "tile $tile")
        }
        assertEquals(Size(436, 340), Palisade.computeSize(params("8x6n6"), 48))
        assertEquals(Size(532, 436), Palisade.computeSize(params("10x8n8"), 48))
        assertEquals(Size(772, 628), Palisade.computeSize(params("15x12n10"), 48))
    }

    @Test
    fun `the palette derives the flash and the line colours`() {
        assertEquals(
            "800,800,800 633,633,633 0,0,0 720,720,0 720,720,720 1000,0,0",
            palette(Palisade.colours()),
        )
        val custom = Palisade.colours(floatArrayOf(0.5f, 0.5f, 0.5f))
        assertEquals(
            "500,500,500 333,333,333 0,0,0 450,450,0 450,450,450 1000,0,0",
            palette(custom),
        )
    }

    private fun palette(colours: FloatArray): String =
        (0 until colours.size / 3).joinToString(" ") { index ->
            (0 until 3).joinToString(",") { channel ->
                (colours[index * 3 + channel] * 1000.0f).roundToInt().toString()
            }
        }

    @Test
    fun `the solver finds the stored solution from the rim`() {
        val state = state("a313d3d3f32a1") as Palisade.State
        val borders = IntArray(25)
        for (i in 0 until 25) borders[i] = state.borderAt(i % 5, i / 5)
        assertTrue(Palisade.solver(state.shared, state.shared.clues, borders))
        val expected = "SKMAGKHGJMBJKNKJNHGJNMFMDG"
        val actual = buildString {
            append('S')
            for (value in borders) append((value or '@'.code).toChar())
        }
        assertEquals(expected, actual)

        // A solved state with the deduced ON bits is complete.
        val solved = Palisade.executeMove(state, expected) as Palisade.State
        assertTrue(solved.completed)
        assertTrue(solved.cheated)
    }

    @Test
    fun `is solved rejects a stray ON border`() {
        val shared = Palisade.Shared(2, 2, 4, IntArray(4) { -1 })
        val borders = ByteArray(4)
        // The rim, as `init_borders` builds it.
        borders[0] = (1 or 8).toByte()
        borders[1] = (1 or 2).toByte()
        borders[2] = (4 or 8).toByte()
        borders[3] = (2 or 4).toByte()
        assertTrue(Palisade.isSolved(shared, borders))

        // An extra wall between (0,0) and (1,0) does not split the
        // region: the other three cells still connect around it. The
        // wall is stray, so the board is not solved.
        val stray = borders.copyOf()
        stray[0] = (stray[0].toInt() or 2).toByte()
        stray[1] = (stray[1].toInt() or 8).toByte()
        assertFalse(Palisade.isSolved(shared, stray))
    }

    @Test
    fun `solve returns the aux verbatim and computes from the rim`() {
        val generated = Midend.newGame(Palisade, "5x5n5", "12345")
        val state = generated.state
        val aux = generated.auxInfo!!
        assertEquals("SKMAGKHGJMBJKNKJNHGJNMFMDG", aux)
        assertEquals(aux, Palisade.solve(state, aux))
        // Garbage aux is returned verbatim, and the midend then rejects
        // the move; C has the same two steps.
        assertEquals("garbage", Palisade.solve(state, "garbage"))

        val fromId = Midend.fromGameId(Palisade, "5x5n5:a313d3d3f32a1")
        assertEquals(
            "SKMAGKHGJMBJKNKJNHGJNMFMDG",
            Palisade.solve(fromId.state, null),
        )
        assertTrue(fromId.solve())
        assertEquals(1, fromId.status())

        // A board the local techniques cannot finish.
        val empty = Midend.fromGameId(Palisade, "5x5n5:")
        assertNull(Palisade.solve(empty.state, null))
        assertFalse(empty.solve())

        // k == 1 and a domino path solve from the sizes alone.
        val singles = Midend.fromGameId(Palisade, "5x5n1:")
        assertEquals("S" + "O".repeat(25), Palisade.solve(singles.state, null))
        val dominos = Midend.fromGameId(Palisade, "1x4n2:")
        assertEquals("SKNKN", Palisade.solve(dominos.state, null))
        // An empty aux is an empty no-op move, and the midend accepts it
        // as a valid move.
        val emptyAux = Midend.fromGameId(Palisade, "1x4n2:")
        assertEquals("", Palisade.solve(emptyAux.state, ""))
        assertTrue(emptyAux.solve())
        assertEquals(2, emptyAux.stateCount)
    }

    @Test
    fun `the text format prints only the U and L edges`() {
        val emptyFrame =
            "+---+---+---+---+---+\n" +
                "|                   |\n" +
                "+   +   +   +   +   +\n" +
                "|                   |\n" +
                "+   +   +   +   +   +\n" +
                "|                   |\n" +
                "+   +   +   +   +   +\n" +
                "|                   |\n" +
                "+   +   +   +   +   +\n" +
                "|                   |\n" +
                "+---+---+---+---+---+\n"
        assertEquals(emptyFrame, Palisade.textFormat(state("")))

        // A one-sided right or down border is invisible in the text.
        assertEquals(
            emptyFrame,
            Palisade.textFormat(Palisade.executeMove(state(""), "F0,0,2")!!),
        )
        assertEquals(
            emptyFrame,
            Palisade.textFormat(Palisade.executeMove(state(""), "F0,0,4")!!),
        )

        val default =
            "+---+---+---+---+---+\n" +
                "|     3   1   3     |\n" +
                "+   +   +   +   +   +\n" +
                "|             3     |\n" +
                "+   +   +   +   +   +\n" +
                "|             3     |\n" +
                "+   +   +   +   +   +\n" +
                "|                   |\n" +
                "+   +   +   +   +   +\n" +
                "| 3   2       1     |\n" +
                "+---+---+---+---+---+\n"
        assertEquals(default, Palisade.textFormat(state("a313d3d3f32a1")))

        val honest =
            "+---+---+---+---+---+\n" +
                "|   | 3   1   3 |   |\n" +
                "+   +---+   +---+   +\n" +
                "|       |   | 3     |\n" +
                "+   +---+   +---+   +\n" +
                "|   |   |   | 3 |   |\n" +
                "+   +   +---+   +   +\n" +
                "|   |       |   |   |\n" +
                "+---+   +---+   +---+\n" +
                "| 3   2 |     1     |\n" +
                "+---+---+---+---+---+\n"
        val completion = honestCompletion(state("a313d3d3f32a1"), "SKMAGKHGJMBJKNKJNHGJNMFMDG")
        assertTrue(completion.completed)
        assertFalse(completion.cheated)
        assertEquals(honest, Palisade.textFormat(completion))

        val cheatedText =
            "+---+---+---+---+---+\n" +
                "|   | 3 x 1 x 3 |   |\n" +
                "+ x +---+ x +---+ x +\n" +
                "|   x   |   | 3 x   |\n" +
                "+ x +---+ x +---+ x +\n" +
                "|   |   |   | 3 |   |\n" +
                "+ x + x +---+ x + x +\n" +
                "|   |   x   |   |   |\n" +
                "+---+ x +---+ x +---+\n" +
                "| 3 x 2 |   x 1 x   |\n" +
                "+---+---+---+---+---+\n"
        val cheated = Palisade.executeMove(state("a313d3d3f32a1"), "SKMAGKHGJMBJKNKJNHGJNMFMDG")!!
        assertEquals(cheatedText, Palisade.textFormat(cheated))

        // The bottom line is a copy of the top frame even when the
        // bottom ON bits are cleared.
        val cleared = Palisade.executeMove(state(""), "S" + "A".repeat(25))!!
        val lines = Palisade.textFormat(cleared).trimEnd('\n').split('\n')
        assertEquals(lines.first(), lines.last())
    }

    @Test
    fun `changed state raises the event only for an honest completion`() {
        val open = state("a313d3d3f32a1")
        val honest = honestCompletion(open, "SKMAGKHGJMBJKNKJNHGJNMFMDG")
        val cheated = Palisade.executeMove(open, "S" + "A".repeat(25))!!
        val ui = Palisade.Ui()

        assertFalse(Palisade.changedState(ui, null, honest))
        assertTrue(Palisade.changedState(ui, open, honest))
        assertFalse(Palisade.changedState(ui, open, cheated))
        assertFalse(Palisade.changedState(ui, honest, honest))
        assertFalse(Palisade.changedState(ui, honest, open))
    }

    @Test
    fun `undo and redo keep the completion event`() {
        val midend = Midend.fromGameId(Palisade, "2x3n3:d33")
        val sequence = listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )
        for (key in sequence) midend.processKey(0, 0, key, TILE)
        assertTrue(midend.takeCompletedEvent())

        assertTrue(midend.undo())
        assertEquals(0, midend.status())
        assertFalse(midend.takeCompletedEvent())
        assertTrue(midend.redo())
        assertEquals(1, midend.status())
        assertTrue(midend.takeCompletedEvent())
    }

    @Test
    fun `restart from a completed game is not a completion event`() {
        val midend = Midend.fromGameId(Palisade, "2x3n3:d33")
        for (key in listOf(
            Buttons.CURSOR_RIGHT,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_SELECT,
        )) {
            midend.processKey(0, 0, key, TILE)
        }
        assertTrue(midend.takeCompletedEvent())
        assertTrue(midend.restart())
        assertFalse(midend.takeCompletedEvent())
        assertEquals(0, midend.status())
    }

    @Test
    fun `the generator reports a divvy retry`() {
        // Seed 353 needs three divvy attempts for 5x5n5, and the
        // description is the same as a single attempt would give.
        val generated = Palisade.newDescWithAttempts(
            params("5x5n5"),
            RandomState.fromSeed("353"),
        )
        // The winnow-order shuffle happens first, so this is not the
        // direct divvy count for the same seed. The first full-clue board
        // fails the solver gate, so the generator draws a second divvy
        // without reshuffling the winnow order.
        assertEquals(2, generated.divvyAttempts)
        assertEquals(1, generated.solverRetries)
        assertEquals("c333d2d2c1a1", generated.desc)
        assertEquals(26, generated.auxInfo.length)
        assertTrue(generated.auxInfo.startsWith("S"))
    }

    /** Builds the honest solution by toggling each interior wall once. */
    private fun honestCompletion(state: Palisade.State, aux: String): Palisade.State {
        val w = state.width
        val h = state.height
        val move = StringBuilder()
        for (i in 1 until aux.length) {
            val bits = aux[i].code and 15
            val x = (i - 1) % w
            val y = (i - 1) / w
            for (dir in listOf(1, 2)) {
                if (bits and (1 shl dir) == 0) continue
                val nx = x + (if (dir == 1) 1 else 0)
                val ny = y + (if (dir == 2) 1 else 0)
                if (nx >= w || ny >= h) continue
                move.append("F$x,$y,${1 shl dir}F$nx,$ny,${1 shl (dir xor 2)}")
            }
        }
        return Palisade.executeMove(state, move.toString()) as Palisade.State
    }
}
