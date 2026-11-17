package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. The S8 preference surface of the midend.
 *
 * The midend owns `one-key-shortcuts` and the game's `get_prefs`
 * values. It applies them to the live UI, writes the C file text, and
 * honours `one-key-shortcuts` for the bare letters only, as
 * `midend.c:1006-1034` does.
 */
class MidendPrefsTest {

    @Test
    fun `a new game starts at the schema defaults`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED)
        assertTrue(midend.oneKeyShortcuts)
        assertEquals(2, midend.prefItems.size)
        assertEquals(0, (midend.prefItems[1] as ConfigItem.Choices).selected)
        assertEquals("one-key-shortcuts=true\narrow-semantics=tile\n", midend.serialisePrefs())
    }

    @Test
    fun `the factory applies the prefs after the ui exists`() {
        val midend = Midend.newGame(
            Fifteen,
            "4x4",
            SEED,
            prefs = "one-key-shortcuts=false\narrow-semantics=gap\n",
        )
        assertFalse(midend.oneKeyShortcuts)
        val arrow = midend.prefItems[1] as ConfigItem.Choices
        assertEquals(1, arrow.selected)
        assertEquals(
            "one-key-shortcuts=false\narrow-semantics=gap\n",
            midend.serialisePrefs(),
        )
        assertTrue((midend.ui as Fifteen.Ui).invertCursor)
    }

    @Test
    fun `a game id and a load also apply the prefs`() {
        val gameId = Midend.newGame(Fifteen, "4x4", SEED).id()
        val byId = Midend.fromGameId(Fifteen, gameId, prefs = "arrow-semantics=gap\n")
        assertEquals(1, (byId.prefItems[1] as ConfigItem.Choices).selected)

        val save = Midend.newGame(Fifteen, "4x4", SEED).serialise()
        val bySave = Midend.fromSave(
            Fifteen,
            save,
            prefs = "one-key-shortcuts=false\n",
        )
        assertFalse(bySave.oneKeyShortcuts)
        assertEquals("one-key-shortcuts=false\narrow-semantics=tile\n", bySave.serialisePrefs())
    }

    /**
     * C calls `midend_load_prefs` and drops the error
     * (`android.c:648-656`), so a stored blob that no longer parses
     * leaves the schema defaults and the game still starts.
     */
    @Test
    fun `an invalid factory prefs text falls back to the defaults`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED, prefs = "arrow-semantics=nope\n")
        assertEquals("one-key-shortcuts=true\narrow-semantics=tile\n", midend.serialisePrefs())
        assertFalse((midend.ui as Fifteen.Ui).invertCursor)
        assertEquals(KeyOutcome.SomeEffect, midend.processKey(48, 48, Buttons.LEFT_BUTTON, TILE))
    }

    @Test
    fun `an invalid text leaves the applied values alone`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED, prefs = "arrow-semantics=gap\n")
        assertEquals("Invalid value for enumeration", midend.applyPrefs("arrow-semantics=nope\n"))
        assertEquals(1, (midend.prefItems[1] as ConfigItem.Choices).selected)
        assertNull(midend.applyPrefs(null))
    }

    @Test
    fun `an unknown keyword is tolerated`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED)
        assertNull(midend.applyPrefs("future-key=1\n"))
        assertEquals("one-key-shortcuts=true\narrow-semantics=tile\n", midend.serialisePrefs())
    }

    @Test
    fun `edited items are stored and applied`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED)
        val items = midend.prefItems.map {
            when (it) {
                is ConfigItem.Bool -> it.copy(value = false)
                is ConfigItem.Choices -> it.copy(selected = 1)
                is ConfigItem.Text -> it
            }
        }
        midend.applyPrefs(items)
        assertFalse(midend.oneKeyShortcuts)
        assertEquals(1, (midend.prefItems[1] as ConfigItem.Choices).selected)
        assertEquals(
            "one-key-shortcuts=false\narrow-semantics=gap\n",
            midend.serialisePrefs(),
        )
    }

    @Test
    fun `one-key shortcuts gate the bare letters but not the aliases`() {
        val midend = Midend.newGame(
            Fifteen,
            "4x4",
            SEED,
            prefs = "one-key-shortcuts=false\n",
        )
        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(96, 192, Buttons.LEFT_BUTTON, TILE),
        )
        assertEquals(2, midend.statePos)

        assertEquals(KeyOutcome.Unused, midend.processKey(0, 0, 'u'.code, TILE))
        assertEquals(2, midend.statePos, "the gated letter must not undo")

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, Buttons.UI_UNDO, TILE),
        )
        assertEquals(1, midend.statePos, "the UI alias is always active")
    }

    @Test
    fun `the one-key setting can be changed directly`() {
        val midend = Midend.newGame(Fifteen, "4x4", SEED)
        midend.oneKeyShortcuts = false
        assertEquals("one-key-shortcuts=false\narrow-semantics=tile\n", midend.serialisePrefs())
    }

    private companion object {
        const val SEED = "12345"
        const val TILE = 48
    }
}
