package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import name.boyle.chris.sgtpuzzles.backend.SIGNPOST
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The controls of the configuration dialogs are
 * Material 3 controls, and they still carry the value into the engine.
 *
 * The path is the real one: the type menu opens the dialog, the dialog
 * applies the C configuration items on OK.
 */
@RunWith(AndroidJUnit4::class)
class ConfigDialogControlsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A custom size is a Material text field with the label on the field. */
    @Test
    fun theCustomSizeFieldIsAMaterialTextField() {
        GamePlayTestSupport.clearFifteenState(context)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, "4x4"),
        ).use { scenario ->
            GamePlayTestSupport.awaitEngine(scenario) { it.gameId.startsWith("4x4:") }

            openCustomDialog()
            onView(withText("Fifteen configuration")).inRoot(isDialog())
                .check(matches(isDisplayed()))
            for (label in listOf("Width", "Height")) {
                onView(
                    allOf(
                        isAssignableFrom(TextInputEditText::class.java),
                        TestUtils.hasFieldLabel(label),
                    ),
                ).inRoot(isDialog()).check(matches(isDisplayed()))
            }
        }
    }

    /** A custom boolean is a Material checkbox, and its value reaches the engine. */
    @Test
    fun theCustomCheckboxIsAMaterialCheckboxThatReachesTheEngine() {
        GamePlayTestSupport.clearBackendState(context, SIGNPOST)
        ActivityScenario.launch<GamePlay>(
            GamePlayTestSupport.intent(context, SIGNPOST, "4x4"),
        ).use { scenario ->
            assertEquals("4x4", awaitParams(scenario))

            openCustomDialog()
            val checkbox = onView(
                allOf(
                    isAssignableFrom(MaterialCheckBox::class.java),
                    withText(START_IN_CORNERS_LABEL),
                ),
            ).inRoot(isDialog())
            checkbox.check(matches(isDisplayed())).perform(click())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())

            // The dialog writes the boolean into the params: "4x4c" is the
            // same board with "Start and end in corners" set.
            assertEquals("4x4c", awaitParams(scenario, "4x4c"))
        }
    }

    /** The live params of the engine; Signpost is a C backend, not a Kotlin engine. */
    private fun awaitParams(
        scenario: ActivityScenario<GamePlay>,
        expected: String? = null,
        deadlineMs: Long = 10_000,
    ): String? {
        val deadline = SystemClock.uptimeMillis() + deadlineMs
        var params: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { params = it.gameEngine.currentParams }
            if (expected == null || params == expected) return params
            SystemClock.sleep(50)
        }
        throw AssertionError("the params are '$params', not '$expected'")
    }

    private fun openCustomDialog() {
        onView(withId(R.id.type_menu)).perform(click())
        onView(withText(R.string.Custom_)).perform(click())
    }

    private companion object {
        /** The engine's own label of the boolean item. */
        const val START_IN_CORNERS_LABEL = "Start and end in corners"
    }
}
