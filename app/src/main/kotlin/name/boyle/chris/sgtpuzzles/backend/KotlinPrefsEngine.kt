package name.boyle.chris.sgtpuzzles.backend

import android.content.Context
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Prefs
import name.boyle.chris.sgtpuzzles.engine.PrefsException

/**
 * The preferences page of a Kotlin backend.
 *
 * It implements only [ConfigBuilder.EngineCallbacks], so it creates no
 * [Midend], no game UI, and it does not generate a game. The page is the
 * midend's `one-key-shortcuts` item followed by [Game.prefs], with the
 * values parsed from the stored text. That is the same item list that
 * `midend_get_prefs` returns for a live midend, as `midend.c:3007-3028`
 * prepends `one-key-shortcuts` and each game's `get_prefs` follows.
 *
 * Ordering: the page is built on the UI thread. [ConfigPreferencesBuilder]
 * calls a `configSet*` method and then [savePrefs] for every change, so a
 * stored edit is applied before it is written.
 */
internal class KotlinPrefsEngine(
    private val backend: BackendName,
    private val game: Game,
    private val context: Context,
    storedText: String? = GameEngineImpl.getPrefs(context, backend),
    private val write: (Context, BackendName, String) -> Unit =
        { c, b, text -> GameEngineImpl.savePrefs(c, b, text) },
) : ConfigBuilder.EngineCallbacks {

    private val schema: List<ConfigItem> =
        listOf(Midend.ONE_KEY_SHORTCUTS_ITEM) + game.prefs

    private val index: Map<String, Int> =
        schema.withIndex().associate { it.value.name to it.index }

    /**
     * The values of the page. A stored text that C would reject leaves
     * the defaults in place, as `android.c:648-656` ignores the error of
     * `midend_deserialise_prefs`.
     */
    private var prefs: Prefs = try {
        Prefs.parse(schema, storedText)
    } catch (_: PrefsException) {
        Prefs.defaults(schema)
    }

    /** The current items, for a test and for the builder. */
    val configItems: List<ConfigItem> get() = prefs.items

    /** Fills [builder] with the page, in C order. */
    fun configEvent(whichEvent: Int, builder: ConfigBuilder) {
        builder.setTitle("${game.name} preferences")
        for (item in configItems) {
            when (item) {
                is ConfigItem.Text ->
                    builder.addString(whichEvent, item.kw, item.name, item.value)

                is ConfigItem.Bool ->
                    builder.addBoolean(whichEvent, item.kw, item.name, item.value)

                is ConfigItem.Choices -> builder.addChoices(
                    whichEvent,
                    item.kw,
                    item.name,
                    item.choices,
                    item.keywords ?: item.choices,
                    item.selected,
                )
            }
        }
        builder.dialogShow()
    }

    /**
     * The preferences page has no dialog of its own; the shell calls
     * [configEvent] with its builder. A call here would be a shell bug,
     * so it fails instead of opening an empty dialog.
     */
    override fun configEvent(
        activityCallbacks: ConfigBuilder.ActivityCallbacks,
        whichEvent: Int,
        context: Context,
        backendName: BackendName,
    ) {
        throw UnsupportedOperationException("the preferences page has no dialog")
    }

    override fun configSetString(itemPtr: String, s: String, isPrefs: Boolean) {
        replace(itemPtr) { (it as ConfigItem.Text).copy(value = s) }
    }

    override fun configSetBool(itemPtr: String, selected: Boolean, isPrefs: Boolean) {
        replace(itemPtr) { (it as ConfigItem.Bool).copy(value = selected) }
    }

    override fun configSetChoice(itemPtr: String, selected: Int, isPrefs: Boolean) {
        replace(itemPtr) { (it as ConfigItem.Choices).copy(selected = selected) }
    }

    override fun configCancel() {}

    override fun configOK(): String = ""

    override val fullGameIDFromDialog: String get() = ""

    override val fullSeedFromDialog: String get() = ""

    /** Writes the C preferences text through [GameEngineImpl.savePrefs]. */
    fun savePrefs(context: Context = this.context) {
        write(context, backend, prefs.encode())
    }

    private fun replace(name: String, update: (ConfigItem) -> ConfigItem) {
        val at = index[name] ?: error("no preference named '$name'")
        prefs = prefs.withValues(listOf(update(configItems[at])))
    }

    companion object {
        /** The page for [backend], without a midend or a generated game. */
        fun forBackend(backend: BackendName, context: Context): KotlinPrefsEngine {
            val game = requireNotNull(KotlinGames.gameFor(backend)) {
                "no Kotlin backend for $backend"
            }
            return KotlinPrefsEngine(backend, game, context)
        }
    }
}
