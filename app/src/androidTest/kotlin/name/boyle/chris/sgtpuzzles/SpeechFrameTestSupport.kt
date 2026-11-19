package name.boyle.chris.sgtpuzzles

import android.graphics.RectF
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import name.boyle.chris.sgtpuzzles.engine.SpokenUnit
import name.boyle.chris.sgtpuzzles.engine.spokenLabel
import name.boyle.chris.sgtpuzzles.render.BOARD_CANVAS_TAG
import name.boyle.chris.sgtpuzzles.render.boardUnitTag
import name.boyle.chris.sgtpuzzles.render.units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt

/**
 * What one drawing pass says about the board, read on the main thread
 * for the assertions of [BoardGeometryParityTest] and the landscape
 * test.
 */
data class SpeechFrame(
    val units: List<SpokenUnit>,
    val labels: List<String>,
    /** The matrix that maps game dp to view px, as `BoardCanvas` draws. */
    val matrix: FloatArray,
    /** The game's own cursor rectangle, in view dp. */
    val cursor: RectF,
    /** The display density that Compose reports back to the root. */
    val density: Float,
    val hasFrame: Boolean,
)

/** Reads the current board speech, matrix, and cursor from a game screen. */
fun readSpeechFrame(activity: GamePlay): SpeechFrame {
    val view = activity.findViewById<GameView>(R.id.game_view)
    val speech = view.boardSpeech.value
    val units = speech?.units().orEmpty()
    return SpeechFrame(
        units = units,
        labels = units.map { it.spokenLabel() },
        matrix = view.renderMatrix.value.copyOf(),
        cursor = activity.gameEngine.cursorLocation,
        density = activity.resources.displayMetrics.density,
        hasFrame = view.renderFrame.value != null,
    )
}

/**
 * The 2.6x guard of D-01: a unit node must land on the pixels that the
 * drawing matrix puts its rectangle on, and that rectangle must be the
 * one the game itself draws for the same cell.
 *
 * The test finds the unit under the engine's cursor rectangle, which is
 * the drawing side of the same cell, and compares the node's bounds in
 * the Compose root with the matrix-mapped rectangle. The first wave's
 * bug placed the units in game dp without the matrix, so this assertion
 * fails by a factor of the display density.
 */
fun assertUnitsLandOnTheDrawing(composeRule: ComposeTestRule, frame: SpeechFrame, where: String) {
    assertTrue("$where: the board was never drawn", frame.hasFrame)

    val index = frame.units.indexOfFirst { unit ->
        unit.row > 0 &&
            unit.left == frame.cursor.left.roundToInt() &&
            unit.top == frame.cursor.top.roundToInt() &&
            unit.width == frame.cursor.width().roundToInt() &&
            unit.height == frame.cursor.height().roundToInt()
    }
    assertTrue(
        "$where: no unit sits on the drawing cursor rect $frame.cursor; " +
            "labels=${frame.labels}",
        index >= 0,
    )
    val unit = frame.units[index]
    val matrix = Matrix(frame.matrix)
    val mapped = matrix.map(
        Rect(
            left = unit.left.toFloat(),
            top = unit.top.toFloat(),
            right = (unit.left + unit.width).toFloat(),
            bottom = (unit.top + unit.height).toFloat(),
        ),
    )

    val canvas = composeRule.onNodeWithTag(BOARD_CANVAS_TAG).getUnclippedBoundsInRoot()
    val expected = DpRect(
        left = canvas.left + Dp(mapped.left / frame.density),
        top = canvas.top + Dp(mapped.top / frame.density),
        right = canvas.left + Dp(mapped.right / frame.density),
        bottom = canvas.top + Dp(mapped.bottom / frame.density),
    )
    val actual = composeRule.onNodeWithTag(boardUnitTag(index)).getUnclippedBoundsInRoot()

    assertEquals("$where: unit left", expected.left.value, actual.left.value, TOLERANCE)
    assertEquals("$where: unit top", expected.top.value, actual.top.value, TOLERANCE)
    assertEquals("$where: unit right", expected.right.value, actual.right.value, TOLERANCE)
    assertEquals("$where: unit bottom", expected.bottom.value, actual.bottom.value, TOLERANCE)
}

private const val TOLERANCE = 1.5f
