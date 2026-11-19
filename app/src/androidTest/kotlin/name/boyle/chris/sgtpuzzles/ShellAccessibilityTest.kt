package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.backend.FLOOD
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.KotlinGameEngine
import name.boyle.chris.sgtpuzzles.backend.TENTS
import name.boyle.chris.sgtpuzzles.buttons.KEY_LABEL_TAG
import name.boyle.chris.sgtpuzzles.engine.BoardGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Level: instrumentation. The accessibility findings of the shell
 * audits that a device can check without a screen reader:
 *
 * - the status line is a polite live region, and a repeat of the same
 *   text does not reach the view, so it cannot be announced twice;
 * - a letter key is spoken with a word, not a bare letter;
 * - a configuration field carries its own label and is 48dp tall;
 * - a key label stays inside the key at 200% text;
 * - the landscape status line clears the navigation bar.
 *
 * TalkBack's own utterances are D-03 and stay a manual pass.
 */
@RunWith(AndroidJUnit4::class)
class ShellAccessibilityTest {

    /** Finds the key bar composables in the real activity. */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<GamePlay>? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @After
    fun restore() {
        scenario?.let { game ->
            game.onActivity { it.requestedOrientation = originalOrientation }
            game.close()
        }
        scenario = null
        // A forced landscape outlives the activity while it is the top
        // app, and a transposed display breaks the next class.
        if (originalOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            originalOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ) {
            awaitDisplayRotation(Surface.ROTATION_0)
        }
    }

    @Test
    fun theStatusLineIsAPoliteLiveRegion() {
        // Flood has no spoken board, so it keeps the status line as the
        // polite live region of A-04. A game with board units speaks its
        // changes itself; `BoardSemanticsTest` pins that side.
        val game = launch(FLOOD, FLOOD_ID)
        GamePlayTestSupport.awaitEngine(game)
        val before = statusOf(game)
        assertEquals("the game does not start with a status line", "0 / 6 moves", before)
        assertEquals(
            "the status line '$before' is not a polite live region",
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            statusNode(game)?.liveRegion,
        )

        tapFloodTopRight(game)
        val after = awaitStatusChange(game, before)
        assertEquals("the move did not reach the status line", "1 / 6 moves", after)
        assertEquals(
            "the live region was lost after a move to '$after'",
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            statusNode(game)?.liveRegion,
        )
    }

    @Test
    fun anUnchangedStatusIsNotWrittenToTheViewAgain() {
        val game = launch(FLOOD, FLOOD_ID)
        GamePlayTestSupport.awaitEngine(game)
        val status = awaitStatus(game, "0 / 6 moves")
        var writes = 0
        game.onActivity { activity ->
            activity.findViewById<TextView>(R.id.status_bar).addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        writes++
                    }
                },
            )
        }

        // The engine repeats the same text on every redraw and timer
        // tick. A repeat must not reach the view, or the live region
        // would announce it again.
        repeat(3) { game.onActivity { it.setStatus(status) } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("the same status was written to the view again", 0, writes)

        // The positive control: a changed text must reach the view.
        game.onActivity { it.setStatus("Moves: 7") }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("a changed status did not reach the view", 1, writes)
    }

    @Test
    fun theLetterKeysAreNotSpokenAsBareLetters() {
        val game = launch(GUESS, GUESS_ID)
        GamePlayTestSupport.awaitEngine(game)

        val descriptions = composeRule.onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        val bareLetters = descriptions.filter { it.length == 1 && it[0].isLetter() }
        assertTrue("these keys are spoken as bare letters: $bareLetters", bareLetters.isEmpty())
        // Guess calls its L key "Labels". The name is the proof that the
        // key is spoken with the game's own word.
        assertTrue("no key is spoken as 'Labels': $descriptions", "Labels" in descriptions)
    }

    @Test
    fun theConfigurationFieldsCarryTheirOwnLabelAndAre48DpTall() {
        val game = launch(TENTS, TENTS_ID)
        GamePlayTestSupport.awaitEngine(game)
        openCustomConfiguration(TENTS_DIALOG)

        var fields = emptyList<EditText>()
        var choices: Spinner? = null
        onView(withText(TENTS_DIALOG)).inRoot(isDialog()).check { view, _ ->
            val root = view.rootView as ViewGroup
            fields = root.descendants().filterIsInstance<EditText>()
            choices = root.descendants().filterIsInstance<Spinner>().firstOrNull()
        }

        assertEquals(
            "the text rows are not labelled Width and Height: ${fields.map { it.hint }}",
            listOf("Width", "Height"),
            fields.map { it.hint?.toString() },
        )
        for (field in fields) {
            val name = field.hint.toString()
            val node = field.createAccessibilityNodeInfo()
            assertNotNull("the $name field has no label in its own node", node.hintText)
            assertEquals("the $name field does not expose its label", name, node.hintText.toString())
            assertTrue(
                "the $name field is ${field.height}px tall, below 48dp (${dp(48f)}px); " +
                    "minimumHeight ${field.minimumHeight}, parent " +
                    "${(field.parent as View).height}, hint '${field.hint}'",
                field.height >= dp(48f),
            )
        }
        assertNotNull("the dialog has no choices row", choices)
        assertEquals(
            "the choices row has no label in its own node",
            "Difficulty",
            choices?.contentDescription,
        )
    }

    @Test
    fun theKeyLabelStaysInsideTheKeyAtTwoHundredPercentText() {
        val originalScale = fontScale()
        launchWithFontScale(originalScale, TWO_HUNDRED_PERCENT)
        try {
            assertEquals(
                "the test did not switch to 200% text",
                TWO_HUNDRED_PERCENT,
                context.resources.configuration.fontScale,
                0.01f,
            )
            val density = context.resources.displayMetrics.density
            val keySizeDp = context.resources.getDimension(R.dimen.keySize) / density
            val labels = composeRule.onAllNodesWithTag(KEY_LABEL_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("the key bar of keen has no text key", labels.isNotEmpty())
            for (label in labels) {
                val layouts = mutableListOf<TextLayoutResult>()
                label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                assertTrue("the key label has no text layout", layouts.isNotEmpty())
                // A size in sp is that many dp once the text scale is
                // applied, which is what the key must hold. An
                // unclamped 24sp label is the full 48dp at 200%.
                val fontSizeDp = layouts.first().layoutInput.style.fontSize.value *
                    context.resources.configuration.fontScale
                assertTrue(
                    "a key label is $fontSizeDp dp of text in a ${keySizeDp}dp key at 200% text",
                    fontSizeDp <= keySizeDp * LABEL_ROOM,
                )
            }
        } finally {
            setFontScale(originalScale)
            awaitFontScale(originalScale)
        }
    }

    @Test
    fun theLandscapeStatusLineClearsTheNavigationBar() {
        val game = launch(FIFTEEN, GamePlayTestSupport.SOLVED_4X4)
        GamePlayTestSupport.awaitEngine(game)
        val before = statusOf(game)

        // Portrait is the control: the status line sits above the key
        // bar, which reserves the inset itself.
        assertStatusClearsNavigationBar(game, "portrait")

        game.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitOrientation(ORIENTATION_LANDSCAPE)
        // The platform animates the rotation and the layout settles
        // after the configuration change.
        SystemClock.sleep(ROTATION_SETTLE_MS)

        assertStatusClearsNavigationBar(game, "landscape")
        // The status line is a new view after the rotation and must get
        // the same text again.
        assertEquals("the status line lost its text on rotation", before, statusOf(game))
    }

    /**
     * The text of the status line must end above the bottom system bar.
     *
     * The comparison is against the window insets of the root view, so
     * it holds whether or not the window itself is inset, and it does
     * not assume where the shell puts the padding.
     */
    private fun assertStatusClearsNavigationBar(game: ActivityScenario<GamePlay>, where: String) {
        game.onActivity { activity ->
            val status = activity.findViewById<View>(R.id.status_bar)
            val root = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(root)
            assertNotNull("the $where window reports no insets", insets)
            val navigationBar = insets!!.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

            val statusLocation = IntArray(2)
            status.getLocationOnScreen(statusLocation)
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val statusTextBottom = statusLocation[1] + status.height - status.paddingBottom
            val lowestAllowed = rootLocation[1] + root.height - navigationBar
            assertTrue(
                "the $where status line ends at ${statusTextBottom}px, under the navigation " +
                    "bar ($navigationBar px, lowest allowed $lowestAllowed)",
                statusTextBottom <= lowestAllowed,
            )
        }
    }

    /** The accessibility node of the status line. */
    private fun statusNode(game: ActivityScenario<GamePlay>): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = null
        game.onActivity { node = it.findViewById<View>(R.id.status_bar).createAccessibilityNodeInfo() }
        return node
    }

    private fun statusOf(game: ActivityScenario<GamePlay>): String {
        var text = ""
        game.onActivity { text = GamePlayTestSupport.statusText(it) }
        return text
    }

    private fun awaitStatusChange(
        game: ActivityScenario<GamePlay>,
        before: String,
        deadlineMs: Long = 5_000,
    ): String {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var text = before
        while (SystemClock.uptimeMillis() < deadline) {
            text = statusOf(game)
            if (text.isNotEmpty() && text != before) return text
            SystemClock.sleep(50)
        }
        throw AssertionError("the status line is still '$before' after the move")
    }

    /** Waits until the status line shows [expected]. */
    private fun awaitStatus(
        game: ActivityScenario<GamePlay>,
        expected: String,
        deadlineMs: Long = 10_000,
    ): String {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var text = ""
        while (SystemClock.uptimeMillis() < deadline) {
            text = statusOf(game)
            if (text == expected) return text
            SystemClock.sleep(50)
        }
        throw AssertionError("the status line is '$text', not '$expected'")
    }

    /**
     * A tap in the top-right cell of the frozen flood game, which is the
     * other colour, so the fill and the move count change.
     */
    private fun tapFloodTopRight(game: ActivityScenario<GamePlay>) {
        val board = awaitBoard(game)
        onView(withId(R.id.game_view)).perform(
            GeneralClickAction(
                Tap.SINGLE,
                CoordinatesProvider { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val density = view.width.toFloat() / (view as GameView).wDip
                    floatArrayOf(
                        location[0] + density * (board.originX + 1.5f * board.tileSize),
                        location[1] + density * (board.originY + 0.5f * board.tileSize),
                    )
                },
                Press.FINGER,
                0,
                0,
            ),
        )
    }

    /**
     * Waits for the resize that follows the launch. Until it lands, the
     * engine still reports the preferred geometry, which is not the
     * geometry the view uses.
     */
    private fun awaitBoard(game: ActivityScenario<GamePlay>): BoardGeometry {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var board: BoardGeometry? = null
            game.onActivity { activity ->
                val view = activity.findViewById<View>(R.id.game_view)
                val engine = activity.gameEngine as? KotlinGameEngine
                if (view.width > 0 && view.height > 0 && engine != null) {
                    board = engine.boardGeometry
                }
            }
            board?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("the board did not reach a laid-out geometry")
    }

    private fun openCustomConfiguration(title: String) {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
        onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun launch(backend: BackendName, gameId: String): ActivityScenario<GamePlay> {
        GamePlayTestSupport.clearBackendState(context, backend)
        val launched = ActivityScenario.launch<GamePlay>(GamePlayTestSupport.intent(context, backend, gameId))
        scenario = launched
        launched.onActivity { originalOrientation = it.requestedOrientation }
        return launched
    }

    /** Launches a game at [scale] text and restores [original] on failure. */
    private fun launchWithFontScale(original: Float, scale: Float) {
        try {
            setFontScale(scale)
            awaitFontScale(scale)
            GamePlayTestSupport.awaitEngine(launch(KEEN, KEEN_ID))
        } catch (e: Throwable) {
            setFontScale(original)
            awaitFontScale(original)
            throw e
        }
    }

    private fun ViewGroup.descendants(): List<View> = buildList {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            add(child)
            if (child is ViewGroup) addAll(child.descendants())
        }
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun awaitOrientation(orientation: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var current = -1
            scenario?.onActivity { current = it.resources.configuration.orientation }
            if (current == orientation) return
            SystemClock.sleep(50)
        }
        throw AssertionError("the activity did not reach orientation $orientation")
    }

    private fun awaitDisplayRotation(rotation: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (displayRotation() == rotation) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the display did not return to rotation $rotation; it is ${displayRotation()}",
        )
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.defaultDisplay.rotation
    }

    private fun fontScale(): Float =
        shell("settings get system font_scale").trim().toFloatOrNull() ?: 1f

    private fun setFontScale(scale: Float) {
        shell("settings put system font_scale $scale")
    }

    /** Waits until this process sees the new text scale in its resources. */
    private fun awaitFontScale(expected: Float) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (context.resources.configuration.fontScale == expected) return
            SystemClock.sleep(50)
        }
        throw AssertionError(
            "the text scale is ${context.resources.configuration.fontScale}, not $expected",
        )
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { file ->
            FileInputStream(file.fileDescriptor).readBytes().decodeToString()
        }
    }

    private companion object {
        const val TWO_HUNDRED_PERCENT = 2.0f

        /** A frozen Guess game: two colours, two pegs, an empty board. */
        const val GUESS_ID = "c2p2g2Bm:c2ab"

        /** A frozen 3x3 flood game with a six-move limit. */
        const val FLOOD_ID = "3x3:121212121,6"

        /** A frozen Keen game with a key bar of digits. */
        const val KEEN_ID = "3:_baa_3a,m6s1m3a3"

        /** The frozen 8x8de tents game of seed 12345. */
        const val TENTS_ID = "8x8de:ae__ijcecgbcd,3,1,2,1,1,1,2,1,2,1,2,1,1,1,1,3"

        const val TENTS_DIALOG = "Tents configuration"

        /** The platform rotation animation, during which touches are dropped. */
        const val ROTATION_SETTLE_MS = 600L

        /** The share of the key a label may fill and still leave a margin. */
        const val LABEL_ROOM = 0.8f
    }
}
