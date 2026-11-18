package name.boyle.chris.sgtpuzzles.backend

import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.launch.GameLaunch
import org.junit.Assert.assertEquals
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

    /** A slow parameter set on a C backend has no engine hook to ask. */
    @Test
    fun `a C backend never warns`() {
        val launch = GameLaunch.toGenerate(
            MINES,
            "20x20n10",
            GameLaunch.Origin.GENERATING_FROM_CHOOSER,
        )
        assertNull(KotlinGames.generationWarning(launch, "20x20n10"))
        // The engine half still names the set, for the day mines is enabled.
        assertEquals(
            Game.SLOW_GENERATION_WARNING,
            KotlinGames.generationWarning(MINES, "20x20n10"),
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
