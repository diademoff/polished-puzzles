package name.boyle.chris.sgtpuzzles.engine

/**
 * One record of a saved game.
 *
 * The file is the upstream `SAVEFILE` format: a header word padded to
 * eight characters, a colon, a decimal byte length, a colon, the value,
 * and a newline. Values are printable ASCII; a seed that is not
 * printable is hex-encoded as `HEXSEED` by the writer.
 */
data class SaveRecord(val key: String, val value: String) {
    init {
        require(key.isNotEmpty() && key.length <= 8) {
            "save record key must be 1..8 characters, not '$key'"
        }
        require(key.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) {
            "save record key must be upper-case ASCII: '$key'"
        }
        require(value.all { it.code in 32..126 }) {
            "save record '$key' has a non-printable character"
        }
    }
}

/**
 * A lossless container for the upstream `SAVEFILE` format.
 *
 * The parser keeps every record, including repeated moves and unknown
 * keys, in file order. The writer reproduces the canonical text, so a
 * save that passes through the engine is byte-identical. Game-specific
 * validation (`validate_params`, `validate_desc`, `execute_move`) is a
 * later Phase 3 slice; this type owns the container.
 *
 * See `docs/adr/0005-engine-infrastructure-port.md`.
 */
class SgtpSave private constructor(val records: List<SaveRecord>) {

    fun values(key: String): List<String> = records.filter { it.key == key }.map { it.value }

    fun value(key: String): String? = records.firstOrNull { it.key == key }?.value

    /** The canonical text: eight-character key field, length, value, newline. */
    fun serialise(): String = buildString {
        for (record in records) {
            append(record.key.padEnd(8, ' '))
            append(':')
            append(record.value.length)
            append(':')
            append(record.value)
            append('\n')
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SgtpSave && other.records == records

    override fun hashCode(): Int = records.hashCode()

    override fun toString(): String =
        "SgtpSave(game=${value("GAME")}, records=${records.size})"

    companion object {
        const val MAGIC = "Simon Tatham's Portable Puzzle Collection"
        const val VERSION = "1"

        /** A sanity bound: real saves are kilobytes, not megabytes. */
        private const val MAX_VALUE_LENGTH = 8 * 1024 * 1024

        fun of(records: List<SaveRecord>): SgtpSave {
            require(records.isNotEmpty()) { "a save file needs at least one record" }
            return SgtpSave(records.toList())
        }

        /**
         * Parses canonical save text. Leading CR/LF before a record is
         * accepted, as upstream accepts it. The header and the version
         * are checked; a save from a newer format is a hard error.
         */
        fun parse(text: String): SgtpSave {
            require('\u0000' !in text) { "save files do not contain NUL" }
            val records = ArrayList<SaveRecord>()
            var i = 0

            while (true) {
                while (i < text.length && (text[i] == '\r' || text[i] == '\n')) i++
                if (i == text.length) break

                require(i + 8 < text.length) {
                    "truncated record header at offset $i"
                }
                val rawKey = text.substring(i, i + 8)
                i += 8
                require(text[i] == ':') {
                    "expected ':' after the key field at offset $i"
                }
                i++

                val key = rawKey.trimEnd(' ')
                require(rawKey.drop(key.length).all { it == ' ' }) {
                    "key field must be padded with spaces at offset ${i - 9}: '$rawKey'"
                }

                val lengthStart = i
                var length = 0
                while (i < text.length && text[i] in '0'..'9') {
                    val digit = text[i] - '0'
                    require(length <= (MAX_VALUE_LENGTH - digit) / 10) {
                        "record '$key' is longer than $MAX_VALUE_LENGTH characters"
                    }
                    length = length * 10 + digit
                    i++
                }
                require(i > lengthStart) {
                    "expected a decimal length for '$key' at offset $lengthStart"
                }
                require(i < text.length && text[i] == ':') {
                    "expected ':' after the length of '$key' at offset $i"
                }
                i++
                require(i + length <= text.length) {
                    "truncated value for '$key': need $length characters at offset $i"
                }

                records += SaveRecord(key, text.substring(i, i + length))
                i += length
            }

            require(records.isNotEmpty()) { "the save file is empty" }
            require(records.first() == SaveRecord("SAVEFILE", MAGIC)) {
                "not a saved game file: the first record must be SAVEFILE"
            }
            val version = records.firstOrNull { it.key == "VERSION" }?.value
            require(version == VERSION) {
                "unsupported save file version: $version"
            }
            return SgtpSave(records)
        }
    }
}
