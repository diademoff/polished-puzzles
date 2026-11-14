package name.boyle.chris.sgtpuzzles.backend

/**
 * Works around an Android SharedPreferences bug: a value that ends in a
 * newline is read back with extra trailing spaces
 * (https://issuetracker.google.com/issues/37032278).
 *
 * The C save parser stops after the last record it needs, so it never
 * sees the padding. The Kotlin parser reads to the end, so the padding
 * must be removed before either parser sees it.
 */
object SavedGameText {
    private val trailingSpaces = " +$".toRegex()

    fun trimAndroidPadding(value: String): String =
        value.replaceFirst(trailingSpaces, "")
}
