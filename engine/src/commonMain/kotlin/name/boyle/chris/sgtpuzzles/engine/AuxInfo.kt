package name.boyle.chris.sgtpuzzles.engine

/**
 * The obfuscated auxiliary answer in a save file.
 *
 * Upstream writes `AUXINFO` as an OAEP-like mask over the solution text
 * and then hex-encodes it, so a quick `head` of a save does not spoil
 * the puzzle. The mask is SHA-1 over the seed half and a decimal
 * counter. This is a direct port of `obfuscate_bitmap`, `bin2hex`, and
 * `hex2bin` from `misc.c`.
 */
internal object AuxInfo {

    private const val HEX = "0123456789abcdef"

    /**
     * The text-to-bytes step is ISO-8859-1, the reverse of [decode], so
     * every byte keeps its value. Upstream `AUXINFO` is a byte string,
     * not Unicode text. A character outside ISO-8859-1 has no byte, so
     * the encoder writes `?` for it. Games generate ASCII.
     */
    fun encode(text: String): String =
        encodeBytes(text.toByteArray(Charsets.ISO_8859_1))

    fun decode(text: String): String =
        decodeBytes(text, text.length / 2).toString(Charsets.ISO_8859_1)

    /**
     * `obfuscate_bitmap` plus `bin2hex` for a whole number of bytes.
     * The bytes are obfuscated in place, as C does, so the caller must
     * pass an array it owns. `guess` uses this for its description.
     */
    fun encodeBytes(bytes: ByteArray): String {
        obfuscate(bytes, bytes.size * 8, false)
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                append(HEX[(b.toInt() ushr 4) and 0xF])
                append(HEX[b.toInt() and 0xF])
            }
        }
    }

    /**
     * `hex2bin` plus the decode direction of `obfuscate_bitmap`, for
     * [size] bytes. A non-hex character is a zero nibble, as C's
     * `hex2bin` does. The caller checks the text length first, as every
     * upstream caller does.
     */
    fun decodeBytes(text: String, size: Int): ByteArray {
        require(text.length >= size * 2) {
            "hex text is too short for $size bytes: '${text}'"
        }
        val bytes = ByteArray(size)
        for (i in 0 until size * 2) {
            val value = hexNibble(text[i])
            bytes[i / 2] = (bytes[i / 2].toInt() or
                (value shl (4 * (1 - (i % 2))))).toByte()
        }
        obfuscate(bytes, bytes.size * 8, true)
        return bytes
    }

    /**
     * `obfuscate_bitmap` plus the nibble encoding of `describe_layout`:
     * a bitmap of exactly [bits] bits, written as `(bits + 3) / 4` hex
     * digits. The low nibble of the last partial byte is dropped, as
     * the C encoder works to nibble granularity because the mask keeps
     * the bit count. The bytes are obfuscated in place, as C does, so
     * the caller must pass an array it owns.
     *
     * When [bits] is a multiple of eight the output is the same string
     * as [encodeBytes] for the same bytes.
     */
    fun encodeMask(bytes: ByteArray, bits: Int): String {
        require(bits >= 0) { "bit count is negative: $bits" }
        require(bytes.size * 8 >= bits) {
            "bitmap has ${bytes.size * 8} bits, need $bits"
        }
        obfuscate(bytes, bits, false)
        return buildString((bits + 3) / 4) {
            for (i in 0 until (bits + 3) / 4) {
                val value = bytes[i / 2].toInt() and 0xFF
                append(HEX[if (i % 2 == 0) value ushr 4 else value and 0xF])
            }
        }
    }

    /**
     * The decode direction of [encodeMask]: `hex2bin` at nibble
     * granularity plus the decode of `obfuscate_bitmap`, for exactly
     * [bits] bits. The result is `(bits + 7) / 8` bytes. A non-hex
     * character is a zero nibble, as C reads it.
     */
    fun decodeMask(text: String, bits: Int): ByteArray {
        require(bits >= 0) { "bit count is negative: $bits" }
        val nibbles = (bits + 3) / 4
        require(text.length >= nibbles) {
            "hex mask is too short for $bits bits: '${text}'"
        }
        val bytes = ByteArray((bits + 7) / 8)
        for (i in 0 until nibbles) {
            val value = hexNibble(text[i])
            bytes[i / 2] = (bytes[i / 2].toInt() or
                (value shl (4 * (1 - (i % 2))))).toByte()
        }
        obfuscate(bytes, bits, true)
        return bytes
    }

    /** One hex digit as 0..15. A non-hex character is zero, as `hex2bin` reads it. */
    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private fun obfuscate(bitmap: ByteArray, bits: Int, decode: Boolean) {
        val bytes = (bits + 7) / 8
        val firstHalf = bytes / 2
        val secondHalf = bytes - firstHalf

        /*
         * C masks the pad bits of the last byte after each step, so the
         * second step reads the cleared byte as its seed. The clear is
         * a no-op for a whole number of bytes, and it is required for a
         * bitmap such as the mines layout, whose bit count is the board
         * area.
         */
        fun clearPad() {
            if (bits % 8 != 0) {
                bitmap[bits / 8] = (bitmap[bits / 8].toInt() and
                    (0xFF and (0xFF00 ushr (bits % 8)))).toByte()
            }
        }

        if (!decode) {
            mask(bitmap, firstHalf, secondHalf, 0, firstHalf)
            clearPad()
            mask(bitmap, 0, firstHalf, firstHalf, secondHalf)
            clearPad()
        } else {
            mask(bitmap, 0, firstHalf, firstHalf, secondHalf)
            clearPad()
            mask(bitmap, firstHalf, secondHalf, 0, firstHalf)
            clearPad()
        }
    }

    private fun mask(
        bitmap: ByteArray,
        seedStart: Int,
        seedLength: Int,
        targetStart: Int,
        targetLength: Int,
    ) {
        var digestPosition = 20
        var counter = 0
        var digest = ByteArray(20)
        for (j in 0 until targetLength) {
            if (digestPosition >= 20) {
                val sha = Sha1()
                sha.update(bitmap, seedStart, seedLength)
                sha.update(counter.toString().encodeToByteArray())
                digest = sha.final()
                digestPosition = 0
                counter++
            }
            bitmap[targetStart + j] =
                (bitmap[targetStart + j].toInt() xor digest[digestPosition++].toInt()).toByte()
        }
    }
}
