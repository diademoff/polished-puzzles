package name.boyle.chris.sgtpuzzles.engine

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. Game: blackbox.
 *
 * Kotlin-only pins for the palette, the geometry, the state copy and
 * equality, the description validator, the `UI` parser, the loose move
 * grammar, the lock thresholds, the generation guard, and the ray
 * property that no valid layout cycles.
 */
class BlackBoxTest {

    private val tile = 32

    @Test
    fun `defaults and presets match the C tables`() {
        val defaults = BlackBox.defaultParams() as BlackBox.Params
        assertEquals(BlackBox.Params(8, 8, 5, 5), defaults)
        assertEquals("w8h8m5M5", defaults.text)

        val presets = BlackBox.presets()
        assertEquals(5, presets.size)
        assertEquals(
            listOf(
                "5x5, 3 balls" to "w5h5m3M3",
                "8x8, 5 balls" to "w8h8m5M5",
                "8x8, 3-6 balls" to "w8h8m3M6",
                "10x10, 5 balls" to "w10h10m5M5",
                "10x10, 4-10 balls" to "w10h10m4M10",
            ),
            presets.map { it.title to it.params },
        )
        for (preset in presets) {
            val params = BlackBox.decodeParams(preset.params)
            assertEquals(preset.params, BlackBox.encodeParams(params, true))
            assertEquals(preset.params, BlackBox.encodeParams(params, false))
        }

        assertTrue(BlackBox.wantsStatusBar)
        assertTrue(BlackBox.canSolve)
        assertFalse(BlackBox.canFormatAsText)
        assertEquals(32, BlackBox.preferredTileSize)
        assertEquals("Black Box", BlackBox.name)
        assertEquals("blackbox", BlackBox.backendName)
    }

    @Test
    fun `custom params parse the ball field like sscanf`() {
        assertEquals(
            BlackBox.Params(5, 5, 3, 6),
            BlackBox.customParams("5", "5", "3-6"),
        )
        assertEquals(
            BlackBox.Params(5, 5, 3, 6),
            BlackBox.customParams("5", "5", "3-6junk"),
        )
        assertEquals(
            BlackBox.Params(5, 5, 5, 5),
            BlackBox.customParams("5", "5", "5"),
        )
        assertEquals(
            BlackBox.Params(5, 5, 5, 5),
            BlackBox.customParams("5", "5", "5-"),
        )
        assertEquals(
            BlackBox.Params(5, 5, -5, -5),
            BlackBox.customParams("5", "5", "-5"),
        )
        assertEquals(
            BlackBox.Params(5, 5, 0, 0),
            BlackBox.customParams("5", "5", "abc"),
        )
        // A literal '-' must follow the first conversion; a space makes
        // sscanf stop after one value, so atoi sets both.
        assertEquals(
            BlackBox.Params(5, 5, 3, 3),
            BlackBox.customParams("5", "5", "3 - 6"),
        )
        assertEquals(
            BlackBox.Params(5, 5, -3, 6),
            BlackBox.customParams("5", "5", "-3-6"),
        )
        assertEquals(
            BlackBox.Params(5, 5, 3, -6),
            BlackBox.customParams("5", "5", "3--6"),
        )
    }

    @Test
    fun `decode params follows atoi and the last value wins`() {
        assertEquals(BlackBox.Params(8, 8, 5, 5), BlackBox.decodeParams(""))
        assertEquals(BlackBox.Params(8, 8, 5, 5), BlackBox.decodeParams("5x5"))
        assertEquals(BlackBox.Params(8, 8, 3, 5), BlackBox.decodeParams("m3"))
        assertEquals(BlackBox.Params(5, 5, 2, 2), BlackBox.decodeParams("xw5yh5m2M2"))
        assertEquals(BlackBox.Params(5, 5, 2, 2), BlackBox.decodeParams("w5h5m2M2zzz"))
        assertEquals(BlackBox.Params(3, 3, 2, 2), BlackBox.decodeParams("w5h5m2M2w3h3"))
        assertEquals(BlackBox.Params(8, 8, 5, 2), BlackBox.decodeParams("W5H5M2M2"))
        assertEquals(BlackBox.Params(5, 5, 2, 2), BlackBox.decodeParams("w 5 h 5 m 2 M 2"))
        assertEquals(BlackBox.Params(5, 5, 2, 2), BlackBox.decodeParams("w+5h+5m+2M+2"))
        // A huge digit run saturates instead of wrapping.
        assertEquals(
            Int.MAX_VALUE,
            (BlackBox.decodeParams("w99999999999999999999h5m1M1") as BlackBox.Params).w,
        )
    }

    @Test
    fun `validate params uses the C order and messages`() {
        fun error(text: String) = BlackBox.validateParams(BlackBox.decodeParams(text), true)

        assertEquals("Width and height must both be at least two", error("w1h5m1M1"))
        assertEquals("Width and height must both be at least two", error("w5h1m1M1"))
        assertEquals(
            "Widths and heights greater than 255 are not supported",
            error("w256h5m1M1"),
        )
        assertEquals(
            "Widths and heights greater than 255 are not supported",
            error("w5h256m1M1"),
        )
        assertEquals("Negative number of balls", error("w5h5m-1M3"))
        assertEquals("Number of balls must be at least one", error("w5h5m0M3"))
        assertEquals(
            "Minimum number of balls may not be greater than maximum",
            error("w5h5m4M3"),
        )
        assertEquals("Too many balls to fit in grid", error("w5h5m25M25"))
        assertNull(error("w5h5m3M3"))
        // `full` is ignored, and C does not reject an impossible maximum.
        assertNull(BlackBox.validateParams(BlackBox.decodeParams("w5h5m3M3"), false))
        assertNull(BlackBox.validateParams(BlackBox.decodeParams("w3h3m1M100"), true))
    }

    @Test
    fun `the description generator matches the frozen preset`() {
        val desc = BlackBox.newDesc(
            BlackBox.Params(5, 5, 3, 3),
            RandomState.fromSeed("12345"),
        )
        assertEquals("7f1b9a74d74b19f6", desc.text)
        assertNull(desc.auxInfo)

        val state = BlackBox.newState(BlackBox.Params(5, 5, 3, 3), desc.text)
            as BlackBox.State
        assertEquals(3, state.nBalls)
        assertEquals(20, state.nLasers)
        assertEquals(
            listOf(3 to 1, 0 to 3, 1 to 3),
            ballCells(state),
        )
    }

    @Test
    fun `a drawn count above the grid is rejected without a hang`() {
        // C accepts this range, seed 12345 draws 76 balls for the nine
        // cells, and C then retries forever for a free cell. The port
        // throws after the count draw.
        val params = BlackBox.decodeParams("w3h3m1M100") as BlackBox.Params
        assertNull(BlackBox.validateParams(params, true))
        val error = assertFailsWith<IllegalArgumentException> {
            BlackBox.newDesc(params, RandomState.fromSeed("12345"))
        }
        assertTrue(error.message!!.contains("cannot place 76 balls"))
        assertTrue(error.message!!.contains("3x3"))

        // A minimum above the grid makes every count too large, so the
        // guard also throws on the first call.
        val tooMany = BlackBox.decodeParams("w2h2m5M9") as BlackBox.Params
        assertEquals(
            "Too many balls to fit in grid",
            BlackBox.validateParams(tooMany, true),
        )
        assertFailsWith<IllegalArgumentException> {
            BlackBox.newDesc(tooMany, RandomState.fromSeed("12345"))
        }
    }

    @Test
    fun `an over-large maximum keeps the C description when the count fits`() {
        // The maximum is above the nine cells, but this seed draws a
        // count of eight, so nine balls fill the grid and C generates a
        // normal description. The text is the oracle's.
        val params = BlackBox.decodeParams("w3h3m1M100") as BlackBox.Params
        val desc = BlackBox.newDesc(params, RandomState.fromSeed("2"))
        assertEquals("c21e76630f56971df11b905abe5f6436b9e7205a", desc.text)

        // A full but feasible grid still generates.
        val full = BlackBox.decodeParams("w3h3m1M9") as BlackBox.Params
        assertNotNull(BlackBox.newDesc(full, RandomState.fromSeed("12345")))
        assertNull(BlackBox.validateDesc(full, BlackBox.newDesc(full, RandomState.fromSeed("1")).text))
    }

    @Test
    fun `validate desc checks the length before any decode`() {
        val params = BlackBox.decodeParams("w5h5m3M3")
        assertEquals("Game description is wrong length", BlackBox.validateDesc(params, ""))
        assertEquals("Game description is wrong length", BlackBox.validateDesc(params, "ab"))
        assertEquals(
            "Game description is wrong length",
            BlackBox.validateDesc(params, "7f1b9a74d74b19f"),
        )
        assertEquals(
            "Game description is wrong length",
            BlackBox.validateDesc(params, "7f1b9a74d74b19f6aa"),
        )
        assertNull(BlackBox.validateDesc(params, "7f1b9a74d74b19f6"))
        // Duplicate balls, uppercase hex, and a non-hex zero nibble are
        // not rejected by the length or size test.
        assertNull(BlackBox.validateDesc(params, "18d7854bc6569634"))
        assertNull(BlackBox.validateDesc(params, "7F1B9A74D74B19F6"))
        assertEquals(
            "Game description is corrupted",
            BlackBox.validateDesc(params, "7f1b9a74d74b19fz"),
        )
        assertEquals(
            "Game description is corrupted",
            BlackBox.validateDesc(
                BlackBox.decodeParams("w3h3m3M3"),
                "17259c5c7686269f",
            ),
        )
    }

    @Test
    fun `a new state has the C fields and duplicate balls collapse`() {
        val params = BlackBox.decodeParams("w5h5m3M3") as BlackBox.Params
        val state = BlackBox.newState(params, "7f1b9a74d74b19f6") as BlackBox.State
        assertEquals(params, state.params)
        assertEquals(5, state.w)
        assertEquals(5, state.h)
        assertEquals(3, state.minBalls)
        assertEquals(3, state.maxBalls)
        assertEquals(20, state.nLasers)
        assertEquals(49, state.grid.size)
        assertTrue(state.exits.all { it == BlackBox.LASER_EMPTY })
        assertEquals(1, state.laserNo)
        assertEquals(0, state.nguesses)
        assertEquals(0, state.nright)
        assertEquals(0, state.nwrong)
        assertEquals(0, state.nmissed)
        assertFalse(state.reveal)
        assertFalse(state.justWrong)
        assertFalse(state.done)

        // Three copies of one ball set the same cell three times. The
        // count stays three and the grid keeps one bit.
        val duplicate = BlackBox.newState(params, "18d7854bc6569634") as BlackBox.State
        assertEquals(3, duplicate.nBalls)
        assertEquals(1, ballCells(duplicate).size)
        assertEquals(0, BlackBox.status(duplicate))

        // Uppercase hex is decoded, and the midend keeps the text.
        val upper = Midend.fromGameId(BlackBox, "w5h5m3M3:7F1B9A74D74B19F6")
        assertEquals("w5h5m3M3:7F1B9A74D74B19F6", upper.id())
        assertEquals(ballCells(state), ballCells(upper.state as BlackBox.State))
    }

    @Test
    fun `copy is deep and equality includes the exits flags`() {
        val params = BlackBox.decodeParams("w5h5m3M3") as BlackBox.Params
        val state = BlackBox.newState(params, "7f1b9a74d74b19f6") as BlackBox.State
        val copy = state.copy() as BlackBox.State
        assertEquals(state, copy)
        assertEquals(state.hashCode(), copy.hashCode())

        copy.grid[0] = 7
        copy.exits[3] = 9
        assertNotEquals(state, copy)
        assertEquals(0, state.grid[0])
        assertEquals(BlackBox.LASER_EMPTY, state.exits[3])

        // The log line never prints the hidden arrays.
        assertFalse(state.toString().contains("grid"))
        assertFalse(state.toString().contains("exits"))
        assertEquals(0, BlackBox.status(state))
    }

    @Test
    fun `the palette and the geometry match the C arithmetic`() {
        val palette = BlackBox.colours()
        assertEquals(13 * 3, palette.size)
        val milli = palette.map { (it * 1000.0f).roundToInt() }
        assertEquals(
            listOf(
                800, 800, 800,
                400, 400, 400,
                560, 560, 560,
                0, 0, 0,
                0, 1000, 0,
                967, 967, 967,
                633, 633, 633,
                720, 720, 720,
                0, 0, 0,
                0, 0, 0,
                1000, 0, 0,
                0, 1000, 0,
                1000, 0, 0,
            ),
            milli,
        )

        assertEquals(
            Size(256, 256),
            BlackBox.computeSize(BlackBox.Params(5, 5, 3, 3), tile),
        )
        assertEquals(
            Size(352, 352),
            BlackBox.computeSize(BlackBox.Params(8, 8, 5, 5), tile),
        )
        assertEquals(
            Size(416, 416),
            BlackBox.computeSize(BlackBox.Params(10, 10, 5, 5), tile),
        )
        assertEquals(
            Size(384, 384),
            BlackBox.computeSize(BlackBox.Params(5, 5, 3, 3), 48),
        )
    }

    @Test
    fun `the UI record is only the attempt counter`() {
        val state = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        assertEquals("E0", BlackBox.encodeUi(BlackBox.newUi(state)))
        assertEquals("E7", BlackBox.encodeUi(BlackBox.Ui(errors = 7)))
        assertEquals("E-2", BlackBox.encodeUi(BlackBox.Ui(errors = -2)))

        val vectors = listOf(
            "E0" to 0,
            "E7" to 7,
            "E-2" to -2,
            "E 3" to 3,
            "E+4" to 4,
            "E007" to 7,
            "E5junk" to 5,
            "E3.5" to 3,
            "X3" to 0,
            "" to 0,
            "E" to 0,
            "e5" to 0,
            "EE3" to 0,
            " E4" to 0,
        )
        for ((text, errors) in vectors) {
            val ui = BlackBox.decodeUi(state, text) as BlackBox.Ui
            assertEquals(errors, ui.errors, "'$text'")
            // Only the counter is restored; the cursor starts hidden.
            assertFalse(ui.curVisible)
            assertEquals(1, ui.curX)
            assertEquals(1, ui.curY)
            assertEquals(BlackBox.LASER_EMPTY, ui.flashLaserno)
            assertFalse(ui.newmove)
            assertEquals(0, ui.flashLaser)
        }
    }

    @Test
    fun `the move grammar is the loose sscanf form`() {
        val base = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")

        for (move in listOf("T1,1", "T 1, 1", "T+1,+1", "T1,1junk", "T1,1,2")) {
            val next = BlackBox.executeMove(base, move) as BlackBox.State
            assertEquals(1, next.nguesses, "'$move'")
            assertTrue(next.gridAt(1, 1) and BlackBox.BALL_GUESS != 0)
            // A second toggle clears it.
            val clear = BlackBox.executeMove(next, move) as BlackBox.State
            assertEquals(0, clear.nguesses)
        }
        for (move in listOf("T1", "T0,1", "T1,0", "T6,1", "t1,1", "X1,1")) {
            assertNull(BlackBox.executeMove(base, move), move)
        }

        for ((move, range) in listOf("F0" to 0, "F 5" to 5, "F19junk" to 19)) {
            val next = BlackBox.executeMove(base, move) as BlackBox.State
            assertNotEquals(BlackBox.LASER_EMPTY, next.exits[range], move)
        }
        for (move in listOf("F-1", "F20", "F", "f0")) {
            assertNull(BlackBox.executeMove(base, move), move)
        }

        assertEquals(0, BlackBox.status(base))
        assertNull(BlackBox.executeMove(base, "R"))
        val three = listOf("T1,4", "T4,2", "T2,4")
            .fold(base) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val revealed = BlackBox.executeMove(three, "R") as BlackBox.State
        assertTrue(revealed.reveal)
        assertEquals(1, BlackBox.status(revealed))
        assertEquals("CORRECT!", BlackBox.statusText(revealed, BlackBox.newUi(revealed)))

        // S works after a reveal; every other move does not.
        val solvedAgain = BlackBox.executeMove(revealed, "S") as BlackBox.State
        assertTrue(solvedAgain.reveal)
        assertNull(BlackBox.executeMove(revealed, "T1,1"))
        assertNull(BlackBox.executeMove(revealed, ""))
        assertNull(BlackBox.executeMove(base, ""))
    }

    @Test
    fun `a toggle on a locked cell still changes the guess`() {
        val base = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val locked = BlackBox.executeMove(base, "LB1,1") as BlackBox.State
        assertTrue(locked.gridAt(1, 1) and BlackBox.BALL_LOCK != 0)
        val guessed = BlackBox.executeMove(locked, "T1,1") as BlackBox.State
        assertEquals(1, guessed.nguesses)
        assertTrue(guessed.gridAt(1, 1) and BlackBox.BALL_LOCK != 0)
        assertTrue(guessed.gridAt(1, 1) and BlackBox.BALL_GUESS != 0)
    }

    @Test
    fun `column and row locks use the greater-than threshold`() {
        // 5x5: h/2 == 2. Two locks set the whole column; three clear it.
        val five = blackBoxState(
            "w5h5m3M3",
            BlackBox.newDesc(
                BlackBox.Params(5, 5, 3, 3),
                RandomState.fromSeed("1"),
            ).text,
        )
        val twoLocked = listOf("LB1,1", "LB1,2")
            .fold(five) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val setAll = BlackBox.executeMove(twoLocked, "LC1") as BlackBox.State
        assertEquals(5, columnLocks(setAll, 1))
        val threeLocked = listOf("LB1,1", "LB1,2", "LB1,3")
            .fold(five) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val cleared = BlackBox.executeMove(threeLocked, "LC1") as BlackBox.State
        assertEquals(0, columnLocks(cleared, 1))

        // 4x4: h/2 == 2 as well, so exactly two locks are still set.
        val four = blackBoxState(
            "w4h4m3M3",
            BlackBox.newDesc(
                BlackBox.Params(4, 4, 3, 3),
                RandomState.fromSeed("1"),
            ).text,
        )
        val fourTwo = listOf("LB1,1", "LB1,2")
            .fold(four) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val fourSet = BlackBox.executeMove(fourTwo, "LC1") as BlackBox.State
        assertEquals(4, columnLocks(fourSet, 1))
        val fourThree = listOf("LB1,1", "LB1,2", "LB1,3")
            .fold(four) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val fourCleared = BlackBox.executeMove(fourThree, "LC1") as BlackBox.State
        assertEquals(0, columnLocks(fourCleared, 1))

        // The row form is symmetric.
        val rowTwo = listOf("LB1,1", "LB2,1")
            .fold(five) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val rowSet = BlackBox.executeMove(rowTwo, "LR1") as BlackBox.State
        assertEquals(5, rowLocks(rowSet, 1))
        val rowThree = listOf("LB1,1", "LB2,1", "LB3,1")
            .fold(five) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val rowCleared = BlackBox.executeMove(rowThree, "LR1") as BlackBox.State
        assertEquals(0, rowLocks(rowCleared, 1))

        assertNull(BlackBox.executeMove(five, "LC"))
        assertNull(BlackBox.executeMove(five, "L B1,1"))
        assertNull(BlackBox.executeMove(five, "LQ1"))
        assertNull(BlackBox.executeMove(five, "LC6"))
        assertNull(BlackBox.executeMove(five, "LR0"))
    }

    @Test
    fun `the cages are advice, so a T is a real move`() {
        val base = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val guessed = listOf("T1,1", "T2,1", "T3,1")
            .fold(base) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val wrong = BlackBox.executeMove(guessed, "R") as BlackBox.State
        assertFalse(wrong.reveal)
        assertTrue(wrong.justWrong)
        assertEquals(0, BlackBox.status(wrong))
        assertEquals(
            "Wrong! Guess again.",
            BlackBox.statusText(wrong, BlackBox.Ui()),
        )

        // A following move clears justwrong and both flag bits but
        // leaves the lasers.
        val next = BlackBox.executeMove(wrong, "T1,2") as BlackBox.State
        assertFalse(next.justWrong)
        assertTrue(
            (0 until next.nLasers).all {
                next.exits[it] == BlackBox.LASER_EMPTY ||
                    next.exits[it] and
                    (BlackBox.LASER_OMITTED or BlackBox.LASER_WRONG) == 0
            },
        )

        // A wrong cagey reveal shows one pair and returns status 0.
        val fixture = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val fired = BlackBox.executeMove(fixture, "F0") as BlackBox.State
        val three = listOf("T1,1", "T2,1", "T3,1")
            .fold(fired) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val cagey = BlackBox.executeMove(three, "R") as BlackBox.State
        assertEquals(BlackBox.LASER_HIT, cagey.exits[0])
        assertEquals(12, cagey.exits[9] and BlackBox.LASER_OMITTED.inv())
        assertEquals(9, cagey.exits[12] and BlackBox.LASER_OMITTED.inv())
        assertEquals(2, cagey.laserNo)
    }

    @Test
    fun `the second solve marks every laser wrong`() {
        val base = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val first = BlackBox.executeMove(base, "S") as BlackBox.State
        assertTrue(first.reveal)
        assertEquals(-1, BlackBox.status(first))
        assertEquals(3, first.nmissed)
        assertTrue(
            (0 until first.nLasers).all {
                first.exits[it] != BlackBox.LASER_EMPTY
            },
        )
        val second = BlackBox.executeMove(first, "S") as BlackBox.State
        assertEquals(-1, BlackBox.status(second))
        assertTrue(
            (0 until second.nLasers).all {
                second.exits[it] and BlackBox.LASER_WRONG != 0
            },
        )
        assertEquals(
            "0 wrong and 3 missed balls.",
            BlackBox.statusText(second, BlackBox.Ui()),
        )
    }

    @Test
    fun `status text has every branch and the attempt suffix`() {
        val base = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        assertEquals(
            "Balls marked: 0 / 3",
            BlackBox.statusText(base, BlackBox.Ui()),
        )
        assertEquals(
            "Balls marked: 0 / 3-6.",
            BlackBox.statusText(
                blackBoxState("w8h8m3M6", "f9086f6b7379c992"),
                BlackBox.Ui(),
            ),
        )
        val three = listOf("T1,1", "T2,1", "T3,1")
            .fold(base) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        assertEquals(
            "Click button to verify guesses.",
            BlackBox.statusText(three, BlackBox.Ui()),
        )
        val four = BlackBox.executeMove(three, "T1,2") as BlackBox.State
        assertEquals(
            "1 too many balls marked.",
            BlackBox.statusText(four, BlackBox.Ui()),
        )
        assertEquals(
            "Balls marked: 0 / 3 (1 incorrect attempt)",
            BlackBox.statusText(base, BlackBox.Ui(errors = 1)),
        )
        assertEquals(
            "Balls marked: 0 / 3 (2 incorrect attempts)",
            BlackBox.statusText(base, BlackBox.Ui(errors = 2)),
        )
        assertEquals(
            "Balls marked: 0 / 3 (-2 incorrect attempts)",
            BlackBox.statusText(base, BlackBox.Ui(errors = -2)),
        )
        val wrong = BlackBox.executeMove(three, "R") as BlackBox.State
        assertEquals(
            "Wrong! Guess again.",
            BlackBox.statusText(wrong, BlackBox.Ui()),
        )
        val correct = BlackBox.executeMove(
            listOf("T1,4", "T4,2", "T2,4")
                .fold(base) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State },
            "R",
        ) as BlackBox.State
        assertEquals("CORRECT!", BlackBox.statusText(correct, BlackBox.Ui()))
        val solvedWrong = BlackBox.executeMove(three, "S") as BlackBox.State
        assertEquals(
            "3 wrong and 3 missed balls.",
            BlackBox.statusText(solvedWrong, BlackBox.Ui()),
        )
    }

    @Test
    fun `changedState counts a wrong reveal only for a real move`() {
        val ui = BlackBox.Ui()
        val base = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val three = listOf("T1,1", "T2,1", "T3,1")
            .fold(base) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State }
        val wrong = BlackBox.executeMove(three, "R") as BlackBox.State

        // A load or a solve has newmove false, so no attempt is counted.
        assertFalse(BlackBox.changedState(ui, three, wrong))
        assertEquals(0, ui.errors)

        ui.newmove = true
        assertFalse(BlackBox.changedState(ui, three, wrong))
        assertEquals(1, ui.errors)
        assertFalse(ui.newmove)

        // A correct reveal reports completion, and a redo of it again.
        val correct = BlackBox.executeMove(
            listOf("T1,4", "T4,2", "T2,4")
                .fold(base) { state, move -> BlackBox.executeMove(state, move) as BlackBox.State },
            "R",
        ) as BlackBox.State
        assertTrue(BlackBox.changedState(ui, three, correct))
        assertFalse(BlackBox.changedState(ui, correct, correct))
        assertFalse(BlackBox.changedState(ui, null, correct))
    }

    @Test
    fun `currentKeyLabel covers the table`() {
        val state = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val ui = BlackBox.Ui(curVisible = true)

        ui.curX = 0
        ui.curY = 0
        assertEquals("Check", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        ui.curX = 1
        ui.curY = 1
        assertEquals("Ball", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Lock", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))

        val guessed = BlackBox.executeMove(state, "T1,1") as BlackBox.State
        assertEquals("Clear", BlackBox.currentKeyLabel(ui, guessed, Buttons.CURSOR_SELECT))
        val locked = BlackBox.executeMove(state, "LB1,1") as BlackBox.State
        assertEquals("", BlackBox.currentKeyLabel(ui, locked, Buttons.CURSOR_SELECT))
        assertEquals("Unlock", BlackBox.currentKeyLabel(ui, locked, Buttons.CURSOR_SELECT2))

        ui.curX = 1
        ui.curY = 0
        assertEquals("Fire", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        assertEquals("Lock", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT2))
        val fired = BlackBox.executeMove(state, "F0") as BlackBox.State
        assertEquals("", BlackBox.currentKeyLabel(ui, fired, Buttons.CURSOR_SELECT))

        val manyLocks = listOf("LB1,1", "LB1,2", "LB1,3")
            .fold(state) { current, move -> BlackBox.executeMove(current, move) as BlackBox.State }
        assertEquals(
            "Unlock",
            BlackBox.currentKeyLabel(ui, manyLocks, Buttons.CURSOR_SELECT2),
        )

        // A hidden cursor and a revealed state have no labels.
        ui.curVisible = false
        assertEquals("", BlackBox.currentKeyLabel(ui, state, Buttons.CURSOR_SELECT))
        ui.curVisible = true
        val revealed = BlackBox.executeMove(state, "S") as BlackBox.State
        assertEquals("", BlackBox.currentKeyLabel(ui, revealed, Buttons.CURSOR_SELECT))
        assertEquals("", BlackBox.currentKeyLabel(ui, revealed, Buttons.CURSOR_SELECT2))
        assertEquals("", BlackBox.currentKeyLabel(ui, state, 'x'.code))
    }

    @Test
    fun `the cursor location is a whole visible tile`() {
        val state = blackBoxState("w5h5m3M3", "7f1b9a74d74b19f6")
        val ui = BlackBox.Ui()
        assertNull(BlackBox.cursorLocation(ui, state, tile))
        ui.curVisible = true
        assertEquals(
            Game.CursorRect(48, 48, 32, 32),
            BlackBox.cursorLocation(ui, state, tile),
        )
        ui.curX = 0
        ui.curY = 0
        assertEquals(
            Game.CursorRect(16, 16, 32, 32),
            BlackBox.cursorLocation(ui, state, tile),
        )
    }

    @Test
    fun `requestKeys is empty with left-right arrows`() {
        val request = BlackBox.requestKeys(BlackBox.Params(5, 5, 3, 3))
        assertTrue(request.labels.isEmpty())
        assertEquals(ArrowMode.ARROWS_LEFT_RIGHT, request.arrowMode)
    }

    @Test
    fun `every laser exits or reflects on every small layout`() {
        // The property that keeps the step loop finite: no cycle and no
        // ray into a ball. All layouts of 3x3 with up to four balls and
        // of 4x4 with up to five balls.
        for ((w, h, maxBalls) in listOf(Triple(3, 3, 4), Triple(4, 4, 5))) {
            val cells = w * h
            for (count in 1..maxBalls) {
                val combination = IntArray(count)
                fun walk(start: Int, depth: Int) {
                    if (depth == count) {
                        val balls = combination.map { it % w to it / w }
                        val state = syntheticState(w, h, balls, count)
                        for (entry in 0 until state.nLasers) {
                            val exit = BlackBox.laserExit(state, entry)
                            assertTrue(
                                exit == BlackBox.LASER_HIT ||
                                    exit == BlackBox.LASER_REFLECT ||
                                    exit in 0 until state.nLasers,
                                "laser $entry on ${w}x$h balls $balls",
                            )
                        }
                        return
                    }
                    for (next in start until cells) {
                        combination[depth] = next
                        walk(next + 1, depth + 1)
                    }
                }
                walk(0, 0)
            }
        }
    }

    private fun blackBoxState(paramsText: String, desc: String): BlackBox.State =
        BlackBox.newState(BlackBox.decodeParams(paramsText), desc) as BlackBox.State

    private fun syntheticState(
        w: Int,
        h: Int,
        balls: List<Pair<Int, Int>>,
        nballs: Int,
    ): BlackBox.State {
        val grid = IntArray((w + 2) * (h + 2))
        for ((x, y) in balls) {
            grid[(y + 1) * (w + 2) + (x + 1)] = BlackBox.BALL_CORRECT
        }
        val nlasers = 2 * (w + h)
        return BlackBox.State(
            params = BlackBox.Params(w, h, 1, nballs),
            w = w,
            h = h,
            minBalls = 1,
            maxBalls = nballs,
            nBalls = nballs,
            nLasers = nlasers,
            grid = grid,
            exits = IntArray(nlasers) { BlackBox.LASER_EMPTY },
            done = false,
            laserNo = 1,
            nguesses = 0,
            nright = 0,
            nwrong = 0,
            nmissed = 0,
            reveal = false,
            justWrong = false,
        )
    }

    private fun ballCells(state: BlackBox.State): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (y in 0 until state.h) {
            for (x in 0 until state.w) {
                if (state.gridAt(x + 1, y + 1) and BlackBox.BALL_CORRECT != 0) {
                    cells += x to y
                }
            }
        }
        return cells
    }

    private fun columnLocks(state: BlackBox.State, x: Int): Int =
        (1..state.h).count { state.gridAt(x, it) and BlackBox.BALL_LOCK != 0 }

    private fun rowLocks(state: BlackBox.State, y: Int): Int =
        (1..state.w).count { state.gridAt(it, y) and BlackBox.BALL_LOCK != 0 }
}
