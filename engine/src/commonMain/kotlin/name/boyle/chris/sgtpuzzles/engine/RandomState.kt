package name.boyle.chris.sgtpuzzles.engine

/**
 * A bit-exact port of the upstream `random.c`.
 *
 * Game seeds must produce the same game on every platform, so this is
 * not a general-purpose random number generator: it is the upstream
 * SHA-1 counter generator, including its quirks. Do not replace it with
 * `kotlin.random.Random`, and do not "fix" the byte order or the
 * rejection loop. A difference here changes every generated board.
 *
 * The C implementation is the source of truth. `RandomParityTest` in
 * the `:parity` module compares streams and encoded states with the
 * oracle.
 */
class RandomState private constructor(
    private val seedBuf: ByteArray,
    private val dataBuf: ByteArray,
    private var pos: Int,
) {

    companion object {
        /** 40 bytes of seed material, 20 bytes of output, and one position byte. */
        const val ENCODED_LENGTH = (40 + 20 + 1) * 2

        fun fromSeed(seed: String): RandomState = fromSeed(seed.encodeToByteArray())

        fun fromSeed(seed: ByteArray): RandomState {
            val seedBuf = ByteArray(40)
            sha1(seed).copyInto(seedBuf, 0)
            sha1(seedBuf.copyOfRange(0, 20)).copyInto(seedBuf, 20)
            return RandomState(seedBuf, sha1(seedBuf.copyOfRange(0, 40)), 0)
        }

        /**
         * Reads `random_state_encode` output. Malformed input follows
         * the C decoder: a non-hex character is a zero nibble, and an
         * odd trailing nibble is ignored.
         */
        fun decode(text: String): RandomState {
            val seedBuf = ByteArray(40)
            val dataBuf = ByteArray(20)
            var pos = 0
            var byte = 0
            var digits = 0

            for (c in text) {
                val value = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'A'..'F' -> c - 'A' + 10
                    in 'a'..'f' -> c - 'a' + 10
                    else -> 0
                }
                byte = (byte shl 4) or value
                digits++
                if (digits == 2) {
                    when {
                        pos < seedBuf.size -> seedBuf[pos++] = byte.toByte()
                        pos < seedBuf.size + dataBuf.size ->
                            dataBuf[pos++ - seedBuf.size] = byte.toByte()

                        pos == seedBuf.size + dataBuf.size && byte <= dataBuf.size ->
                            pos = byte
                    }
                    byte = 0
                    digits = 0
                }
            }
            return RandomState(seedBuf, dataBuf, pos)
        }

        private fun sha1(bytes: ByteArray): ByteArray {
            val sha = Sha1()
            sha.update(bytes)
            return sha.final()
        }

        private const val HEX = "0123456789abcdef"
    }

    /**
     * Copies the generator. Upstream uses this when a generator state
     * must be shared between two attempts.
     */
    fun copy(): RandomState = RandomState(seedBuf.copyOf(), dataBuf.copyOf(), pos)

    /** `random_bits`: an unsigned value in `0 until 2^bits`. */
    fun bits(bits: Int): Long {
        require(bits in 1..31) { "random_bits supports 1..31 bits, not $bits" }
        var ret = 0L
        var n = 0
        while (n < bits) {
            if (pos >= dataBuf.size) {
                // Increment the 40-byte seed as a little-endian integer.
                var i = 0
                while (i < seedBuf.size) {
                    val b = seedBuf[i].toInt() and 0xFF
                    if (b != 0xFF) {
                        seedBuf[i] = (b + 1).toByte()
                        break
                    }
                    seedBuf[i] = 0
                    i++
                }
                sha1(seedBuf).copyInto(dataBuf, 0)
                pos = 0
            }
            ret = (ret shl 8) or (dataBuf[pos++].toLong() and 0xFF)
            n += 8
        }
        return ret and ((1L shl (bits - 1)) * 2 - 1)
    }

    /** `random_upto`: an unsigned value in `0 until limit`, without bias. */
    fun upto(limit: Long): Long {
        require(limit > 0) { "random_upto needs a positive limit" }
        var bits = 0
        while ((limit ushr bits) != 0L) bits++
        bits += 3
        require(bits < 32) { "random_upto limit is too large: $limit" }

        val range = 1L shl bits
        val divisor = range / limit
        val accepted = limit * divisor
        var data: Long
        do {
            data = bits(bits)
        } while (data >= accepted)
        return data / divisor
    }

    /** `random_state_encode`: 40 + 20 bytes and the position, lowercase hex. */
    fun encodeState(): String {
        val out = StringBuilder(ENCODED_LENGTH)
        fun byte(value: Byte) {
            val v = value.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        for (b in seedBuf) byte(b)
        for (b in dataBuf) byte(b)
        byte(pos.toByte())
        return out.toString()
    }
}

/** SHA-1, as used by upstream `random.c`. Not a general crypto API. */
internal class Sha1 {
    private val h = intArrayOf(
        0x67452301,
        0xEFCDAB89.toInt(),
        0x98BADCFE.toInt(),
        0x10325476,
        0xC3D2E1F0.toInt(),
    )
    private val block = ByteArray(64)
    private var blockUsed = 0
    private var lenLo = 0
    private var lenHi = 0

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        var q = offset
        var len = length
        val lenWord = len

        lenLo += lenWord
        lenHi += if (lenLo.toUInt() < lenWord.toUInt()) 1 else 0

        if (blockUsed != 0 && blockUsed + len < 64) {
            data.copyInto(block, blockUsed, q, q + len)
            blockUsed += len
            return
        }

        while (blockUsed + len >= 64) {
            val take = 64 - blockUsed
            data.copyInto(block, blockUsed, q, q + take)
            q += take
            len -= take
            transform()
            blockUsed = 0
        }
        data.copyInto(block, 0, q, q + len)
        blockUsed = len
    }

    fun final(): ByteArray {
        val pad = if (blockUsed >= 56) 56 + 64 - blockUsed else 56 - blockUsed
        val lenHiBits = (lenHi shl 3) or (lenLo ushr 29)
        val lenLoBits = lenLo shl 3

        val padding = ByteArray(pad)
        padding[0] = 0x80.toByte()
        update(padding)

        val tail = ByteArray(8)
        putBigEndian(tail, 0, lenHiBits)
        putBigEndian(tail, 4, lenLoBits)
        update(tail)

        val out = ByteArray(20)
        for (i in h.indices) putBigEndian(out, i * 4, h[i])
        return out
    }

    private fun transform() {
        val w = IntArray(80)
        for (t in 0..15) {
            w[t] = ((block[t * 4].toInt() and 0xFF) shl 24) or
                ((block[t * 4 + 1].toInt() and 0xFF) shl 16) or
                ((block[t * 4 + 2].toInt() and 0xFF) shl 8) or
                (block[t * 4 + 3].toInt() and 0xFF)
        }
        for (t in 16..79) {
            w[t] = rol(w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16], 1)
        }

        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]

        for (t in 0..19) {
            val tmp = rol(a, 5) + ((b and c) or (d and b.inv())) + e + w[t] + 0x5A827999
            e = d
            d = c
            c = rol(b, 30)
            b = a
            a = tmp
        }
        for (t in 20..39) {
            val tmp = rol(a, 5) + (b xor c xor d) + e + w[t] + 0x6ED9EBA1
            e = d
            d = c
            c = rol(b, 30)
            b = a
            a = tmp
        }
        for (t in 40..59) {
            val tmp = rol(a, 5) + ((b and c) or (b and d) or (c and d)) + e + w[t] +
                0x8F1BBCDC.toInt()
            e = d
            d = c
            c = rol(b, 30)
            b = a
            a = tmp
        }
        for (t in 60..79) {
            val tmp = rol(a, 5) + (b xor c xor d) + e + w[t] + 0xCA62C1D6.toInt()
            e = d
            d = c
            c = rol(b, 30)
            b = a
            a = tmp
        }

        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
    }

    private fun rol(x: Int, y: Int): Int = (x shl y) or (x ushr (32 - y))

    private fun putBigEndian(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }
}
