package name.boyle.chris.sgtpuzzles.backend

import android.content.Context
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder.Event.CFG_PREFS
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Description
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.RandomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Level: unit. The preferences page of a Kotlin backend is built from
 * the engine schema alone: no midend, no game UI, and no generation.
 * The stored text is the C preferences format.
 */
class KotlinPrefsEngineTest {

    @Test
    fun `the page is one-key-shortcuts plus the game schema`() {
        val engine = prefsEngine(FIFTEEN, Fifteen)

        assertEquals(2, engine.configItems.size)
        val shortcuts = engine.configItems[0]
        assertEquals("one-key-shortcuts", shortcuts.kw)
        assertEquals("Keyboard shortcuts without Ctrl", shortcuts.name)
        assertEquals(true, (shortcuts as ConfigItem.Bool).value)
        val arrow = engine.configItems[1]
        assertEquals("arrow-semantics", arrow.kw)
        assertEquals("Sense of arrow keys", arrow.name)
        assertEquals(":Move the tile:Move the gap", (arrow as ConfigItem.Choices).choices)
        assertEquals(":tile:gap", arrow.keywords)
        assertEquals(0, arrow.selected)
    }

    @Test
    fun `the builder receives the title and every item`() {
        val engine = prefsEngine(FIFTEEN, Fifteen)
        val builder = RecordingBuilder()

        engine.configEvent(CFG_PREFS.jni, builder)

        assertEquals("Fifteen preferences", builder.pageTitle)
        assertTrue(builder.shown)
        assertEquals(listOf("one-key-shortcuts", "arrow-semantics"), builder.items.map { it.kw })
        assertEquals("boolean", builder.items[0].type)
        assertEquals("choices", builder.items[1].type)
        assertEquals(":tile:gap", builder.items[1].keywords)
    }

    @Test
    fun `the stored text sets the page values`() {
        val engine = prefsEngine(
            FIFTEEN,
            Fifteen,
            storedText = "one-key-shortcuts=false\narrow-semantics=gap\n",
        )

        assertFalse((engine.configItems[0] as ConfigItem.Bool).value)
        assertEquals(1, (engine.configItems[1] as ConfigItem.Choices).selected)
    }

    @Test
    fun `an unknown key is ignored and a bad value falls back to the defaults`() {
        val unknown = prefsEngine(FIFTEEN, Fifteen, storedText = "future-key=1\narrow-semantics=gap\n")
        assertEquals(1, (unknown.configItems[1] as ConfigItem.Choices).selected)

        // `android.c:648-656` ignores the load error, so the page keeps
        // the compile-time defaults.
        val bad = prefsEngine(FIFTEEN, Fifteen, storedText = "arrow-semantics=nope\n")
        assertEquals(0, (bad.configItems[1] as ConfigItem.Choices).selected)
        assertEquals(true, (bad.configItems[0] as ConfigItem.Bool).value)
    }

    @Test
    fun `an edit is saved as the C preferences text and round trips`() {
        val saved = mutableListOf<String>()
        val engine = prefsEngine(
            FIFTEEN,
            Fifteen,
            storedText = null,
            write = { _, _, text -> saved += text },
        )
        engine.configEvent(CFG_PREFS.jni, RecordingBuilder())

        engine.configSetChoice("Sense of arrow keys", 1, true)
        engine.configSetBool("Keyboard shortcuts without Ctrl", false, true)
        engine.savePrefs()

        assertEquals(listOf("one-key-shortcuts=false\narrow-semantics=gap\n"), saved)

        val reopened = prefsEngine(FIFTEEN, Fifteen, storedText = saved.single())
        assertEquals(engine.configItems, reopened.configItems)
    }

    @Test
    fun `a game with no game prefs still has the shortcuts page`() {
        val saved = mutableListOf<String>()
        val engine = prefsEngine(
            CUBE,
            Cube,
            storedText = null,
            write = { _, _, text -> saved += text },
        )

        assertEquals(1, engine.configItems.size)
        assertEquals("one-key-shortcuts", engine.configItems.single().kw)
        engine.savePrefs()
        assertEquals(listOf("one-key-shortcuts=true\n"), saved)
    }

    @Test
    fun `the page creates no midend and generates nothing`() {
        val saved = mutableListOf<String>()
        val engine = prefsEngine(
            FIFTEEN,
            NeverGenerates,
            storedText = null,
            write = { _, _, text -> saved += text },
        )

        // `NeverGenerates` fails on newDesc, newState, and newUi, so
        // reaching this point proves that the page does not build a
        // game. The written text is preferences only; no save record
        // exists.
        engine.configEvent(CFG_PREFS.jni, RecordingBuilder())
        engine.configSetChoice("Sense of arrow keys", 1, true)
        engine.savePrefs()

        assertEquals("one-key-shortcuts=true\narrow-semantics=gap\n", saved.single())
        assertFalse(saved.single().contains("SAVEFILE"))
        assertFalse(saved.single().contains("DESC"))
    }

    private fun prefsEngine(
        backend: BackendName,
        game: Game,
        storedText: String? = null,
        write: (Context, BackendName, String) -> Unit = { _, _, _ -> },
    ): KotlinPrefsEngine = KotlinPrefsEngine(
        backend = backend,
        game = game,
        context = Mockito.mock(Context::class.java),
        storedText = storedText,
        write = write,
    )

    /**
     * Fifteen's rules with generation and UI creation disabled, so the
     * test can prove that the preferences page never calls them.
     */
    private object NeverGenerates : Game by Fifteen {
        override fun newDesc(params: Game.Params, random: RandomState): Description =
            error("the preferences page must not generate")

        override fun newState(params: Game.Params, desc: String): Game.State =
            error("the preferences page must not build a state")

        override fun newUi(state: Game.State): Game.Ui =
            error("the preferences page must not build a UI")
    }

    private data class RecordedItem(
        val type: String,
        val kw: String,
        val name: String,
        val value: String? = null,
        val bool: Boolean? = null,
        val choices: String? = null,
        val keywords: String? = null,
        val selected: Int? = null,
    )

    private class RecordingBuilder : ConfigBuilder {
        var pageTitle: String? = null
        var shown = false
        val items = mutableListOf<RecordedItem>()

        override fun setTitle(title: String) {
            pageTitle = title
        }

        override fun addString(whichEvent: Int, kw: String, name: String, value: String) {
            items += RecordedItem("string", kw, name, value = value)
        }

        override fun addBoolean(whichEvent: Int, kw: String, name: String, checked: Boolean) {
            items += RecordedItem("boolean", kw, name, bool = checked)
        }

        override fun addChoices(
            whichEvent: Int,
            kw: String,
            name: String,
            choiceList: String,
            choiceKWList: String,
            selection: Int,
        ) {
            items += RecordedItem(
                "choices",
                kw,
                name,
                choices = choiceList,
                keywords = choiceKWList,
                selected = selection,
            )
        }

        override fun dialogShow() {
            shown = true
        }
    }
}
