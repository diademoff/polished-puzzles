package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Level: semantic.
 *
 * Fixed vectors recorded from the upstream C implementation through the
 * oracle. The exhaustive stream comparison lives in `:parity`, which
 * runs the oracle on every build.
 */
class RandomStateTest {

    private val hex = "0123456789abcdef"

    private fun sha1Hex(text: String): String {
        val sha = Sha1()
        sha.update(text.encodeToByteArray())
        return sha.final().joinToString("") { byte ->
            val v = byte.toInt() and 0xFF
            "${hex[v ushr 4]}${hex[v and 0x0F]}"
        }
    }

    @Test
    fun `SHA-1 matches the standard vectors`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1Hex(""))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            sha1Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `random_bits matches the upstream stream`() {
        val random = RandomState.fromSeed("12345")
        val values = List(10) { random.bits(8) }
        assertEquals(listOf(202L, 239L, 239L, 20L, 81L, 102L, 42L, 182L, 184L, 198L), values)
        assertEquals(
            "8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e8908980d44" +
                "3c29c69b60c9caefef1451662ab6b8c65888654a119724096b840a",
            random.encodeState(),
        )
    }

    @Test
    fun `random_upto matches the upstream stream`() {
        val random = RandomState.fromSeed("12345")
        val values = List(10) { random.upto(6) }
        assertEquals(listOf(1L, 4L, 4L, 2L, 1L, 3L, 4L, 5L, 5L, 0L), values)
    }

    @Test
    fun `encoded state resumes the same stream`() {
        val random = RandomState.fromSeed("a seed that is longer than one block, for SHA-1")
        repeat(37) { random.bits(5) }
        val encoded = random.encodeState()
        val expected = List(20) { random.bits(7) }

        val decoded = RandomState.decode(encoded)
        assertEquals(expected, List(20) { decoded.bits(7) })
        assertEquals(RandomState.ENCODED_LENGTH, encoded.length)
    }

    @Test
    fun `copy continues independently`() {
        val random = RandomState.fromSeed("12345")
        repeat(3) { random.bits(8) }
        val saved = random.encodeState()
        val copy = random.copy()

        // The original may advance; the copy keeps its own buffers.
        repeat(100) { random.bits(8) }
        assertEquals(saved, copy.encodeState())
        val replay = RandomState.decode(saved)
        assertEquals(List(5) { replay.bits(8) }, List(5) { copy.bits(8) })
    }
}
