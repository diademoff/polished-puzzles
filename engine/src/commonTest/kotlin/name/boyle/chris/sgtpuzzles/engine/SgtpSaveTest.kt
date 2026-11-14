package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Level: semantic.
 *
 * The save format is an exchange format. The parser must keep every
 * record in order and the writer must reproduce upstream bytes exactly.
 * The game corpus round trip lives in `:parity`.
 */
class SgtpSaveTest {

    private val sample = """
        SAVEFILE:41:Simon Tatham's Portable Puzzle Collection
        VERSION :1:1
        GAME    :7:Fifteen
        PARAMS  :3:4x4
        CPARAMS :3:4x4
        SEED    :5:12345
        DESC    :37:14,13,3,10,12,5,6,7,1,8,2,11,0,9,4,15
        NSTATES :1:1
        STATEPOS:1:1
    """.trimIndent() + "\n"

    @Test
    fun `canonical text round-trips byte for byte`() {
        val save = SgtpSave.parse(sample)
        assertEquals(sample, save.serialise())
        assertEquals("Fifteen", save.value("GAME"))
        assertEquals(1, save.values("NSTATES").size)
    }

    @Test
    fun `repeated and unknown records keep their order`() {
        val text = """
            SAVEFILE:41:Simon Tatham's Portable Puzzle Collection
            VERSION :1:1
            GAME    :7:Fifteen
            PARAMS  :3:4x4
            CPARAMS :3:4x4
            DESC    :7:0,1,2,3
            NSTATES :1:3
            STATEPOS:1:2
            MOVE    :4:10,0
            SOLVE   :4:20,0
            FUTURE  :5:hello
        """.trimIndent() + "\n"

        val save = SgtpSave.parse(text)
        assertEquals(listOf("10,0"), save.values("MOVE"))
        assertEquals(listOf("20,0"), save.values("SOLVE"))
        assertEquals("hello", save.value("FUTURE"))
        assertEquals(text, save.serialise())
    }

    @Test
    fun `empty values are legal`() {
        val text = "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :3:Net\n" +
            "UI      :0:\n"
        val save = SgtpSave.parse(text)
        assertEquals("", save.value("UI"))
        assertEquals(text, save.serialise())
    }

    @Test
    fun `a zero-length SEED value is legal`() {
        val text = "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\n" +
            "VERSION :1:1\n" +
            "GAME    :7:Fifteen\n" +
            "PARAMS  :3:4x4\n" +
            "CPARAMS :3:4x4\n" +
            "SEED    :0:\n" +
            "DESC    :37:14,13,3,10,12,5,6,7,1,8,2,11,0,9,4,15\n" +
            "NSTATES :1:1\n" +
            "STATEPOS:1:1\n"
        val save = SgtpSave.parse(text)
        assertEquals("", save.value("SEED"))
        assertEquals(null, save.value("HEXSEED"))
        assertEquals(text, save.serialise())
    }

    @Test
    fun `leading CRLF and a missing newline between records are accepted`() {
        val text = "\r\nSAVEFILE:41:Simon Tatham's Portable Puzzle Collection\r\n" +
            "VERSION :1:1\r\n" +
            "GAME    :3:Net\r\n"
        assertEquals("Net", SgtpSave.parse(text).value("GAME"))

        val joined = "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection" +
            "VERSION :1:1" +
            "GAME    :3:Net"
        assertEquals("Net", SgtpSave.parse(joined).value("GAME"))
    }

    @Test
    fun `bad headers and truncated records are rejected`() {
        assertFailsWith<IllegalArgumentException> { SgtpSave.parse("") }
        assertFailsWith<IllegalArgumentException> {
            SgtpSave.parse(
                "SAVEFILE:41:Not the magic\nVERSION :1:1\n",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SgtpSave.parse(
                "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\nVERSION :1:2\n",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SgtpSave.parse(
                "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\nGAME    :9:Net\n",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SgtpSave.parse(
                "SAVEFILE:41:Simon Tatham's Portable Puzzle Collection\nGAME    :3:Net\nBROKEN\n",
            )
        }
    }

    @Test
    fun `non-ASCII values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SaveRecord("DESC", "caf\u00e9")
        }
        assertFailsWith<IllegalArgumentException> {
            SaveRecord("desc", "x")
        }
        assertTrue(SaveRecord("DESC", "cafe").value == "cafe")
    }
}
