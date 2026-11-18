package name.boyle.chris.sgtpuzzles

import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import name.boyle.chris.sgtpuzzles.backend.FIFTEEN
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CATEGORY_THIS_GAME
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CHOOSER_STYLE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CONTROLS_REMINDERS_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.NIGHT_MODE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.ORIENTATION_KEY
import name.boyle.chris.sgtpuzzles.engine.Fifteen
import name.boyle.chris.sgtpuzzles.ui.SystemColors
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Level: instrumentation. The real settings activity, not a stub.
 *
 * The global rows come from `res/xml/preferences.xml`; the per-game
 * rows of an enabled Kotlin backend come from the engine schema. The
 * page of `fifteen` is therefore the interesting one: it must show
 * exactly what `Fifteen.prefs` declares, plus the shell's own arrow-key
 * switch.
 */
@RunWith(AndroidJUnit4::class)
class PrefsActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private var originalReminders: Boolean? = null

    @Before
    fun rememberToggle() {
        originalReminders =
            if (prefs.contains(CONTROLS_REMINDERS_KEY)) {
                prefs.getBoolean(CONTROLS_REMINDERS_KEY, true)
            } else {
                null
            }
    }

    @After
    fun restoreToggle() {
        prefs.edit().apply {
            if (originalReminders == null) {
                remove(CONTROLS_REMINDERS_KEY)
            } else {
                putBoolean(CONTROLS_REMINDERS_KEY, originalReminders!!)
            }
        }.commit()
    }

    @Test
    fun theGlobalRowsExistAndAToggleWritesItsKey() {
        ActivityScenario.launch<PrefsActivity>(
            Intent(context, PrefsActivity::class.java),
        ).use { scenario ->
            scrollToPreference(scenario, CHOOSER_STYLE_KEY)
            onView(withText(R.string.chooserStyle)).check(matches(isDisplayed()))
            scrollToPreference(scenario, ORIENTATION_KEY)
            onView(withText(R.string.orientation)).check(matches(isDisplayed()))
            scrollToPreference(scenario, NIGHT_MODE_KEY)
            onView(withText(R.string.nightMode)).check(matches(isDisplayed()))

            scrollToPreference(scenario, CONTROLS_REMINDERS_KEY)
            val before = prefs.getBoolean(CONTROLS_REMINDERS_KEY, true)
            onView(withText(R.string.controlsReminders)).perform(click())
            assertEquals(
                "the switch must write '$CONTROLS_REMINDERS_KEY'",
                !before,
                prefs.getBoolean(CONTROLS_REMINDERS_KEY, true),
            )

            scrollToPreference(scenario, ADVANCED_SETTINGS)
            onView(withText(R.string.advancedSettings)).check(matches(isDisplayed())).perform(click())
            scrollToPreference(scenario, DISPLAY_FULLSCREEN)
            onView(withText(R.string.fullscreen)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun theFifteenPageIsTheEngineSchema() {
        val intent = Intent(context, PrefsActivity::class.java)
            .putExtra(PrefsActivity.PrefsMainFragment.BACKEND_EXTRA, FIFTEEN.toString())
        ActivityScenario.launch<PrefsActivity>(intent).use { scenario ->
            for (item in Fifteen.prefs) {
                scrollToPreference(scenario, item.kw)
                onView(withText(item.name)).check(matches(isDisplayed()))
            }

            // Exactly the engine rows, then the one switch that the
            // shell adds for every game with arrows. A row from another
            // game's schema would fail here.
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(android.R.id.content) as PreferenceFragmentCompat
                val category =
                    fragment.findPreference<PreferenceCategory>(CATEGORY_THIS_GAME)!!
                val titles = (0 until category.preferenceCount)
                    .map { category.getPreference(it).title.toString() }
                assertEquals(
                    Fifteen.prefs.map { it.name } + context.getString(R.string.showArrowKeys),
                    titles,
                )
            }

            // The display page keeps the midend's own item, which the
            // engine schema does not declare.
            scrollToPreference(scenario, ADVANCED_SETTINGS)
            onView(withText(R.string.advancedSettings)).perform(click())
            scrollToPreference(scenario, ONE_KEY_SHORTCUTS)
            onView(withText("Keyboard shortcuts without Ctrl")).check(matches(isDisplayed()))
        }
    }

    /**
     * The shell palette setting is an interactive row with a Material 3
     * switch, it is offered only where the platform has a palette, and the
     * switch writes the stored key.
     */
    @Test
    fun theSystemColorsRowIsAMaterialSwitch() {
        val hadSetting = prefs.contains(SystemColors.KEY)
        val before = prefs.getBoolean(SystemColors.KEY, true)
        try {
            ActivityScenario.launch<PrefsActivity>(
                Intent(context, PrefsActivity::class.java),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager
                        .findFragmentById(android.R.id.content) as PreferenceFragmentCompat
                    assertEquals(
                        "the row must be offered exactly where a palette exists",
                        SystemColors.isAvailable(),
                        fragment.findPreference<Preference>(SystemColors.KEY)!!.isVisible,
                    )
                }

                scrollToPreference(scenario, SystemColors.KEY)
                onView(withText(R.string.systemColors)).check(matches(isDisplayed()))
                if (!SystemColors.isAvailable()) return@use

                // The switch sits in the row's widget frame: two levels
                // above it is the row that holds this title.
                onView(
                    allOf(
                        isAssignableFrom(MaterialSwitch::class.java),
                        withParent(withParent(hasDescendant(withText(R.string.systemColors)))),
                    ),
                ).check(matches(isDisplayed()))

                onView(withText(R.string.systemColors)).perform(click())
                assertEquals(
                    "the switch must write '${SystemColors.KEY}'",
                    !before,
                    prefs.getBoolean(SystemColors.KEY, true),
                )
            }
        } finally {
            prefs.edit().apply {
                if (hadSetting) putBoolean(SystemColors.KEY, before) else remove(SystemColors.KEY)
            }.commit()
        }
    }

    /** Scrolls the preference list without a gesture, so no row is off screen. */
    private fun scrollToPreference(scenario: ActivityScenario<PrefsActivity>, key: String) {
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager
                .findFragmentById(android.R.id.content) as PreferenceFragmentCompat
            fragment.scrollToPreference(key)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    companion object {
        private const val ADVANCED_SETTINGS = "advancedSettings"
        private const val DISPLAY_FULLSCREEN = "fullscreen"
        private const val ONE_KEY_SHORTCUTS = "one-key-shortcuts"
    }
}
