package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. Game: fifteen.
 *
 * D-01 in landscape. The board is drawn and laid out in landscape since
 * the shell layout change, so the spoken units must exist after a
 * rotation, keep their order and their text, and still land on the
 * pixels the drawing matrix gives them. The portrait board is the
 * control: the same labels must come back.
 */
@RunWith(AndroidJUnit4::class)
class BoardLandscapeSemanticsTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<GamePlay>? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @After
    fun restore() {
        scenario?.onActivity { it.requestedOrientation = originalOrientation }
        scenario?.close()
        scenario = null
        // A forced landscape outlives the activity while it is the top
        // app, so wait for the natural rotation before the next class
        // reads the screen.
        if (originalOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ) {
            awaitDisplayRotation(Surface.ROTATION_0)
        }
    }

    @Test
    fun theSameUnitsSpeakInLandscape() {
        launchFifteen()
        val portrait = frame()
        assertEquals(16, portrait.units.size)
        assertUnitsLandOnTheDrawing(composeRule, portrait, "portrait fifteen")

        scenario?.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
        // The platform animates the rotation and lays the shell out after
        // the configuration change.
        SystemClock.sleep(ROTATION_SETTLE_MS)
        composeRule.waitForIdle()

        val landscape = frame()
        assertEquals("the unit count changed on rotation", 16, landscape.units.size)
        assertEquals("the spoken labels changed on rotation", portrait.labels, landscape.labels)
        assertUnitsLandOnTheDrawing(composeRule, landscape, "landscape fifteen")

        val board = composeRule.onNodeWithTag(BOARD_TAG).fetchSemanticsNode()
        val shape = board.config[SemanticsProperties.CollectionInfo]
        assertEquals("the board lost its rows on rotation", 4, shape.rowCount)
        assertEquals("the board lost its columns on rotation", 4, shape.columnCount)
        assertEquals(
            "the unit nodes disappeared on rotation",
            16,
            composeRule.onAllNodes(
                SemanticsMatcher("is a board unit") { node ->
                    node.config.getOrNull(SemanticsProperties.TestTag)
                        ?.startsWith("board-unit-") == true
                },
            ).fetchSemanticsNodes().size,
        )
    }

    // ------------------------------------------------------------------

    private fun launchFifteen() {
        GamePlayTestSupport.clearBackendState(context, FIFTEEN)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, FIFTEEN, FIFTEEN_ID),
        )
        scenario = launched
        launched.onActivity { originalOrientation = it.requestedOrientation }
        GamePlayTestSupport.awaitEngine(launched)
        composeRule.waitUntil(NODE_TIMEOUT) {
            composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun frame(): SpeechFrame {
        var frame: SpeechFrame? = null
        scenario?.onActivity { frame = readSpeechFrame(it) }
        return frame ?: error("the game activity is gone")
    }

    private fun awaitOrientation(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + ORIENTATION_TIMEOUT_MS
        var orientation = Configuration.ORIENTATION_UNDEFINED
        while (SystemClock.uptimeMillis() < deadline) {
            scenario?.onActivity { orientation = it.resources.configuration.orientation }
            if (orientation == expected) return
            SystemClock.sleep(50)
        }
        assertEquals("the display did not reach orientation $expected", expected, orientation)
    }

    @Suppress("DEPRECATION")
    private fun awaitDisplayRotation(rotation: Int) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (windowManager.defaultDisplay.rotation == rotation) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the display did not return to rotation $rotation; " +
                "it is ${windowManager.defaultDisplay.rotation}",
        )
    }

    private companion object {
        const val NODE_TIMEOUT = 10_000L
        const val ORIENTATION_TIMEOUT_MS = 10_000L
        const val ROTATION_SETTLE_MS = 1_000L

        /** The frozen seed-12345 4x4 board of the fifteen tests. */
        const val FIFTEEN_ID = "4x4:14,13,3,10,12,5,6,7,1,8,2,11,0,9,4,15"
    }
}
