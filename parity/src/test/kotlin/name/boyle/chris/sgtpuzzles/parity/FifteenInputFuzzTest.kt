package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Buttons
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.engine.KeyOutcome
import name.boyle.chris.sgtpuzzles.engine.Midend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Level: semantic. Game: fifteen.
 *
 * A deterministic input fuzz. Each step sends the same key and the same
 * view point to C and to Kotlin at the same viewport. Coordinates cover
 * the board and the letterbox margins, and the key set covers clicks,
 * drags, releases, cursor keys, the hint key, undo, redo, and solve.
 *
 * The frozen seed makes a failure reproducible. When the two sides
 * disagree, the assertion names the case, the step, and the key.
 */
class FifteenInputFuzzTest {

    private data class Action(val key: Int, val x: Int, val y: Int)

    @Test
    fun randomInputMatchesTheOracle() = OracleClient().use { oracle ->
        val cases = listOf(
            "4x4" to (411 to 702),
            "4x4" to (1024 to 600),
            "5x5" to (200 to 200),
            "3x2" to (720 to 1280),
        )

        for ((params, viewport) in cases) {
            val (viewWidth, viewHeight) = viewport
            var current = oracle.newGame("fifteen", params, SEED).state
            val midend = Midend.newGame(Fifteen, params, SEED)
            val geometry = midend.geometryFor(viewWidth, viewHeight)
            val random = Random(SEED.hashCode() * 31 + viewWidth + viewHeight)
            val where = "$params in $viewWidth x $viewHeight (tile ${geometry.tileSize})"
            var clicksThatMoved = 0

            repeat(STEPS) { step ->
                val action = nextAction(random, viewWidth, viewHeight, step)
                val expected = oracle.keySized(
                    current,
                    action.x,
                    action.y,
                    action.key,
                    viewWidth,
                    viewHeight,
                )
                val outcome = midend.processKey(
                    geometry.toGameX(action.x),
                    geometry.toGameY(action.y),
                    action.key,
                    geometry.tileSize,
                )
                if (action.key == Buttons.LEFT_BUTTON &&
                    outcome == KeyOutcome.SomeEffect
                ) {
                    clicksThatMoved++
                }

                val what = "step $step key ${action.key} at (${action.x},${action.y}) $where"
                assertEquals("save $what", expected.state, midend.serialise().serialise())
                assertEquals("id $what", expected.id, midend.id())
                assertEquals("status $what", expected.status, midend.status())
                assertEquals("undo $what", expected.canUndo, midend.canUndo)
                assertEquals("redo $what", expected.canRedo, midend.canRedo)
                current = midend.serialise().serialise()
            }
            assertTrue("no click moved the board $where", clicksThatMoved > 0)
        }
    }

    private fun nextAction(
        random: Random,
        viewWidth: Int,
        viewHeight: Int,
        step: Int,
    ): Action {
        // Solve once per run, so the completed state and the moves
        // around it are also compared.
        if (step == STEPS / 2) return Action(Buttons.UI_SOLVE, 0, 0)

        val keys = intArrayOf(
            Buttons.LEFT_BUTTON,
            Buttons.LEFT_BUTTON,
            Buttons.LEFT_BUTTON,
            Buttons.RIGHT_BUTTON,
            Buttons.MIDDLE_BUTTON,
            Buttons.LEFT_DRAG,
            Buttons.LEFT_RELEASE,
            Buttons.CURSOR_UP,
            Buttons.CURSOR_DOWN,
            Buttons.CURSOR_LEFT,
            Buttons.CURSOR_RIGHT,
            'h'.code,
            Buttons.UI_UNDO,
            Buttons.UI_REDO,
        )
        val key = keys[random.nextInt(keys.size)]
        val usesPoint = key == Buttons.LEFT_BUTTON ||
            key == Buttons.RIGHT_BUTTON ||
            key == Buttons.MIDDLE_BUTTON ||
            key == Buttons.LEFT_DRAG ||
            key == Buttons.LEFT_RELEASE
        if (!usesPoint) return Action(key, 0, 0)

        // Points outside the board hit the letterbox margin, which the
        // shell must map the same way as C.
        return Action(key, random.nextInt(viewWidth), random.nextInt(viewHeight))
    }

    private companion object {
        const val SEED = "20261114"
        const val STEPS = 120
    }
}
