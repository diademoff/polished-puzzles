package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Level: semantic.
 *
 * `AUXINFO` is a byte string that upstream hex-encodes. The text form
 * must map one character to one byte in both directions, so a value
 * with bytes at or above 0x80 round-trips unchanged. Games generate
 * ASCII, so the high-byte case only appears in a foreign or hand-written
 * save, and the corpus does not cover it.
 */
class AuxInfoTest {

    /** Bytes 0x80 0xFF 0x41, the shortest value with a high byte. */
    private val highBytes = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x41)

    /** The same bytes as the engine holds them, one char per byte. */
    private val highText = highBytes.toString(Charsets.ISO_8859_1)

    /**
     * The hex that upstream produces for [highBytes]. `obfuscate_bitmap`
     * masks byte 0 with SHA-1 of bytes 1 and 2 plus `"0"`, then masks
     * bytes 1 and 2 with SHA-1 of byte 0 plus `"0"`, and `bin2hex`
     * writes the result. A port of that code reproduces the hex of the
     * slant corpus saves, so this value comes from the C algorithm.
     */
    private val highHex = "fd27a0"

    @Test
    fun `a high-byte text round-trips through encode and decode`() {
        assertEquals(highHex, AuxInfo.encode(highText))
        assertEquals(highText, AuxInfo.decode(highHex))
        assertContentEquals(highBytes, AuxInfo.decodeBytes(highHex, highBytes.size))
        assertContentEquals(highBytes, AuxInfo.encode(highText).let {
            AuxInfo.decodeBytes(it, highBytes.size)
        })
    }

    @Test
    fun `a high-byte auxinfo survives a save and a reload`() {
        val midend = Midend.newGame(Fifteen, "4x4", "12345")
        val records = midend.serialise().records.toMutableList()
        val nstates = records.indexOfFirst { it.key == "NSTATES" }
        records.add(nstates, SaveRecord("AUXINFO", highHex))

        val reloaded = Midend.fromSave(Fifteen, SgtpSave.of(records))
        assertEquals(highText, reloaded.auxInfo)
        assertEquals(highHex, reloaded.serialise().value("AUXINFO"))

        // The same holds after the save text goes through the parser.
        val text = SgtpSave.of(records).serialise()
        val reparsed = Midend.fromSave(Fifteen, SgtpSave.parse(text))
        assertEquals(highHex, reparsed.serialise().value("AUXINFO"))
    }
}
