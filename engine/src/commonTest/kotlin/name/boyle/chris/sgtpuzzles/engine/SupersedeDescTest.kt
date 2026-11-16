package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Level: semantic. The public and private description seam of ADR 0007
 * (S6), pinned with a test-only game that delegates to `fifteen`.
 *
 * `mines` is the first real consumer: its first click resolves the
 * pending mine layout and calls `midend_supersede_game_desc`, so the
 * save writes a resolved `DESC` and the masked layout as `PRIVDESC`. A
 * load rebuilds state 0 from `PRIVDESC` and then applies `DESC` with
 * `set_public_desc`. The test game reproduces that shape without a
 * mines port: one key resolves the description, and the private text
 * decodes to the same board as the public one.
 */
class SupersedeDescTest {

    private val tileSize = 48

    /**
     * Fifteen with a description that a move can resolve. The private
     * text and the public text decode to the same board, as a mines
     * layout and its public description do; only the text differs, and
     * the leading zeroes make the public text a different string.
     */
    private class DescFifteen : Game by Fifteen {

        var overlaidState: Game.State? = null
            private set

        var overlaidDesc: String? = null
            private set

        val newStateTexts = ArrayList<String>()

        override fun newState(params: Game.Params, desc: String): Game.State {
            newStateTexts += desc
            return Fifteen.newState(params, desc)
        }

        override fun interpretMove(
            state: Game.State,
            ui: Game.Ui,
            x: Int,
            y: Int,
            button: Int,
            tileSize: Int,
        ): MoveResult =
            if (button == 'p'.code) {
                MoveResult.Move(SUPERSEDE_MOVE)
            } else {
                Fifteen.interpretMove(state, ui, x, y, button, tileSize)
            }

        override fun executeMove(state: Game.State, move: String): Game.State? =
            if (move == SUPERSEDE_MOVE) {
                Fifteen.newState(Fifteen.Params(2, 2), PRIVATE)
            } else {
                Fifteen.executeMove(state, move)
            }

        override fun publicDesc(state: Game.State): String? =
            if (state == Fifteen.newState(Fifteen.Params(2, 2), PRIVATE)) PUBLIC else null

        override fun privateDesc(state: Game.State): String? = PRIVATE

        override fun setPublicDesc(state: Game.State, text: String): Game.State {
            overlaidState = state
            overlaidDesc = text
            return state
        }
    }

    /** Fifteen that notes every button which reaches `interpretMove`. */
    private open class RecordingFifteen(private val delegate: Game) : Game by delegate {
        val buttons = ArrayList<Int>()

        override fun interpretMove(
            state: Game.State,
            ui: Game.Ui,
            x: Int,
            y: Int,
            button: Int,
            tileSize: Int,
        ): MoveResult {
            buttons += button
            return delegate.interpretMove(state, ui, x, y, button, tileSize)
        }
    }

    /** Fifteen with `BUTTON_BEATS(LEFT_BUTTON, RIGHT_BUTTON)`, as mines. */
    private class PriorityFifteen : RecordingFifteen(Fifteen) {
        override val flags: Int
            get() = Game.buttonBeatsBit(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON) or
                Game.REQUIRE_RBUTTON
    }

    @Test
    fun `a move supersedes the description and both save records`() {
        val game = DescFifteen()
        val midend = Midend.newGame(game, "2x2", SEED)
        val oldDesc = midend.desc
        val oldId = midend.id()
        assertNotEquals(PUBLIC, oldDesc, "the generated text has no leading zeroes")

        assertEquals(
            KeyOutcome.SomeEffect,
            midend.processKey(0, 0, 'p'.code, tileSize),
        )

        assertEquals(PUBLIC, midend.desc, "DESC is the new public text")
        assertEquals(PRIVATE, midend.privDesc, "PRIVDESC is the game's private text")
        assertNotEquals(oldId, midend.id(), "the game ID carries the public description")
        assertEquals("2x2:$PUBLIC", midend.id())

        val save = midend.serialise()
        assertEquals(PUBLIC, save.value("DESC"))
        assertEquals(PRIVATE, save.value("PRIVDESC"))
        val keys = save.records.map { it.key }
        assertEquals(
            keys.indexOf("DESC") + 1,
            keys.indexOf("PRIVDESC"),
            "PRIVDESC comes directly after DESC",
        )
        assertTrue(keys.indexOf("PRIVDESC") < keys.indexOf("NSTATES"))
    }

    @Test
    fun `restart rebuilds from the superseded public description`() {
        val game = DescFifteen()
        val midend = Midend.newGame(game, "2x2", SEED)
        midend.processKey(0, 0, 'p'.code, tileSize)
        game.newStateTexts.clear()

        assertTrue(midend.restart())

        assertEquals(PUBLIC, game.newStateTexts.single(), "restart uses the public DESC")
        assertEquals(
            Fifteen.newState(Fifteen.Params(2, 2), PRIVATE),
            midend.state,
            "the rebuilt state decodes the public text",
        )
        assertEquals(listOf(PUBLIC), midend.serialise().values("RESTART"))
    }

    @Test
    fun `a reload rebuilds state zero from privdesc and applies the public desc`() {
        val writer = DescFifteen()
        val midend = Midend.newGame(writer, "2x2", SEED)
        midend.processKey(0, 0, 'p'.code, tileSize)
        val postMove = midend.state
        val text = midend.serialise().serialise()

        val reader = DescFifteen()
        val reloaded = Midend.fromSave(reader, SgtpSave.parse(text))

        assertEquals(PUBLIC, reloaded.desc)
        assertEquals(PRIVATE, reloaded.privDesc)
        assertEquals(midend.id(), reloaded.id())
        assertEquals(postMove, reloaded.state, "the move replays on the rebuilt state")

        val stateZero = Fifteen.newState(Fifteen.Params(2, 2), PRIVATE)
        assertEquals(stateZero, reader.overlaidState, "state 0 is the private description")
        assertEquals(PUBLIC, reader.overlaidDesc, "set_public_desc gets the public text")
        assertEquals(
            listOf(PRIVATE),
            reader.newStateTexts,
            "state 0 alone is built from PRIVDESC",
        )
        assertEquals(text, reloaded.serialise().serialise(), "the save is unchanged")

        assertTrue(reloaded.undo())
        assertEquals(stateZero, reloaded.state)
    }

    @Test
    fun `a save without privdesc is unchanged`() {
        val midend = Midend.newGame(Fifteen, "2x2", SEED)
        val save = midend.serialise()
        assertNull(save.value("PRIVDESC"), "a game without a private description writes none")

        val reloaded = Midend.fromSave(Fifteen, SgtpSave.parse(save.serialise()))
        assertEquals(midend.desc, reloaded.desc)
        assertNull(reloaded.privDesc)
        assertEquals(save.serialise(), reloaded.serialise().serialise())

        // A game that can supersede also loads without the record, and
        // then the midend does not apply a public description overlay.
        val writer = DescFifteen()
        val plain = Midend.newGame(writer, "2x2", SEED)
        val reader = DescFifteen()
        val loaded = Midend.fromSave(reader, SgtpSave.parse(plain.serialise().serialise()))
        assertNull(reader.overlaidDesc, "a save without PRIVDESC has no overlay")
        assertEquals(plain.desc, reader.newStateTexts.single())
        assertEquals(plain.desc, loaded.desc)
    }

    @Test
    fun `the button priority ignores the weaker overlapping press`() {
        // The C macro value for a left press that beats a right press.
        assertEquals(
            1 shl ((Buttons.LEFT_BUTTON - Buttons.LEFT_BUTTON) * 3 +
                (Buttons.RIGHT_BUTTON - Buttons.LEFT_BUTTON)),
            Game.buttonBeatsBit(Buttons.LEFT_BUTTON, Buttons.RIGHT_BUTTON),
        )
        val game = PriorityFifteen()
        val midend = Midend.newGame(game, "2x2", SEED)

        midend.processKey(tileSize / 2, tileSize / 2, Buttons.LEFT_BUTTON, tileSize)
        assertEquals(listOf(Buttons.LEFT_BUTTON), game.buttons)
        val history = midend.stateCount

        assertEquals(
            KeyOutcome.Unused,
            midend.processKey(3 * tileSize / 2, tileSize / 2, Buttons.RIGHT_BUTTON, tileSize),
            "BUTTON_BEATS(LEFT, RIGHT) ignores the right press",
        )
        assertEquals(
            listOf(Buttons.LEFT_BUTTON),
            game.buttons,
            "the ignored press never reaches the game",
        )
        assertEquals(history, midend.stateCount, "the ignored press adds no state")

        midend.processKey(3 * tileSize / 2, tileSize / 2, Buttons.LEFT_RELEASE, tileSize)
        assertEquals(Buttons.LEFT_RELEASE, game.buttons.last(), "the left button is still held")
    }

    @Test
    fun `a flagless game releases the previous button on an overlapping press`() {
        val game = RecordingFifteen(Fifteen)
        val midend = Midend.newGame(game, "2x2", SEED)

        midend.processKey(tileSize / 2, tileSize / 2, Buttons.LEFT_BUTTON, tileSize)
        midend.processKey(3 * tileSize / 2, tileSize / 2, Buttons.RIGHT_BUTTON, tileSize)

        assertEquals(
            listOf(Buttons.LEFT_BUTTON, Buttons.LEFT_RELEASE, Buttons.RIGHT_BUTTON),
            game.buttons,
        )
    }

    private companion object {
        const val SEED = "12345"

        /** A fixed 2x2 board, as the private text of the post-move state. */
        const val PRIVATE = "1,2,3,0"

        /**
         * The same board with padded numbers, so the public text is a
         * different string and the overlay is observable.
         */
        const val PUBLIC = "01,02,03,00"

        const val SUPERSEDE_MOVE = "P"
    }
}
