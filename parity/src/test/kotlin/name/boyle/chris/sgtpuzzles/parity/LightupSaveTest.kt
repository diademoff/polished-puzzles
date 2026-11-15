package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Lightup
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.SaveRecord
import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: strict for the save text, semantic for the state. Game: lightup.
 *
 * Every `lightup` save in the corpus is read by the Kotlin midend,
 * rewritten byte for byte, and reloaded by C. The other direction takes a
 * Kotlin save for every preset and lets C read it.
 *
 * The full form is `WxHb<blackpc>s<symm>d<diff>`, so `PARAMS` drops the
 * short-term black percentage, symmetry, and difficulty while `CPARAMS`
 * keeps them. There is no `AUXINFO` and no `UI` record.
 */
class LightupSaveTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `every frozen save round-trips through the Kotlin midend`() =
        OracleClient().use { oracle ->
            val files = File(testdata, "saves").walkTopDown()
                .filter { it.isFile && it.extension == "sgtp" }
                .filter { SgtpSave.parse(it.readText()).value("GAME") == Lightup.name }
                .sorted()
                .toList()
            assertEquals("ten lightup saves", 10, files.size)

            for (file in files) {
                val text = file.readText()
                val midend = Midend.fromSave(Lightup, SgtpSave.parse(text))
                assertEquals("${file.name} canonical text", text, midend.serialise().serialise())

                val expected = oracle.id(text)
                assertEquals("${file.name} id", expected.id, midend.id())
                assertEquals("${file.name} status", expected.status, midend.status())
                assertEquals("${file.name} can undo", expected.canUndo, midend.canUndo)
                assertEquals("${file.name} can redo", expected.canRedo, midend.canRedo)
                assertEquals("${file.name} text", oracle.text(text), midend.textFormat())

                val save = SgtpSave.parse(text)
                assertNull("${file.name} AUXINFO", save.value("AUXINFO"))
                assertNull("${file.name} UI", save.value("UI"))
                assertEquals(
                    "${file.name} record order",
                    listOf(
                        "SAVEFILE", "VERSION", "GAME", "PARAMS", "CPARAMS",
                        "SEED", "DESC", "NSTATES", "STATEPOS",
                    ),
                    save.records.map { it.key },
                )

                // C reloads the Kotlin text with the same id.
                assertEquals(
                    "${file.name} C reload",
                    expected.id,
                    oracle.id(midend.serialise().serialise()).id,
                )
            }

            // `default.sgtp` and the first preset are byte-identical.
            assertEquals(
                File(testdata, "saves/initial/lightup/default.sgtp").readText(),
                File(testdata, "saves/initial/lightup/7x7b20s3d0.sgtp").readText(),
            )
        }

    @Test
    fun `the params and cparams split is pinned`() {
        val tricky = SgtpSave.parse(
            File(testdata, "saves/initial/lightup/7x7b20s3d1.sgtp").readText(),
        )
        assertEquals("7x7b20s3d0", tricky.value("PARAMS"))
        assertEquals("7x7b20s3d1", tricky.value("CPARAMS"))
        assertEquals(
            "7x7:bBB0bBeB2eBgBeBBeBb1B1b",
            Midend.fromSave(Lightup, tricky).id(),
        )

        val tenTricky = SgtpSave.parse(
            File(testdata, "saves/initial/lightup/10x10b20s2d1.sgtp").readText(),
        )
        assertEquals("10x10b20s3d0", tenTricky.value("PARAMS"))
        assertEquals("10x10b20s2d1", tenTricky.value("CPARAMS"))
        assertEquals(
            "10x10:a1Bi1a1b3a1h3a0eBf1l0f0eBa1h1a2b0aBi01a",
            Midend.fromSave(Lightup, tenTricky).id(),
        )

        // Every preset except the default has the split, and the id has
        // no `b`, `s`, or `d`.
        for (preset in Lightup.presets()) {
            val midend = Midend.newGame(Lightup, preset.params, SEED)
            val save = SgtpSave.parse(midend.serialise().serialise())
            assertEquals(
                "${preset.params} PARAMS",
                "7x7b20s3d0".replace("7x7", preset.params.substringBefore('b')),
                save.value("PARAMS"),
            )
            assertEquals("${preset.params} CPARAMS", preset.params, save.value("CPARAMS"))
            assertTrue(
                "${preset.params} id",
                midend.id().substringBefore(':').matches(Regex("\\d+x\\d+")),
            )
        }
    }

    @Test
    fun `a Kotlin save for every preset loads in the oracle`() =
        OracleClient().use { oracle ->
            for (preset in Lightup.presets()) {
                val midend = Midend.newGame(Lightup, preset.params, SEED)
                val text = midend.serialise().serialise()
                val loaded = oracle.id(text)

                assertEquals("${preset.params} id", midend.id(), loaded.id)
                assertEquals("${preset.params} canonical text", text, loaded.state)
                assertEquals("${preset.params} status", midend.status(), loaded.status)
                assertEquals("${preset.params} text", midend.textFormat(), oracle.text(text))
                assertFalse("${preset.params} starts with no undo", loaded.canUndo)
                assertFalse("${preset.params} starts with no redo", loaded.canRedo)
                assertNull("${preset.params} AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
                assertNull("${preset.params} UI", SgtpSave.parse(text).value("UI"))
                assertEquals(
                    "${preset.params} CPARAMS is the full form",
                    preset.params,
                    SgtpSave.parse(text).value("CPARAMS"),
                )
            }
        }

    @Test
    fun `history records round-trip in both directions`() = OracleClient().use { oracle ->
        val solve = oracle.newGame("lightup", "7x7b20s3d0", SEED).let { start ->
            SgtpSave.parse(oracle.solve(start.state).state).value("SOLVE")!!
        }

        val cases = listOf(
            History("played light", listOf("MOVE" to "L1,0"), 2, 0),
            History("impossible mark", listOf("MOVE" to "I1,0"), 2, 0),
            History(
                "undone position with a live redo branch",
                listOf("MOVE" to "L1,0", "MOVE" to "I2,0"),
                2,
                0,
                canRedo = true,
            ),
            History("solve with a long L and I list", listOf("SOLVE" to solve), 2, 1),
            History(
                "restart",
                listOf("MOVE" to "L1,0", "RESTART" to DESC_7X7),
                3,
                0,
            ),
        )

        for (case in cases) {
            val text = historySave(case)
            val midend = Midend.fromSave(Lightup, SgtpSave.parse(text))

            assertEquals("${case.label} canonical text", text, midend.serialise().serialise())
            assertEquals("${case.label} state count", case.records.size + 1, midend.stateCount)
            assertEquals("${case.label} state pos", case.statePos, midend.statePos)

            val expected = oracle.id(text)
            assertEquals("${case.label} id", expected.id, midend.id())
            assertEquals("${case.label} status", case.status, midend.status())
            assertEquals("${case.label} oracle status", expected.status, midend.status())
            assertEquals("${case.label} can undo", expected.canUndo, midend.canUndo)
            assertEquals("${case.label} can redo", expected.canRedo, midend.canRedo)
            assertEquals("${case.label} text", oracle.text(text), midend.textFormat())
            assertEquals(
                "${case.label} C reloads the Kotlin text",
                expected.id,
                oracle.id(midend.serialise().serialise()).id,
            )
            assertNull("${case.label} AUXINFO", SgtpSave.parse(text).value("AUXINFO"))
            assertNull("${case.label} UI", SgtpSave.parse(text).value("UI"))
        }

        // The solve record has both L and I entries on a Hard board.
        val hard = Midend.newGame(Lightup, "7x7b20s3d2", SEED)
        assertTrue(hard.solve())
        val solveRecord = SgtpSave.parse(hard.serialise().serialise()).value("SOLVE")!!
        assertTrue("L entries", solveRecord.contains(";L"))
        assertTrue("I entries", solveRecord.contains(";I"))
        assertEquals(
            "the solve record reloads in C",
            hard.id(),
            oracle.id(hard.serialise().serialise()).id,
        )
    }

    private data class History(
        val label: String,
        val records: List<Pair<String, String>>,
        val statePos: Int,
        val status: Int,
        val canRedo: Boolean = false,
    )

    private fun historySave(case: History): String {
        val all = arrayListOf(
            SaveRecord("SAVEFILE", SgtpSave.MAGIC),
            SaveRecord("VERSION", SgtpSave.VERSION),
            SaveRecord("GAME", Lightup.name),
            SaveRecord("PARAMS", "7x7b20s3d0"),
            SaveRecord("CPARAMS", "7x7b20s3d0"),
            SaveRecord("SEED", SEED),
            SaveRecord("DESC", DESC_7X7),
        )
        all += SaveRecord("NSTATES", (1 + case.records.size).toString())
        all += SaveRecord("STATEPOS", case.statePos.toString())
        for ((key, value) in case.records) all += SaveRecord(key, value)
        return SgtpSave.of(all).serialise()
    }

    private companion object {
        const val SEED = "12345"
        const val DESC_7X7 = "Be1j4e3aBeBj1e1"
    }
}
