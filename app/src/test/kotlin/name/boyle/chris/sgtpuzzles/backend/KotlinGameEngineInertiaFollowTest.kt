package name.boyle.chris.sgtpuzzles.backend

import android.util.Log
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Test
import org.mockito.Mockito

/**
 * Level: unit. `inertia_follow` is the Android `drawing_api` callback,
 * so the Kotlin engine forwards it to the activity like the C engine
 * does. The shell already implements the callback; this test pins the
 * forwarding from the engine drawing sink.
 */
class KotlinGameEngineInertiaFollowTest {

    @Test
    fun `the engine forwards the inertia follow callback`() {
        val activity = Mockito.mock(GameEngine.ActivityCallbacks::class.java)
        val view = Mockito.mock(GameEngine.ViewCallbacks::class.java)

        Mockito.mockStatic(Log::class.java).use {
            val sink = drawSinkOf(engineFor(activity, view))
            sink.inertiaFollow(true)
            sink.inertiaFollow(false)
        }

        Mockito.verify(activity).inertiaFollow(true)
        Mockito.verify(activity).inertiaFollow(false)
    }

    /**
     * Builds the engine through its private constructor. `fromLaunch`
     * also needs a `Context` and stored preferences, which a JVM unit
     * test does not have. The initializer logs one line, so the caller
     * mocks `Log`.
     */
    private fun engineFor(
        activity: GameEngine.ActivityCallbacks,
        view: GameEngine.ViewCallbacks,
    ): KotlinGameEngine {
        val constructor = KotlinGameEngine::class.java.getDeclaredConstructor(
            Game::class.java,
            Midend::class.java,
            BackendName::class.java,
            GameEngine.ActivityCallbacks::class.java,
            GameEngine.ViewCallbacks::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            Fifteen,
            Midend.newGame(Fifteen, "4x4", "12345"),
            FIFTEEN,
            activity,
            view,
            null,
        )
    }

    /** The live drawing sink of the engine; the shell never sees it. */
    private fun drawSinkOf(engine: KotlinGameEngine): DrawSink {
        val field = KotlinGameEngine::class.java.getDeclaredField("sink")
        field.isAccessible = true
        return field.get(engine) as DrawSink
    }
}
