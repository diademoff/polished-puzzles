package name.boyle.chris.sgtpuzzles.engine

/**
 * One entry of an upstream `config_item` list, the item that a settings
 * dialog and a preferences page show.
 *
 * [name] is the display name. [kw] is the C `kw`, the preference
 * keyword. It defaults to [name] because `android.c` passes the display
 * name as the keyword for a settings item (`android.c:492-512`); only a
 * preference item carries a real keyword, from `midend.c:3007-3028` and
 * each game's `get_prefs`.
 *
 * [Choices.choices] and [Choices.keywords] keep the C strings verbatim,
 * including the leading separator, so the Android builders and the
 * preference parser read the same bytes as C. A settings item has
 * `keywords == null`, because `game_configure` never sets `choicekws`
 * and `android.c:505-508` passes `choices` in its place.
 */
sealed interface ConfigItem {
    val name: String

    /** The C `kw`: the preference keyword, or the display name. */
    val kw: String

    /** `C_STRING`: a text field. */
    data class Text(
        override val name: String,
        val value: String,
        override val kw: String = name,
    ) : ConfigItem

    /** `C_BOOLEAN`: a check box. */
    data class Bool(
        override val name: String,
        val value: Boolean,
        override val kw: String = name,
    ) : ConfigItem

    /**
     * `C_CHOICES`: a list of names selected by index.
     *
     * [choices] is C `choicenames` and [keywords] is C `choicekws`. Both
     * are a leading separator followed by the names, as
     * `:Easy:Hard` and `:easy:hard`. [keywords] is null for a settings
     * item, where C leaves `choicekws` unset.
     */
    data class Choices(
        override val name: String,
        val choices: String,
        val keywords: String?,
        val selected: Int,
        override val kw: String = name,
    ) : ConfigItem
}

/**
 * Splits a C separated list such as `:Easy:Hard`. The first character
 * is the separator, as `strcspn` and the `C_CHOICES` loops in
 * `midend.c:3093-3110` and `3260-3275` use it. An empty item is kept,
 * because upstream compares a zero-length keyword too.
 */
internal fun separatedList(text: String): List<String> {
    require(text.isNotEmpty()) { "a separated list needs a separator" }
    val separator = text[0]
    val items = ArrayList<String>()
    var start = 1
    var index = 1
    while (true) {
        if (index == text.length || text[index] == separator) {
            items += text.substring(start, index)
            if (index == text.length) break
            start = index + 1
        }
        index++
    }
    return items
}
