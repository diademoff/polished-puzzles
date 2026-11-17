package name.boyle.chris.sgtpuzzles.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Level: semantic.
 *
 * The C midend asks the frontend for a random seed before it creates a
 * game, and a game may use those bytes for frontend random state that
 * is independent of the board (`net.c:2061-2085`). The seam hands the
 * bytes to `newUi`, defaults to the one NUL byte of the oracle
 * (`tools/oracle/oracle.c:101-107`), and keeps the bytes out of the
 * save file.
 */
class PlatformSeedTest {

    /** The first `random_upto` values of a seed, as a game would use it. */
    private fun firstValues(seed: ByteArray, count: Int): List<Long> {
        val random = RandomState.fromSeed(seed)
        return List(count) { random.upto(100) }
    }

    /** A UI that exposes the random values that its seed produced. */
    private class SeedUi(val values: List<Long>) : Game.Ui {
        override fun equals(other: Any?): Boolean = other is SeedUi && other.values == values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "SeedUi($values)"
    }

    /** Records the platform seed and turns it into frontend state. */
    private inner class SeedProbe : Game by Fifteen {
        var seenSeed: ByteArray? = null

        override fun newUi(state: Game.State, platformSeed: ByteArray): Game.Ui {
            seenSeed = platformSeed.copyOf()
            return SeedUi(firstValues(platformSeed, 4))
        }
    }

    /** A UI with one field from the platform seed and one saved field. */
    private class BaseUi(val marker: Long, val saved: Long) : Game.Ui {
        override fun equals(other: Any?): Boolean =
            other is BaseUi && other.marker == marker && other.saved == saved

        override fun hashCode(): Int = marker.hashCode() * 31 + saved.hashCode()

        override fun toString(): String = "BaseUi(marker=$marker, saved=$saved)"
    }

    /** Saves one UI field and restores the other from the base UI. */
    private inner class BaseProbe : Game by Fifteen {
        var decodedBase: Game.Ui? = null

        override fun newUi(state: Game.State, platformSeed: ByteArray): Game.Ui =
            BaseUi(firstValues(platformSeed, 1).single(), -1)

        override fun encodeUi(ui: Game.Ui): String? = (ui as BaseUi).saved.toString()

        override fun decodeUi(state: Game.State, text: String, base: Game.Ui): Game.Ui {
            decodedBase = base
            return BaseUi((base as BaseUi).marker, text.toLong())
        }
    }

    @Test
    fun `the default midend gives the game the oracle nul byte`() {
        val game = SeedProbe()
        val midend = Midend.newGame(game, "4x4", "12345")

        assertContentEquals(byteArrayOf(0), game.seenSeed)
        assertEquals(SeedUi(firstValues(byteArrayOf(0), 4)), midend.ui)
    }

    @Test
    fun `an explicit platform seed reaches the game unchanged`() {
        val seed = ByteArray(16) { (it + 1).toByte() }
        val game = SeedProbe()
        val midend = Midend.newGame(game, "4x4", "12345", seed)

        assertContentEquals(seed, game.seenSeed)
        assertEquals(SeedUi(firstValues(seed, 4)), midend.ui)
        assertNotEquals(SeedUi(firstValues(byteArrayOf(0), 4)), midend.ui)
    }

    @Test
    fun `a game id and a load also receive the platform seed`() {
        val seed = ByteArray(16) { (it + 33).toByte() }
        val gameId = Midend.newGame(Fifteen, "4x4", "12345").id()

        val byId = SeedProbe()
        val fromId = Midend.fromGameId(byId, gameId, seed)
        assertContentEquals(seed, byId.seenSeed)
        assertEquals(SeedUi(firstValues(seed, 4)), fromId.ui)

        val save = Midend.newGame(Fifteen, "4x4", "12345").serialise()
        val loader = SeedProbe()
        val fromSave = Midend.fromSave(loader, save, seed)
        assertContentEquals(seed, loader.seenSeed)
        assertEquals(SeedUi(firstValues(seed, 4)), fromSave.ui)
    }

    @Test
    fun `a load keeps the base ui and lets the record override one field`() {
        val seed = ByteArray(16) { (it + 7).toByte() }
        val marker = firstValues(seed, 1).single()
        val save = Midend.newGame(BaseProbe(), "4x4", "12345", seed).serialise()
        assertEquals("-1", save.value("UI"), "the new game writes its fresh UI")

        val records = save.records.map {
            if (it.key == "UI") SaveRecord("UI", "7") else it
        }
        val game = BaseProbe()
        val reloaded = Midend.fromSave(game, SgtpSave.of(records), seed)

        assertEquals(BaseUi(marker, -1), game.decodedBase)
        assertEquals(BaseUi(marker, 7), reloaded.ui)
        assertEquals("7", game.encodeUi(reloaded.ui))
    }

    @Test
    fun `a save without a ui record keeps the base ui`() {
        val seed = ByteArray(16) { (it + 11).toByte() }
        val marker = firstValues(seed, 1).single()
        val save = Midend.newGame(Fifteen, "4x4", "12345").serialise()
        assertNull(save.value("UI"))

        val reloaded = Midend.fromSave(BaseProbe(), save, seed)
        assertEquals(BaseUi(marker, -1), reloaded.ui)
    }

    @Test
    fun `the platform seed does not enter the save or the game id`() {
        val text = Midend.newGame(Fifteen, "4x4", "12345").serialise().serialise()
        val other = Midend.newGame(
            Fifteen,
            "4x4",
            "12345",
            ByteArray(16) { 42 },
        )

        assertEquals(text, other.serialise().serialise())
        assertEquals(Midend.newGame(Fifteen, "4x4", "12345").id(), other.id())

        val reloaded = Midend.fromSave(Fifteen, SgtpSave.parse(text))
        assertEquals(text, reloaded.serialise().serialise())
        assertEquals(Fifteen.Ui(), reloaded.ui)
    }
}
