package name.boyle.chris.sgtpuzzles.backend

import android.util.Log
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import name.boyle.chris.sgtpuzzles.engine.DrawSink
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.Game
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Test
import org.mockito.Mockito

/**
 * Level: unit. `draw_thick_line` reaches the view through the Android
 * `drawLine` callback with the same signature as `draw_line` but a
 * caller-supplied thickness. The board origin is added to both
 * endpoints, as `android.c:187-191` does.
 */
class KotlinGameEngineThickLineTest {

    @Test
    fun `the engine forwards the thick line with the origin added`() {
        val activity = Mockito.mock(GameEngine.ActivityCallbacks::class.java)
        val view = Mockito.mock(GameEngine.ViewCallbacks::class.java)

        Mockito.mockStatic(Log::class.java).use {
            val engine = engineFor(activity, view)
            // A non-zero origin proves the offset is applied to both
            // endpoints and not only to the first.
            setGeometry(engine, BoardGeometry(24, 7, 11, 100, 100))

            sinkOf(engine).thickLine(10f, 20f, 30f, 40f, 2.5f, 3)
        }

        Mockito.verify(view).drawLine(2.5f, 17f, 31f, 37f, 51f, 3)
    }

    /**
     * `draw_thick_line` replaces a thickness below one pixel with
     * `1.0F` before the drawing API (`drawing.c:117-118`), so the view
     * receives the clamped width and not `0.0625`.
     */
    @Test
    fun `the engine forwards the clamped thickness`() {
        val activity = Mockito.mock(GameEngine.ActivityCallbacks::class.java)
        val view = Mockito.mock(GameEngine.ViewCallbacks::class.java)

        Mockito.mockStatic(Log::class.java).use {
            val engine = engineFor(activity, view)
            setGeometry(engine, BoardGeometry(24, 7, 11, 100, 100))

            sinkOf(engine).thickLine(10f, 20f, 30f, 40f, 0.0625f, 3)
        }

        Mockito.verify(view).drawLine(1f, 17f, 31f, 37f, 51f, 3)
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
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            Fifteen,
            Midend.newGame(Fifteen, "4x4", "12345"),
            FIFTEEN,
            activity,
            view,
        )
    }

    private fun setGeometry(engine: KotlinGameEngine, geometry: BoardGeometry) {
        val field = KotlinGameEngine::class.java.getDeclaredField("geometry")
        field.isAccessible = true
        field.set(engine, geometry)
    }

    /** The live drawing sink of the engine; the shell never sees it. */
    private fun sinkOf(engine: KotlinGameEngine): DrawSink {
        val field = KotlinGameEngine::class.java.getDeclaredField("sink")
        field.isAccessible = true
        return field.get(engine) as DrawSink
    }
}
