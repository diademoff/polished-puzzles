package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.MINES
import name.boyle.chris.sgtpuzzles.backend.UNRULY
import name.boyle.chris.sgtpuzzles.render.BOARD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. Games: fifteen, mines, and unruly.
 *
 * D-01's geometry guard: the cell node of the game cursor sits on the
 * pixels of the rectangle the game draws for that cell, through the
 * render matrix. Wave 1 shipped nodes in game dp without the matrix,
 * which put the whole board 2.6x off on the Pixel 4a 5G; this test
 * fails on that code.
 *
 * Fifteen always has a cursor (the gap). Mines and unruly reveal theirs
 * with one arrow key, the same key a player presses.
 */
@RunWith(AndroidJUnit4::class)
class BoardGeometryParityTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<GamePlay>? = null

    @After
    fun close() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun fifteenCellsLandOnTheDrawing() {
        val engine = launch(FIFTEEN, FIFTEEN_ID)
        assertTrue("fifteen has no drawing cursor", !engine.cursorLocation.isEmpty)
        assertUnitsLandOnTheDrawing(composeRule, frame(), "fifteen")
    }

    @Test
    fun minesCellsLandOnTheDrawing() {
        val engine = launch(MINES, MINES_ID)
        pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("mines did not reveal its cursor", !engine.cursorLocation.isEmpty)
        val frame = frame()
        assertEquals(
            "mines does not mark exactly one cursor unit: ${frame.labels}",
            1,
            frame.units.count { it.text.endsWith(", cursor") },
        )
        assertUnitsLandOnTheDrawing(composeRule, frame, "mines")
    }

    @Test
    fun unrulyCellsLandOnTheDrawing() {
        val engine = launch(UNRULY, UNRULY_ID)
        pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("unruly did not reveal its cursor", !engine.cursorLocation.isEmpty)
        assertUnitsLandOnTheDrawing(composeRule, frame(), "unruly")
    }

    // ------------------------------------------------------------------

    private fun launch(backend: BackendName, gameId: String): KotlinGameEngine {
        GamePlayTestSupport.clearBackendState(context, backend)
        val launched = ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, backend, gameId),
        )
        scenario = launched
        val engine = GamePlayTestSupport.awaitEngine(launched)
        composeRule.waitUntil(NODE_TIMEOUT) {
            composeRule.onAllNodesWithTag(BOARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        return engine
    }

    private fun pressKey(key: Int) {
        onView(withId(R.id.game_view)).perform(ViewActions.pressKey(key))
        composeRule.waitForIdle()
    }

    private fun frame(): SpeechFrame {
        var frame: SpeechFrame? = null
        scenario?.onActivity { frame = readSpeechFrame(it) }
        return frame ?: error("the game activity is gone")
    }

    private companion object {
        const val NODE_TIMEOUT = 10_000L

        /** The frozen seed-12345 4x4 board of the fifteen tests. */
        const val FIFTEEN_ID = "4x4:14,13,3,10,12,5,6,7,1,8,2,11,0,9,4,15"

        /** The frozen 9x9 mines board, before the first uncover. */
        const val MINES_ID =
            "9x9:r10,u,8cb2237d0679ca88db6464eac60da9634551396400a51f3f48415c7d4e8908980d443c29c69b60c9c" +
                "aefef1451662ab6b8c65888654a119724096b8402"

        /** The frozen 8x8 unruly board of the spoken table. */
        const val UNRULY_ID = "8x8:bEACHcBdBDEalcbAbbBa"
    }
}
