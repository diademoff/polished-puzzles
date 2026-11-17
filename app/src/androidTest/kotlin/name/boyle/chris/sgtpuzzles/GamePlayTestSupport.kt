package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LAST_PARAMS_PREFIX
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_BACKEND
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_COMPLETED_PREFIX
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_GAME_PREFIX
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.STATE_PREFS_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream

/**
 * Shared setup for the instrumented game tests.
 *
 * Every helper waits on a real state change with a deadline. There is no
 * fixed sleep: a generation runs on the game generator worker, so a test
 * that only waits for an Espresso idle check could read the old game.
 */
object GamePlayTestSupport {

    /**
     * A board with the gap in the bottom right corner. The solved 4x4
     * board is the cheapest deterministic game ID: it needs no
     * generation and no seed.
     */
    const val SOLVED_4X4 = "4x4:1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,0"

    /** Forgets the saved state of fifteen, so a launch starts fresh. */
    fun clearFifteenState(context: Context) = clearBackendState(context, FIFTEEN)

    /** Forgets the saved state of [backend], so a launch starts fresh. */
    fun clearBackendState(context: Context, backend: BackendName) {
        context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(SAVED_BACKEND)
            .remove(SAVED_GAME_PREFIX + backend)
            .remove(SAVED_COMPLETED_PREFIX + backend)
            .remove(LAST_PARAMS_PREFIX + backend)
            .commit()
    }

    /** An explicit GamePlay intent for a game ID or a parameter string. */
    fun intent(context: Context, gameIdOrParams: String): Intent =
        intent(context, FIFTEEN, gameIdOrParams)

    /** An explicit GamePlay intent for [backend] and a game ID or params. */
    fun intent(context: Context, backend: BackendName, gameIdOrParams: String): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("sgtpuzzles:$backend:$gameIdOrParams"),
            context,
            GamePlay::class.java,
        )

    /**
     * The live save text of a Kotlin engine, the same records that the
     * activity writes to SharedPreferences.
     */
    fun saveText(engine: KotlinGameEngine): String =
        ByteArrayOutputStream().also { engine.serialise(it) }.toString("UTF-8")

    /** The number of played moves in the live save, including undo history. */
    fun moveCount(engine: KotlinGameEngine): Int =
        saveText(engine).lineSequence().count { it.startsWith("MOVE") }

    /**
     * Waits until the live engine is a [KotlinGameEngine] that satisfies
     * [predicate]. A save, a game ID, and a generated game all reach the
     * engine through different paths, so the test cannot assume that the
     * engine after a click is already the engine it asked for.
     */
    fun awaitEngine(
        scenario: ActivityScenario<GamePlay>,
        deadlineMs: Long = 10_000,
        predicate: (KotlinGameEngine) -> Boolean = { true },
    ): KotlinGameEngine {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < deadline) {
            var found: KotlinGameEngine? = null
            scenario.onActivity { activity ->
                val engine = activity.gameEngine as? KotlinGameEngine
                if (engine != null && predicate(engine)) found = engine
            }
            found?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("the Kotlin engine did not reach the expected state")
    }

    /** The status line of a loaded game, for example `Moves: 2`. */
    fun statusText(activity: GamePlay): String =
        activity.findViewById<TextView>(R.id.status_bar).text.toString()

    /**
     * Checks that the live engine stays [expected] for the whole
     * [windowMs].
     *
     * An invalid dialog can still hand a configuration to the generator
     * worker after the click returns. There is no new state to wait for,
     * so this polls a bounded window and fails on any new engine, game
     * ID, or parameter string. A new game always replaces the engine
     * instance in `startGameConfirmed`.
     */
    fun awaitEngineUnchanged(
        scenario: ActivityScenario<GamePlay>,
        expected: KotlinGameEngine,
        windowMs: Long = 1_000,
    ) {
        val deadline = SystemClock.uptimeMillis() + windowMs
        var checks = 0
        while (SystemClock.uptimeMillis() < deadline) {
            var observed: KotlinGameEngine? = null
            scenario.onActivity { activity ->
                observed = activity.gameEngine as? KotlinGameEngine
            }
            val engine = observed
                ?: throw AssertionError("the live engine is not a Kotlin engine")
            assertSame("a new game replaced the engine", expected, engine)
            assertEquals("the game ID changed", expected.gameId, engine.gameId)
            assertEquals(
                "the parameters changed",
                expected.currentParams,
                engine.currentParams,
            )
            checks++
            SystemClock.sleep(50)
        }
        assertTrue("the settle window did not run", checks > 0)
    }

    /**
     * Waits until the state store holds a save with the `SEED` record
     * [seed]. `startGameConfirmed` writes the save after the engine is
     * in place, so the seed is the last part of a generated game to
     * arrive.
     */
    fun awaitSavedSeed(context: Context, seed: String, deadlineMs: Long = 10_000) {
        val state = context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        while (SystemClock.uptimeMillis() < deadline) {
            val saved = state.getString(SAVED_GAME_PREFIX + FIFTEEN, null)
            if (saved != null && saved.lines().any { it.startsWith("SEED") && it.endsWith(":$seed") }) {
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("no saved fifteen game with seed '$seed'")
    }
}
