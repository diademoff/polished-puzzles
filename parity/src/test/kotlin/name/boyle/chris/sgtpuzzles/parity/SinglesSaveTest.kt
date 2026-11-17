package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import name.boyle.chris.sgtpuzzles.engine.Singles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Level: strict for the text and semantic for the rest. Game: singles.
 *
 * Saves in both directions, the history, the `SOLVE` and `RESTART`
 * records, the empty and `S` moves, the parameter split, and the exact
 * record order. Singles writes no `AUXINFO`, `PRIVDESC`, `UI`, or
 * `TIME`.
 */
class SinglesSaveTest {

    private fun handSave(
        desc: String,
        params: String = "2x2de",
        seedRecord: String? = "SEED",
        history: List<Pair<String, String>> = emptyList(),
    ): String {
        val records = ArrayList<Pair<String, String>>()
        records += "SAVEFILE" to "Simon Tatham's Portable Puzzle Collection"
        records += "VERSION" to "1"
        records += "GAME" to "Singles"
        records += "PARAMS" to params
        records += "CPARAMS" to params
        if (seedRecord == "SEED") records += "SEED" to "1"
        if (seedRecord == "EMPTY") records += "SEED" to ""
        if (seedRecord == "HEX") records += "HEXSEED" to "00ff"
        records += "DESC" to desc
        records += "NSTATES" to (history.size + 1).toString()
        records += "STATEPOS" to (history.size + 1).toString()
        records += history
        return records.joinToString("") { (key, value) ->
            "${key.padEnd(8)}:${value.length}:$value\n"
        }
    }

    private fun assertSame(what: String, expected: GameState, midend: Midend) {
        assertEquals("$what save", expected.state, midend.serialise().serialise())
        assertEquals("$what id", expected.id, midend.id())
        assertEquals("$what status", expected.status, midend.status())
        assertEquals("$what undo", expected.canUndo, midend.canUndo)
        assertEquals("$what redo", expected.canRedo, midend.canRedo)
    }

    @Test(timeout = 600_000)
    fun `every preset save round-trips in both directions`() = OracleClient().use { oracle ->
        for (params in oracle.presets("singles").map { it.params } + "") {
            for (seed in listOf("1", "12345", "another seed")) {
                val local = Midend.newGame(Singles, params, seed)
                val localSave = local.serialise()
                val cLoaded = oracle.id(localSave.serialise())
                assertEquals("'$params' seed '$seed' id in C", local.id(), cLoaded.id)
                assertEquals("'$params' seed '$seed' status in C", local.status(), cLoaded.status)
                assertEquals(
                    "'$params' seed '$seed' text in C",
                    local.textFormat(),
                    oracle.text(localSave.serialise()),
                )

                val cSave = oracle.newGame("singles", params, seed)
                val kotlinLoaded = Midend.fromSave(Singles, SgtpSave.parse(cSave.state))
                assertSame("'$params' seed '$seed' from C", cSave, kotlinLoaded)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `a played, solved, and restarted history round-trips`() =
        OracleClient().use { oracle ->
            val midend = Midend.newGame(Singles, "5x5de", SEED)
            val points = listOf(
                OracleClient.Point(16 + 32, 16 + 32, Buttons.LEFT_BUTTON),
                OracleClient.Point(16 + 32, 16 + 32, Buttons.LEFT_RELEASE),
                OracleClient.Point(16 + 2 * 32, 16 + 2 * 32, Buttons.RIGHT_BUTTON),
                OracleClient.Point(16 + 2 * 32, 16 + 2 * 32, Buttons.RIGHT_RELEASE),
            )
            for (point in points) midend.processKey(point.x, point.y, point.button, 32)
            assertEquals(3, midend.stateCount)
            val played = midend.serialise()
            assertEquals(
                listOf("MOVE", "MOVE"),
                played.records.filter { it.key == "MOVE" }.map { it.key },
            )
            val cLoaded = oracle.id(played.serialise())
            assertEquals(midend.id(), cLoaded.id)
            assertSame("played in C", cLoaded, Midend.fromSave(Singles, played))

            assertTrue(midend.solve())
            val solved = midend.serialise()
            assertEquals("SOLVE", solved.records.last().key)
            val cSolved = oracle.id(solved.serialise())
            assertEquals(midend.id(), cSolved.id)
            assertEquals(1, cSolved.status)
            assertEquals(
                solved.value("SOLVE"),
                SgtpSave.parse(cSolved.state).value("SOLVE"),
            )

            val solvedKotlin = Midend.fromSave(Singles, SgtpSave.parse(cSolved.state))
            assertSame("solve in C", cSolved, solvedKotlin)
            assertTrue((solvedKotlin.state as Singles.State).usedSolve)

            assertTrue(midend.restart())
            val restarted = midend.serialise()
            assertEquals("RESTART", restarted.records.last().key)
            assertEquals(restarted.value("DESC"), restarted.records.last().value)
            val cRestarted = oracle.id(restarted.serialise())
            assertEquals(midend.id(), cRestarted.id)
            assertEquals(midend.textFormat(), oracle.text(restarted.serialise()))
        }

    @Test(timeout = 300_000)
    fun `the empty move and the S move round-trip`() = OracleClient().use { oracle ->
        val empty = handSave("1221", history = listOf("MOVE" to ""))
        val emptyExpected = oracle.status(empty)
        val emptyLocal = Midend.fromSave(Singles, SgtpSave.parse(empty))
        assertSame("empty move", emptyExpected, emptyLocal)
        assertEquals(1, emptyLocal.status())
        assertTrue(emptyLocal.canUndo)
        assertEquals("", emptyLocal.serialise().values("MOVE").last())

        val sMove = handSave("2111", history = listOf("MOVE" to "S"))
        val sExpected = oracle.status(sMove)
        val sLocal = Midend.fromSave(Singles, SgtpSave.parse(sMove))
        assertSame("S move", sExpected, sLocal)
        assertTrue("S sets used_solve", (sLocal.state as Singles.State).usedSolve)
        assertEquals("S", sLocal.serialise().values("MOVE").last())

        // A save made from the S state still loads in C.
        assertEquals(sLocal.id(), oracle.id(sLocal.serialise().serialise()).id)

        val combined = handSave("2111", history = listOf("MOVE" to "S;B0,0"))
        val combinedExpected = oracle.status(combined)
        val combinedLocal = Midend.fromSave(Singles, SgtpSave.parse(combined))
        assertSame("S with a cell", combinedExpected, combinedLocal)
        assertTrue((combinedLocal.state as Singles.State).usedSolve)
        assertTrue((combinedLocal.state as Singles.State).flags[0] and Singles.F_BLACK != 0)
    }

    @Test(timeout = 300_000)
    fun `the parameter split and the record order are the C ones`() =
        OracleClient().use { oracle ->
            val local = Midend.newGame(Singles, "2x2dk", SEED)
            val save = local.serialise()
            assertEquals("2x2de", save.value("PARAMS"))
            assertEquals("2x2dk", save.value("CPARAMS"))
            assertEquals("2x2", local.id().substringBefore(':'))
            assertEquals(
                listOf(
                    "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                    "SEED", "DESC", "NSTATES", "STATEPOS",
                ),
                save.records.map { it.key },
            )
            for (key in listOf("AUXINFO", "PRIVDESC", "UI", "TIME")) {
                assertEquals("no $key", null, save.value(key))
            }
            val expected = oracle.newGame("singles", "2x2dk", SEED)
            assertEquals(expected.state, save.serialise())
            assertEquals(expected.id, local.id())

            // A solved and a restarted save insert one record, and no
            // game record beyond them.
            val solved = Midend.newGame(Singles, "2x2de", SEED)
            assertTrue(solved.solve())
            val solvedSave = solved.serialise()
            assertEquals("SOLVE", solvedSave.records.last().key)
            assertEquals(
                listOf(
                    "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                    "SEED", "DESC", "NSTATES", "STATEPOS", "SOLVE",
                ),
                solvedSave.records.map { it.key },
            )
            for (key in listOf("AUXINFO", "PRIVDESC", "UI", "TIME")) {
                assertEquals("no $key in a solved save", null, solvedSave.value(key))
            }

            val restarted = Midend.newGame(Singles, "2x2de", SEED)
            assertTrue(restarted.solve())
            assertTrue(restarted.restart())
            assertEquals("RESTART", restarted.serialise().records.last().key)
            assertEquals("SOLVE", restarted.serialise().records[9].key)
        }

    @Test(timeout = 300_000)
    fun `the seed records and the AUXINFO absence match the container`() =
        OracleClient().use { oracle ->
            val emptySeed = handSave("2111", seedRecord = "EMPTY")
            val expected = oracle.status(emptySeed)
            val local = Midend.fromSave(Singles, SgtpSave.parse(emptySeed))
            assertSame("empty seed", expected, local)
            assertEquals("", local.serialise().value("SEED"))

            // A written save keeps an empty seed as a zero-length SEED
            // record, not a HEXSEED.
            val resaved = local.serialise()
            assertEquals("SEED", resaved.records.first { it.key == "SEED" }.key)
            assertEquals("", resaved.value("SEED"))

            val hexSeed = handSave("2111", seedRecord = "HEX")
            val hexExpected = oracle.status(hexSeed)
            val hexLocal = Midend.fromSave(Singles, SgtpSave.parse(hexSeed))
            assertEquals("hex seed id", hexExpected.id, hexLocal.id())
            assertEquals("hex seed status", hexExpected.status, hexLocal.status())
            assertEquals("hex seed text", oracle.text(hexSeed), hexLocal.textFormat())
            // The seed decodes to a NUL and a byte. C writes an empty
            // SEED record because its seed is a C string; the container
            // keeps the two bytes and writes HEXSEED. That difference is
            // in the shared save container, not in this game.
            assertEquals("HEXSEED", hexLocal.serialise().records[5].key)
            assertEquals("00ff", hexLocal.serialise().value("HEXSEED"))

            assertFalse(hexLocal.serialise().records.any { it.key == "AUXINFO" })
            assertEquals(null, hexLocal.auxInfo)
        }

    private companion object {
        const val SEED = "12345"
    }
}
