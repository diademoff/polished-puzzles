package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.Cube
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.launch.GameLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
     * The app gate passes a handled backend through to its engine. After
     * the eighth enablement wave every ported backend is enabled, so the
     * silent side of the gate has no C backend left to pin; the engine
     * tests pin the threshold of every game that declares one, and this
     * test pins the open side and that `cube` itself stays quiet.
     */
    @Test
    fun `the gate asks the engine of a handled backend`() {
        val slowCube = object : Game by Cube {
            override fun generationWarning(params: Game.Params): String? =
                Game.SLOW_GENERATION_WARNING
        }
        val params = "4x4"
        assertEquals(
            "the stub must declare a threshold, so the gate is what passes it through",
            Game.SLOW_GENERATION_WARNING,
            slowCube.generationWarning(Cube.decodeParams(params)),
        )
        assertTrue("cube runs on the Kotlin backend", KotlinGames.handles(CUBE))
        val launch = GameLaunch.toGenerate(
            CUBE,
            params,
            GameLaunch.Origin.GENERATING_FROM_CHOOSER,
        )
        assertEquals(
            Game.SLOW_GENERATION_WARNING,
            KotlinGames.generationWarning(launch, params, slowCube),
        )
        // The real engine declares no threshold: D-036 bounds `cube` at
        // generation instead.
        assertNull(KotlinGames.generationWarning(launch, params))
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
