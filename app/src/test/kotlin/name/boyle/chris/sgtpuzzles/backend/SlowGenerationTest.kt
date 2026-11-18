package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Net
import name.boyle.chris.sgtpuzzles.launch.GameLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decision point of the slow-generation warning, without an Android
 * runtime. Level: unit.
 *
 * The engine answers for a parameter set; this gate decides whether the
 * shell may show that answer for the launch. A stale answer must never
 * appear for a save, a game ID, a restored state, or a C backend.
 */
class SlowGenerationTest {

    @Test
    fun `a fresh Kotlin generation above the threshold warns`() {
        val launch = GameLaunch.toGenerate(
            TOWERS,
            "9du",
            GameLaunch.Origin.CUSTOM_DIALOG,
        )
        assertEquals(Game.SLOW_GENERATION_WARNING, KotlinGames.generationWarning(launch, "9du"))
    }

    @Test
    fun `a fast parameter set does not warn`() {
        val launch = GameLaunch.toGenerate(
            TOWERS,
            "6xu",
            GameLaunch.Origin.CUSTOM_DIALOG,
        )
        assertNull(KotlinGames.generationWarning(launch, "6xu"))
    }

    /**
     * The app gate, not the absence of a threshold, keeps a C backend
     * quiet: a game that does declare a slow set warns only when
     * `handles` enables it.
     */
    @Test
    fun `a C backend never warns`() {
        val slowNet = object : Game by Net {
            override fun generationWarning(params: Game.Params): String? =
                Game.SLOW_GENERATION_WARNING
        }
        val params = "5x5"
        assertEquals(
            "the stub must declare a threshold, so the gate is what silences it",
            Game.SLOW_GENERATION_WARNING,
            slowNet.generationWarning(Net.decodeParams(params)),
        )
        assertFalse("net must still run on C", KotlinGames.handles(NET))
        val launch = GameLaunch.toGenerate(
            NET,
            params,
            GameLaunch.Origin.GENERATING_FROM_CHOOSER,
        )
        assertNull(KotlinGames.generationWarning(launch, params))
        assertNull(KotlinGames.generationWarning(launch, params, slowNet))
    }

    /** A slow board of the sixth enablement wave warns like the rest. */
    @Test
    fun `a slow sixth-wave board warns`() {
        val launch = GameLaunch.toGenerate(
            GALAXIES,
            "16x16dn",
            GameLaunch.Origin.CUSTOM_DIALOG,
        )
        assertEquals(
            Game.SLOW_GENERATION_WARNING,
            KotlinGames.generationWarning(launch, "16x16dn"),
        )
    }

    /** A game ID loads a board; it generates nothing. */
    @Test
    fun `a game ID does not warn`() {
        val launch = GameLaunch.ofGameID(
            TOWERS,
            "9du:1/2/3",
            GameLaunch.Origin.CUSTOM_DIALOG,
        )
        assertNull(KotlinGames.generationWarning(launch, "9du"))
    }

    /** A restored local state loads a save; it generates nothing. */
    @Test
    fun `a restored local state does not warn`() {
        val launch = GameLaunch.ofLocalState(TOWERS, "GAME    :5:Towers", true)
        assertNull(KotlinGames.generationWarning(launch, "9du"))
    }
}
