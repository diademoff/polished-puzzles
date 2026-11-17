package name.boyle.chris.sgtpuzzles.backend

import android.util.Log
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder.Event.CFG_DESC
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder.Event.CFG_PREFS
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder.Event.CFG_SEED
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder.Event.CFG_SETTINGS
import name.boyle.chris.sgtpuzzles.engine.ConfigItem
import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Slant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito

/**
 * Level: unit. The Kotlin engine builds every dialog from the engine
 * schema: the settings page, the seed and game-ID pages, and the
 * preference page. The tests use a fake [ConfigBuilder] and a fake
 * activity, so no Android dialog is created.
 */
class KotlinGameEngineConfigTest {

    private lateinit var log: MockedStatic<Log>

    @Before
    fun setUp() {
        // The engine initializer logs one line.
        log = Mockito.mockStatic(Log::class.java)
    }

    @After
    fun tearDown() {
        log.close()
    }

    @Test
    fun `the fifteen settings page is the engine schema`() {
        val builder = recordingBuilder(engineFor(Fifteen, "4x4", FIFTEEN), CFG_SETTINGS.jni)

        assertEquals("Fifteen configuration", builder.pageTitle)
        assertTrue(builder.shown)
        assertEquals(listOf("Width", "Height"), builder.items.map { it.name })
        assertEquals(listOf("Width", "Height"), builder.items.map { it.kw })
        assertEquals(listOf("4", "4"), builder.items.map { it.value })
    }

    @Test
    fun `the slant settings page carries width, height, and difficulty`() {
        val engine = engineFor(Slant, "8x8dh", SLANT)
        val builder = recordingBuilder(engine, CFG_SETTINGS.jni)

        assertEquals("Slant configuration", builder.pageTitle)
        assertEquals(listOf("Width", "Height", "Difficulty"), builder.items.map { it.name })
        // A settings item uses its display name as the C keyword.
        assertEquals(listOf("Width", "Height", "Difficulty"), builder.items.map { it.kw })
        assertEquals(listOf("8", "8"), builder.items.take(2).map { it.value })
        val difficulty = builder.items[2]
        assertEquals(":Easy:Hard", difficulty.choices)
        // `game_configure` has no `choicekws`; C passes the names instead.
        assertEquals(":Easy:Hard", difficulty.keywords)
        // The settings dialog reads `me->params`, and Slant's short
        // encoding drops the non-persistent difficulty, exactly as C
        // does. A game started on Hard therefore opens on Easy here.
        assertEquals(0, difficulty.selected)
    }

    @Test
    fun `configOK encodes every fifteen preset through custom params`() {
        for (preset in Fifteen.presets()) {
            val engine = engineFor(Fifteen, preset.params, FIFTEEN)
            recordingBuilder(engine, CFG_SETTINGS.jni)
            val expected = Fifteen.encodeParams(Fifteen.decodeParams(preset.params), true)
            assertEquals(preset.title, expected, engine.configOK())
        }
    }

    @Test
    fun `configOK encodes every slant preset through custom params`() {
        for (preset in Slant.presets()) {
            val engine = engineFor(Slant, preset.params, SLANT)
            recordingBuilder(engine, CFG_SETTINGS.jni)
            // C's settings dialog opens on `me->params`, the long-term
            // parameters. Slant's short encoding drops the difficulty,
            // so the dialog and the encoded result use the default one.
            val persistent = Slant.decodeParams(
                Slant.encodeParams(Slant.decodeParams(preset.params), false),
            )
            val expected = Slant.encodeParams(persistent, true)
            assertEquals(preset.title, expected, engine.configOK())
        }
    }

    @Test
    fun `configOK throws the C message for an invalid fifteen edit`() {
        val engine = engineFor(Fifteen, "4x4", FIFTEEN)
        recordingBuilder(engine, CFG_SETTINGS.jni)

        engine.configSetString("Width", "1", false)

        val error = assertThrows(IllegalArgumentException::class.java) { engine.configOK() }
        assertEquals("Width and height must both be at least two", error.message)
    }

    @Test
    fun `configOK throws the C message for an invalid slant edit`() {
        val engine = engineFor(Slant, "8x8", SLANT)
        recordingBuilder(engine, CFG_SETTINGS.jni)

        // The second preset is valid, so the invalid value is the width.
        engine.configSetString("Height", "1", false)

        val error = assertThrows(IllegalArgumentException::class.java) { engine.configOK() }
        assertEquals("Width and height must both be at least two", error.message)
    }

    @Test
    fun `the game ID dialog validates a bad description with the C message`() {
        val engine = engineFor(Fifteen, "4x4", FIFTEEN)
        val builder = recordingBuilder(engine, CFG_DESC.jni)

        assertEquals("Fifteen game selection", builder.pageTitle)
        assertEquals("4x4:" + midendOf(engine).desc, builder.items.single().value)

        engine.configSetString("Game ID", "1x1:1,2,0,3", false)

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.fullGameIDFromDialog
        }
        assertEquals("Width and height must both be at least two", error.message)
    }

    @Test
    fun `the seed dialog validates a bad parameter set with the C message`() {
        val engine = engineFor(Fifteen, "4x4", FIFTEEN)
        val builder = recordingBuilder(engine, CFG_SEED.jni)

        assertEquals("Fifteen random selection", builder.pageTitle)
        assertEquals("4x4#12345", builder.items.single().value)

        engine.configSetString("Seed", "1x1#12345", false)

        val error = assertThrows(IllegalArgumentException::class.java) {
            engine.fullSeedFromDialog
        }
        assertEquals("Width and height must both be at least two", error.message)
    }

    @Test
    fun `a valid edited game ID is returned as typed`() {
        val engine = engineFor(Fifteen, "2x2", FIFTEEN)
        recordingBuilder(engine, CFG_DESC.jni)
        engine.configSetString("Game ID", "2x2:1,2,0,3", false)

        assertEquals("2x2:1,2,0,3", engine.fullGameIDFromDialog)
    }

    @Test
    fun `configOK validates the seed dialog`() {
        val engine = engineFor(Fifteen, "4x4", FIFTEEN)
        recordingBuilder(engine, CFG_SEED.jni)

        assertEquals("4x4#12345", engine.configOK())

        engine.configSetString("Seed", "1x1#12345", false)
        val error = assertThrows(IllegalArgumentException::class.java) { engine.configOK() }
        assertEquals("Width and height must both be at least two", error.message)
    }

    @Test
    fun `the preference page is one-key-shortcuts plus the game prefs`() {
        val engine = engineFor(Fifteen, "4x4", FIFTEEN)
        val builder = recordingBuilder(engine, CFG_PREFS.jni)

        assertEquals("Fifteen preferences", builder.pageTitle)
        assertEquals(2, builder.items.size)
        val shortcuts = builder.items[0]
        assertEquals("one-key-shortcuts", shortcuts.kw)
        assertEquals("Keyboard shortcuts without Ctrl", shortcuts.name)
        assertEquals(true, shortcuts.bool)
        val arrow = builder.items[1]
        assertEquals("arrow-semantics", arrow.kw)
        assertEquals("Sense of arrow keys", arrow.name)
        assertEquals(":Move the tile:Move the gap", arrow.choices)
        assertEquals(":tile:gap", arrow.keywords)
        assertEquals(0, arrow.selected)
    }

    @Test
    fun `a game with no game prefs still has the shortcuts page`() {
        val engine = engineFor(Cube, "4x4", CUBE)
        val builder = recordingBuilder(engine, CFG_PREFS.jni)

        assertEquals("Cube preferences", builder.pageTitle)
        assertEquals(1, builder.items.size)
        assertEquals("one-key-shortcuts", builder.items.single().kw)
    }

    @Test
    fun `a preference edit is applied before it is saved`() {
        val engine = engineFor(Fifteen, "4x4", FIFTEEN)
        recordingBuilder(engine, CFG_PREFS.jni)

        engine.configSetChoice("Sense of arrow keys", 1, true)
        engine.configSetBool("Keyboard shortcuts without Ctrl", false, true)

        val midend = midendOf(engine)
        assertFalse(midend.oneKeyShortcuts)
        assertEquals(1, (midend.prefItems[1] as ConfigItem.Choices).selected)
        assertTrue((midend.ui as Fifteen.Ui).invertCursor)
        assertEquals(
            "one-key-shortcuts=false\narrow-semantics=gap\n",
            midend.serialisePrefs(),
        )
    }

    @Test
    fun `a stored preference is on the page and on the first geometry`() {
        val engine = engineFor(
            Fifteen,
            "4x4",
            FIFTEEN,
            prefs = "arrow-semantics=gap\n",
        )
        val builder = recordingBuilder(engine, CFG_PREFS.jni)

        assertEquals(1, builder.items[1].selected)
        assertTrue((midendOf(engine).ui as Fifteen.Ui).invertCursor)
    }

    @Test
    fun `the status bar is set only for a game that wants one`() {
        val fifteenActivity = RecordingActivity()
        val fifteen = engineFor(Fifteen, "4x4", FIFTEEN, activity = fifteenActivity)
        fifteen.resizeEvent(320, 320, 0)
        assertEquals(1, fifteenActivity.statuses.size)

        val slantActivity = RecordingActivity()
        val slant = engineFor(Slant, "8x8", SLANT, activity = slantActivity)
        slant.resizeEvent(320, 320, 0)
        assertTrue(slantActivity.statuses.isEmpty())
    }

    private fun recordingBuilder(engine: KotlinGameEngine, event: Int): RecordingBuilder =
        RecordingBuilder().also { engine.configEvent(event, it) }

    private fun engineFor(
        game: Game,
        params: String,
        backend: BackendName,
        prefs: String? = null,
        activity: GameEngine.ActivityCallbacks = RecordingActivity(),
    ): KotlinGameEngine {
        val midend = Midend.newGame(game, params, "12345", prefs = prefs)
        val constructor = KotlinGameEngine::class.java.getDeclaredConstructor(
            Game::class.java,
            Midend::class.java,
            BackendName::class.java,
            GameEngine.ActivityCallbacks::class.java,
            GameEngine.ViewCallbacks::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            game,
            midend,
            backend,
            activity,
            Mockito.mock(GameEngine.ViewCallbacks::class.java),
        )
    }

    private fun midendOf(engine: KotlinGameEngine): Midend {
        val field = KotlinGameEngine::class.java.getDeclaredField("midend")
        field.isAccessible = true
        return field.get(engine) as Midend
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

    private class RecordingActivity : GameEngine.ActivityCallbacks {
        val statuses = mutableListOf<String>()

        override fun allowFlash(): Boolean = true
        override fun changedState(canUndo: Boolean, canRedo: Boolean) {}
        override fun completed() {}
        override fun inertiaFollow(isSolved: Boolean) {}
        override fun purgingStates() {}
        override fun requestTimer(on: Boolean) {}
        override fun setStatus(status: String) {
            statuses += status
        }
    }
}
