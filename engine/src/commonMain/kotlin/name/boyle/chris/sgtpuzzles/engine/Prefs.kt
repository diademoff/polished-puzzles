package name.boyle.chris.sgtpuzzles.engine

/**
 * A preferences parse error. [message] is the exact string that
 * `midend_deserialise_prefs` returns to the frontend
 * (`midend.c:3174-3279`), so the app can show the C message.
 */
class PrefsException(message: String) : IllegalArgumentException(message)

/**
 * The per-game preferences of one midend, the counterpart of
 * `me->be_prefs` in `midend.c`.
 *
 * A game declares its schema as [Game.prefs]. The parser and the
 * encoder copy `midend_deserialise_prefs` and `midend_serialise_prefs`:
 *
 * - the text is one `kw=value` line per item, in schema order;
 * - a boolean is exactly `true` or `false`;
 * - a choice is the matching `choicekws` keyword
 *   (`midend.c:3093-3110`, `3257-3283`);
 * - a newline inside a value is written as a newline and one space, and
 *   the loader removes the space again (`midend.c:3083-3092`,
 *   `3191-3229`);
 * - a `#` line and a blank line are skipped, a missing final newline is
 *   accepted, and an unknown keyword is ignored
 *   (`midend.c:3161-3168`, `3231-3238`).
 *
 * `C_STRING` is parsed and encoded too, so the load rules are complete
 * against C even though no upstream game has a string preference. The
 * seam still expects `Game.prefs` to contain only booleans and choices;
 * no ported game declares a string one.
 */
class Prefs private constructor(
    private val schema: List<ConfigItem>,
    private val values: kotlin.collections.Map<String, String>,
) {

    /** The schema with the applied values, in schema order. */
    val items: List<ConfigItem>
        get() = schema.map { withValue(it) }

    /** The value of a boolean preference. */
    fun bool(kw: String): Boolean {
        val item = item(kw)
        check(item is ConfigItem.Bool) { "preference '$kw' is not a boolean" }
        return when (val value = values[kw]) {
            "true" -> true
            "false" -> false
            else -> error("boolean preference '$kw' holds '$value'")
        }
    }

    /** The selected index of a choice preference. */
    fun choice(kw: String): Int {
        val item = item(kw)
        check(item is ConfigItem.Choices) { "preference '$kw' is not a choice" }
        val value = values[kw]
        return value?.toIntOrNull() ?: error("choice preference '$kw' holds '$value'")
    }

    /** The value of a text preference. */
    fun text(kw: String): String {
        val item = item(kw)
        check(item is ConfigItem.Text) { "preference '$kw' is not a string" }
        return values[kw] ?: item.value
    }

    /**
     * Applies edited items by keyword and returns the result. Items with
     * an unknown keyword are ignored, as an unknown line in a
     * preferences file is. The item type must match the schema.
     */
    fun withValues(items: List<ConfigItem>): Prefs {
        val next = LinkedHashMap(values)
        for (item in items) {
            val declared = schema.lastOrNull { it.kw == item.kw } ?: continue
            require(declared::class == item::class) {
                "preference '${item.kw}' has type ${item::class.simpleName}, " +
                    "but the schema declares ${declared::class.simpleName}"
            }
            next[item.kw] = encodedValue(item)
        }
        return Prefs(schema, next)
    }

    /** The upstream file text, one `kw=value` line in schema order. */
    fun encode(): String = encode(schema, this)

    private fun item(kw: String): ConfigItem =
        schema.lastOrNull { it.kw == kw }
            ?: throw IllegalArgumentException("no preference named '$kw'")

    private fun withValue(item: ConfigItem): ConfigItem = when (item) {
        is ConfigItem.Text -> ConfigItem.Text(item.name, values[item.kw] ?: item.value, item.kw)
        is ConfigItem.Bool -> ConfigItem.Bool(
            item.name,
            bool(item.kw),
            item.kw,
        )

        is ConfigItem.Choices -> ConfigItem.Choices(
            item.name,
            item.choices,
            item.keywords,
            choice(item.kw),
            item.kw,
        )
    }

    companion object {
        /**
         * Parses the upstream preferences text. A null or empty text
         * keeps every value of [schema]. The schema values are the
         * current values, as `midend_get_prefs` returns them before the
         * loader applies the lines. A bad value throws [PrefsException]
         * with the C message.
         */
        fun parse(schema: List<ConfigItem>, text: String?): Prefs {
            verifySchema(schema)
            val values = LinkedHashMap<String, String>()
            for (item in schema) values[item.kw] = defaultValue(item)
            if (text.isNullOrEmpty()) return Prefs(schema, values)

            var pos = 0
            var ungot: Char? = null

            fun read(): Int {
                val pending = ungot
                if (pending != null) {
                    ungot = null
                    return pending.code
                }
                if (pos >= text.length) return EOF
                return text[pos++].code
            }

            var eof = false
            while (!eof) {
                val first = read()
                if (first == EOF) break
                val readChar = first.toChar()

                if (readChar == '#' || readChar == '\n') {
                    /*
                     * Skip a comment or a blank line. A blank line is
                     * already at its newline; a comment runs to the
                     * next one. EOF here is success.
                     */
                    var next = first
                    while (next != '\n'.code) {
                        next = read()
                        if (next == EOF) return Prefs(schema, values)
                    }
                    continue
                }

                val keyword = StringBuilder()
                var current = readChar
                while (true) {
                    keyword.append(current)
                    val next = read()
                    if (next == EOF) {
                        throw PrefsException("Partial line at end of preferences file")
                    }
                    if (next == '\n'.code) {
                        throw PrefsException("Expected '=' after keyword")
                    }
                    if (next == '='.code) break
                    current = next.toChar()
                }

                /* C keeps the last schema item with this keyword. */
                val item = schema.lastOrNull { it.kw == keyword.toString() }

                val value = StringBuilder()
                while (true) {
                    val next = read()
                    if (next == EOF) {
                        /* A missing final newline ends a complete directive. */
                        eof = true
                        break
                    }
                    if (next == '\n'.code) {
                        val after = read()
                        if (after == EOF) {
                            eof = true
                            break
                        }
                        if (after == ' '.code) {
                            /* A space-stuffed continuation. */
                            value.append('\n')
                            continue
                        }
                        ungot = after.toChar()
                        break
                    }
                    value.append(next.toChar())
                }

                if (item == null) continue

                val raw = value.toString()
                when (item) {
                    is ConfigItem.Bool -> values[item.kw] = when (raw) {
                        "true" -> "true"
                        "false" -> "false"
                        else -> throw PrefsException(
                            "Value for boolean was not 'true' or 'false'",
                        )
                    }

                    is ConfigItem.Choices -> {
                        val index = choiceKeywords(item).indexOf(raw)
                        if (index < 0) throw PrefsException("Invalid value for enumeration")
                        values[item.kw] = index.toString()
                    }

                    is ConfigItem.Text -> values[item.kw] = raw
                }
            }
            return Prefs(schema, values)
        }

        /** The values that [schema] declares, without a text. */
        fun defaults(schema: List<ConfigItem>): Prefs {
            verifySchema(schema)
            val values = LinkedHashMap<String, String>()
            for (item in schema) values[item.kw] = defaultValue(item)
            return Prefs(schema, values)
        }

        /**
         * The C file encoding: one line per item of [schema], in order,
         * with the space stuffing of `midend_serialise_prefs`. A value
         * that [prefs] does not hold comes from the schema item, so a
         * caller can pass the live schema and an older set of values.
         */
        fun encode(schema: List<ConfigItem>, prefs: Prefs): String {
            val out = StringBuilder()
            for (item in schema) {
                out.append(item.kw).append('=')
                val stored = prefs.values[item.kw]
                out.append(
                    when (item) {
                        is ConfigItem.Text -> stuff(stored ?: item.value)
                        is ConfigItem.Bool -> stored ?: defaultValue(item)
                        is ConfigItem.Choices -> {
                            val index = stored?.toIntOrNull() ?: item.selected
                            val keywords = choiceKeywords(item)
                            require(index in keywords.indices) {
                                "choice preference '${item.kw}' selects $index of " +
                                    "${keywords.size}"
                            }
                            keywords[index]
                        }
                    },
                )
                out.append('\n')
            }
            return out.toString()
        }

        private const val EOF = -1

        private fun defaultValue(item: ConfigItem): String = when (item) {
            is ConfigItem.Text -> item.value
            is ConfigItem.Bool -> if (item.value) "true" else "false"
            is ConfigItem.Choices -> item.selected.toString()
        }

        private fun encodedValue(item: ConfigItem): String = when (item) {
            is ConfigItem.Text -> item.value
            is ConfigItem.Bool -> if (item.value) "true" else "false"
            is ConfigItem.Choices -> item.selected.toString()
        }

        private fun stuff(value: String): String {
            val out = StringBuilder(value.length)
            for (c in value) {
                out.append(c)
                /* `midend.c:3083-3092`: a newline is followed by one space. */
                if (c == '\n') out.append(' ')
            }
            return out.toString()
        }

        private fun choiceKeywords(item: ConfigItem.Choices): List<String> {
            val keywords = requireNotNull(item.keywords) {
                "choice preference '${item.kw}' has no keyword list"
            }
            return separatedList(keywords)
        }

        /**
         * Checks the parts that C assumes: keywords are lowercase
         * letters and hyphens (`midend.c:3070-3071`), a keyword is
         * unique, and a choice has a keyword for every name.
         */
        private fun verifySchema(schema: List<ConfigItem>) {
            val seen = HashSet<String>()
            for (item in schema) {
                require(seen.add(item.kw)) {
                    "duplicate preference keyword '${item.kw}'"
                }
                require(item.kw.all { it in 'a'..'z' || it == '-' }) {
                    "preference keyword '${item.kw}' must be lowercase letters and hyphens"
                }
                if (item is ConfigItem.Choices) {
                    val names = separatedList(item.choices)
                    val keywords = choiceKeywords(item)
                    require(item.selected in names.indices) {
                        "choice preference '${item.kw}' selects ${item.selected} of " +
                            "${names.size}"
                    }
                    require(keywords.size == names.size) {
                        "choice preference '${item.kw}' has ${keywords.size} keywords " +
                            "for ${names.size} names"
                    }
                }
            }
        }
    }
}
