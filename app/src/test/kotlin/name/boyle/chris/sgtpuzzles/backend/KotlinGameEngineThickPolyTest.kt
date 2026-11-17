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
 * Level: unit. `draw_thick_polygon` reaches the view through the Android
 * `drawPoly` callback with the `draw_polygon` signature but a
 * caller-supplied thickness. The board origin goes to the callback
 * unchanged, and the outline precedes the fill, as `android.c:198-207`
 * passes them.
 */
class KotlinGameEngineThickPolyTest {

    @Test
    fun `the engine forwards the thick polygon with the origin`() {
        val activity = Mockito.mock(GameEngine.ActivityCallbacks::class.java)
        val view = Mockito.mock(GameEngine.ViewCallbacks::class.java)
        val points = intArrayOf(10, 20, 30, 40)

        Mockito.mockStatic(Log::class.java).use {
            val engine = engineFor(activity, view)
            setGeometry(engine, BoardGeometry(24, 7, 11, 100, 100))

            sinkOf(engine).thickPoly(points, 2.5f, 3, 4)
        }

        Mockito.verify(view).drawPoly(2.5f, points, 7, 11, 4, 3)
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
