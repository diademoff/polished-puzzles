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

    fun encode(text: String): String = encodeBytes(text.encodeToByteArray())

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
            val c = text[i]
            val value = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> 0
            }
            bytes[i / 2] = (bytes[i / 2].toInt() or
                (value shl (4 * (1 - (i % 2))))).toByte()
        }
        obfuscate(bytes, bytes.size * 8, true)
        return bytes
    }

    private fun obfuscate(bitmap: ByteArray, bits: Int, decode: Boolean) {
        val bytes = (bits + 7) / 8
        val firstHalf = bytes / 2
        val secondHalf = bytes - firstHalf

        if (!decode) {
            mask(bitmap, firstHalf, secondHalf, 0, firstHalf)
            mask(bitmap, 0, firstHalf, firstHalf, secondHalf)
        } else {
            mask(bitmap, 0, firstHalf, firstHalf, secondHalf)
            mask(bitmap, firstHalf, secondHalf, 0, firstHalf)
        }

        if (bits % 8 != 0) {
            bitmap[bits / 8] = (bitmap[bits / 8].toInt() and
                (0xFF and (0xFF00 ushr (bits % 8)))).toByte()
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
