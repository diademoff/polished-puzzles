package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: semantic. Game: cube.
 *
 * Parameters, presets, the ignored `full` argument, the `atoi` decoder,
 * the C validation messages and their order, `params + seed -> DESC`
 * for every preset and extra parameter vector, the frozen descriptions,
 * and the per-class blue counts.
 *
 * `validateParams` is not called on a huge accepted area: its
 * class-count step enumerates every grid square, so only the rejected
 * side of the area check is fast and safe.
 */
class CubeDescTest {

    private val testdata = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    )

    @Test
    fun `the default and the four presets match the oracle`() =
        OracleClient().use { oracle ->
            val expected = oracle.presets("cube")
            val actual = Cube.presets()
            assertEquals(4, expected.size)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals("preset $i title", expected[i].title, actual[i].title)
                assertEquals("preset $i params", expected[i].params, actual[i].params)
            }
            assertEquals(
                listOf(
                    "Cube" to "c4x4",
                    "Tetrahedron" to "t1x2",
                    "Octahedron" to "o2x2",
                    "Icosahedron" to "i3x3",
                ),
                actual.map { it.title to it.params },
            )

            // The seam decodes the empty string as C does, but a new
            // game treats it as "use the defaults".
            assertEquals(
                Cube.Params(Cube.CUBE, 0, 0),
                Cube.decodeParams(""),
            )
            val defaults = oracle.newGame("cube", "", SEED)
            val midend = Midend.newGame(Cube, "", SEED)
            assertEquals(defaults.id, midend.id())
            assertEquals("c4x4", Cube.encodeParams(midend.params, true))
            assertEquals("c4x4", Cube.encodeParams(midend.params, false))
        }

    @Test
    fun `encode params ignores full for every preset and extra vector`() {
        val vectors = Cube.presets().map { it.params } +
            listOf("c4x5", "c2x4", "t2x2", "t0x3", "o2x3", "o1x3", "i2x2", "i4x1")
        for (text in vectors) {
            val params = Cube.decodeParams(text)
            assertEquals(text, Cube.encodeParams(params, true))
            assertEquals(text, Cube.encodeParams(params, false))
        }
    }

    @Test
    fun `decode quirks match the oracle`() = OracleClient().use { oracle ->
        val accepted = listOf(
            "",
            "4x4",
            "c4x4",
            "c4",
            "04x04",
            "c4x4x5",
            "c 4x4",
            "c+4x4",
            "t1x2",
            "o2x2",
            "i3x3",
        )
        for (text in accepted) {
            assertEquals(
                "id for '$text'",
                oracle.newGame("cube", text, SEED).id,
                Midend.newGame(Cube, text, SEED).id(),
            )
        }

        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("4x4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("04x04"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c4x4x5"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c 4x4"))
        assertEquals(Cube.Params(Cube.CUBE, 4, 4), Cube.decodeParams("c+4x4"))
        assertEquals(Cube.Params(Cube.TETRAHEDRON, 0, 0), Cube.decodeParams("tc4x4"))
        assertEquals(Cube.Params(Cube.CUBE, -3, -3), Cube.decodeParams("c-3"))
        assertEquals(Cube.Params(Cube.CUBE, 4, -2), Cube.decodeParams("c4x-2"))
        assertEquals(Cube.Params(Cube.CUBE, 0, 0), Cube.decodeParams("C4x4"))

        val rejected = listOf("c4x", "C4x4", "c-3", "c4x-2", "tc4x4")
        for (text in rejected) {
            val expected = try {
                oracle.newGame("cube", text, SEED)
                null
            } catch (e: OracleException) {
                e.message
            }
            assertEquals(
                "message for '$text'",
                expected,
                Cube.validateParams(Cube.decodeParams(text), true),
            )
        }
    }

    @Test
    fun `the atoi overflow wraps through a long like the oracle`() =
        OracleClient().use { oracle ->
            // The C library parses with `strtol` and casts to `int`, so
            // the low 32 bits survive. `atoi` must not clamp.
            val accepted = listOf("c4294967300x4", "c4x4294967300")
            for (text in accepted) {
                assertEquals(
                    "params for '$text'",
                    Cube.Params(Cube.CUBE, 4, 4),
                    Cube.decodeParams(text),
                )
                assertEquals(
                    "id for '$text'",
                    oracle.newGame("cube", text, SEED).id,
                    Midend.newGame(Cube, text, SEED).id(),
                )
            }

            val cases = listOf(
                "c2147483648x4" to "Grid dimensions may not be negative",
                "c4x2147483648" to "Grid dimensions may not be negative",
                "c99999999999999999999999999x4" to
                    "Grid dimensions may not be negative",
                "c-4294967300x4" to "Grid dimensions may not be negative",
                "c-2147483649x4" to "Grid area must not be unreasonably large",
                "c9223372036854775808x4" to
                    "Grid dimensions may not be negative",
                "c-99999999999999999999999999x4" to
                    "Both grid dimensions must be greater than one",
            )
            for ((text, message) in cases) {
                assertEquals(
                    "Kotlin message for '$text'",
                    message,
                    Cube.validateParams(Cube.decodeParams(text), true),
                )
                val oracleMessage = try {
                    oracle.newGame("cube", text, SEED)
                    null
                } catch (e: OracleException) {
                    e.message
                }
                assertEquals("oracle message for '$text'", message, oracleMessage)
            }
        }

    @Test
    fun `validation messages and their order match the oracle`() =
        OracleClient().use { oracle ->
            val cases = listOf(
                "c2x2" to "Not enough grid space to place all blue faces",
                "c2x3" to "Not enough space to place the solid on an empty square",
                "c3x1" to "Both grid dimensions must be greater than one",
                "c1x1" to "Both grid dimensions must be greater than one",
                "t0x2" to "Not enough space to place the solid on an empty square",
                "t1x1" to "Not enough grid space to place all blue faces",
                "t0x0" to "At least one grid dimension must be greater than zero",
                "o1x1" to "Not enough grid space to place all blue faces",
                "i1x1" to "Not enough grid space to place all blue faces",
                "i2x1" to "Not enough grid space to place all blue faces",
                "i1x2" to "Not enough grid space to place all blue faces",
                "x4x4" to "Both grid dimensions must be greater than one",
                "C4x4" to "Both grid dimensions must be greater than one",
                "c-3" to "Grid dimensions may not be negative",
                "c4x-2" to "Grid dimensions may not be negative",
                // Rejected before the enumeration, so fast.
                "c46341x46341" to "Grid area must not be unreasonably large",
                "t46341x1" to "Grid area must not be unreasonably large",
                "t1x46341" to "Grid area must not be unreasonably large",
                "o46341x46341" to "Grid area must not be unreasonably large",
            )
            for ((text, message) in cases) {
                assertEquals(
                    "Kotlin message for '$text'",
                    message,
                    Cube.validateParams(Cube.decodeParams(text), true),
                )
                val oracleMessage = try {
                    oracle.newGame("cube", text, SEED)
                    null
                } catch (e: OracleException) {
                    e.message
                }
                assertEquals("oracle message for '$text'", message, oracleMessage)
            }
        }

    @Test
    fun `accepted non-preset parameters match the oracle`() =
        OracleClient().use { oracle ->
            val accepted = listOf(
                "t2x2", "t2x1", "t1x3", "t0x3", "t3x0",
                "o2x3", "o3x1", "o1x3", "i2x2", "i4x1",
                "c4x5", "c5x4", "c2x4", "c4x2",
            )
            for (text in accepted) {
                assertNull("'$text' must be valid", Cube.validateParams(Cube.decodeParams(text), true))
                assertEquals(
                    "id for '$text'",
                    oracle.newGame("cube", text, SEED).id,
                    Midend.newGame(Cube, text, SEED).id(),
                )
            }
        }

    @Test
    fun `the frozen descriptions match the goldens and the oracle`() =
        OracleClient().use { oracle ->
            val golden = Json.parse(
                File(testdata, "golden/cube/desc.json").readText(),
            ) as JsonValue.Obj
            assertEquals("cube", golden.string("game"))
            val seed = golden.string("seed")
            val entries = (golden.field("entries") as JsonValue.Arr).items
            assertEquals("golden entry count", 5, entries.size)

            for (item in entries) {
                val entry = item as JsonValue.Obj
                val params = entry.string("params")
                val id = entry.string("id")
                assertEquals("golden '$params'", id, Midend.newGame(Cube, params, seed).id())
                assertEquals(
                    "oracle '$params'",
                    oracle.newGame("cube", params, seed).id,
                    Midend.newGame(Cube, params, seed).id(),
                )
            }

            assertEquals("c4x4:205E,4", Midend.newGame(Cube, "c4x4", seed).id())
            assertEquals("t1x2:9108,8", Midend.newGame(Cube, "t1x2", seed).id())
            assertEquals("o2x2:13018B,14", Midend.newGame(Cube, "o2x2", seed).id())
            assertEquals("i3x3:981F3821227120,23", Midend.newGame(Cube, "i3x3", seed).id())
        }

    @Test
    fun `every preset and seed matches the oracle`() = OracleClient().use { oracle ->
        val paramsList = Cube.presets().map { it.params }
        val seeds = listOf("1", "2", "3", "12345", "seed", "another seed", "0", "-7", "")
        for (params in paramsList) {
            for (seed in seeds) {
                assertEquals(
                    "seed '$seed' for '$params'",
                    oracle.newGame("cube", params, seed).id,
                    Midend.newGame(Cube, params, seed).id(),
                )
            }
        }
    }

    @Test
    fun `a numeric seed sweep and the extra vectors match the oracle`() =
        OracleClient().use { oracle ->
            val paramsList = Cube.presets().map { it.params } +
                listOf(
                    "t2x2", "t2x1", "t1x3", "t0x3", "t3x0",
                    "o2x3", "o3x1", "o1x3", "i2x2", "i4x1",
                    "c4x5", "c5x4", "c2x4", "c4x2",
                )
            for (params in paramsList) {
                for (seed in 0..19) {
                    assertEquals(
                        "'$params' seed=$seed",
                        oracle.newGame("cube", params, seed.toString()).id,
                        Midend.newGame(Cube, params, seed.toString()).id(),
                    )
                }
            }

            // The parameter vectors quoted by the specification, seed 7.
            val expected = mapOf(
                "t2x2" to "t2x2:031400,18",
                "t2x1" to "t2x1:4910,3",
                "t1x3" to "t1x3:00D800,16",
                "t0x3" to "t0x3:4C8,3",
                "t3x0" to "t3x0:468,4",
                "o2x3" to "o2x3:001425A020,14",
                "o3x1" to "o3x1:078650,2",
                "o1x3" to "o1x3:0CA710,2",
                "i2x2" to "i2x2:BBEFF7,20",
                "i4x1" to "i4x1:22EFFBAA0,11",
                "c4x5" to "c4x5:07284,3",
                "c5x4" to "c5x4:07284,3",
                "c2x4" to "c2x4:F9,5",
                "c4x2" to "c4x2:F9,5",
            )
            for ((params, id) in expected) {
                assertEquals("id '$params'", id, Midend.newGame(Cube, params, "7").id())
            }
        }

    @Test
    fun `the generator places one face per class on a non-blue square`() =
        OracleClient().use { oracle ->
            val paramsList = Cube.presets().map { it.params } +
                listOf("t2x2", "o2x3", "i2x2", "c5x4")
            for (text in paramsList) {
                val midend = Midend.newGame(Cube, text, SEED)
                val params = Cube.decodeParams(text) as Cube.Params
                val state = Cube.newState(params, midend.desc) as Cube.State
                val nfaces = when (params.solid) {
                    Cube.TETRAHEDRON -> 4
                    Cube.CUBE -> 6
                    Cube.OCTAHEDRON -> 8
                    else -> 20
                }
                assertEquals("'$text' blue count", nfaces, state.bluemask.count { it })
                assertTrue("'$text' starts on a non-blue square", !state.bluemask[state.current])
                val area = state.bluemask.size
                val hex = midend.desc.substringBefore(',')
                assertEquals("'$text' hex length", (area + 3) / 4, hex.length)
                assertEquals(
                    "'$text' oracle id",
                    oracle.newGame("cube", text, SEED).id,
                    midend.id(),
                )
            }
        }

    @Test
    fun `validate desc uses the exact C strings`() {
        val cube = Cube.decodeParams("c4x4")
        assertNull(Cube.validateDesc(cube, "205E,4"))
        assertNull(Cube.validateDesc(cube, "ffff,0"))
        assertNull(Cube.validateDesc(cube, "FFFF,0"))
        assertNull(Cube.validateDesc(cube, "205E,99"))
        assertNull(Cube.validateDesc(cube, "205E,2147483647"))
        assertNull(Cube.validateDesc(cube, "0000,0"))
        assertEquals(
            "Not enough hex digits at start of string",
            Cube.validateDesc(cube, "205,4"),
        )
        assertEquals(
            "Not enough hex digits at start of string",
            Cube.validateDesc(cube, "205G,4"),
        )
        assertEquals(
            "Expected ',' after hex digits",
            Cube.validateDesc(cube, "205E"),
        )
        assertEquals(
            "Expected ',' after hex digits",
            Cube.validateDesc(cube, "205E4"),
        )
        assertEquals(
            "Expected decimal integer after ','",
            Cube.validateDesc(cube, "205E,"),
        )
        assertEquals(
            "Expected decimal integer after ','",
            Cube.validateDesc(cube, "205E,+4"),
        )
        assertEquals(
            "Expected decimal integer after ','",
            Cube.validateDesc(cube, "205E, 4"),
        )
        assertEquals(
            "Expected decimal integer after ','",
            Cube.validateDesc(cube, "205E,-1"),
        )
        assertEquals(
            "Expected decimal integer after ','",
            Cube.validateDesc(cube, "205E,4x"),
        )
        assertEquals(
            "Expected decimal integer after ','",
            Cube.validateDesc(cube, "205E,4,5"),
        )

        // The spare low bits of the last character are ignored.
        val tetra = Cube.decodeParams("t1x2")
        assertNull(Cube.validateDesc(tetra, "4257,0"))
        assertEquals(
            Cube.newState(tetra, "4250,0").hashCode(),
            Cube.newState(tetra, "4257,0").hashCode(),
        )
        assertEquals(
            Cube.newState(tetra, "4250,0"),
            Cube.newState(tetra, "4257,0"),
        )
    }

    private companion object {
        const val SEED = "12345"
    }
}
