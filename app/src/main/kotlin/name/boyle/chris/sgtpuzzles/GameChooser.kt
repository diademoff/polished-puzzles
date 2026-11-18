package name.boyle.chris.sgtpuzzles

import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import name.boyle.chris.sgtpuzzles.Utils.sendFeedbackDialog
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.BackendName.Companion.byLowerCase
import name.boyle.chris.sgtpuzzles.backend.GUESS
import name.boyle.chris.sgtpuzzles.backend.KEEN
import name.boyle.chris.sgtpuzzles.backend.LIGHTUP
import name.boyle.chris.sgtpuzzles.backend.NET
import name.boyle.chris.sgtpuzzles.backend.SIGNPOST
import name.boyle.chris.sgtpuzzles.backend.SOLO
import name.boyle.chris.sgtpuzzles.backend.TOWERS
import name.boyle.chris.sgtpuzzles.chooser.ChooserScreen
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CHOOSER_STYLE_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.SAVED_BACKEND
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.STATE_PREFS_NAME
import name.boyle.chris.sgtpuzzles.launch.GameGenerator.Companion.executableIsMissing
import name.boyle.chris.sgtpuzzles.ui.SgTPuzzlesTheme
import name.boyle.chris.sgtpuzzles.ui.SystemColors

/**
 * The game chooser.
 *
 * The screen itself is Compose ([ChooserScreen]); this activity owns the
 * preferences, the night mode state, and the navigation.
 */
class GameChooser : ActivityWithLoadButton(), OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences

    private var compact by mutableStateOf(false)
    private var isNight by mutableStateOf(false)
    private var systemColors by mutableStateOf(false)
    private var starred by mutableStateOf<Set<BackendName>>(emptySet())
    private var current by mutableStateOf<BackendName?>(null)
    private var scrollToken by mutableIntStateOf(0)
    private var resumeTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read the preferences before the early return: onResume still
        // runs after finish() and needs them.
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (executableIsMissing(this)) {
            finish()
            return
        }
        prefs.registerOnSharedPreferenceChangeListener(this)
        migrateChooserStyle()
        compact = isCompact()
        isNight = NightModeHelper.isNight(resources.configuration)
        systemColors = SystemColors.isEnabled(this)
        starred = readStarred()
        setContent {
            SgTPuzzlesTheme(darkTheme = isNight, dynamicColor = systemColors) {
                ChooserScreen(
                    games = BackendName.all.toList(),
                    starred = starred,
                    current = current,
                    compact = compact,
                    isNight = isNight,
                    scrollToken = scrollToken,
                    onGameClick = ::openGame,
                    onStarToggle = ::toggleStarred,
                    onLoad = { loadGame() },
                    onSettings = {
                        startActivity(Intent(this, PrefsActivity::class.java))
                    },
                    onHelp = {
                        startActivity(
                            Intent(this, HelpActivity::class.java)
                                .putExtra(HelpActivity.TOPIC, "index"),
                        )
                    },
                    onFeedback = { sendFeedbackDialog(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTime = System.nanoTime()
        current = getSharedPreferences(STATE_PREFS_NAME, MODE_PRIVATE)
            .getString(SAVED_BACKEND, null)
            ?.let { byLowerCase(it) }
        starred = readStarred()
        compact = isCompact()
        // The setting can change on the settings screen, so read it again
        // instead of remembering the value this screen started with.
        systemColors = SystemColors.isEnabled(this)
        scrollToken++
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isNight = NightModeHelper.isNight(newConfig)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when {
            key == CHOOSER_STYLE_KEY -> compact = isCompact()
            // The starred state can be changed outside this screen.
            key != null && key.startsWith("starred_") -> starred = readStarred()
        }
    }

    /**
     * A tap right after returning from a game is usually the second half
     * of a double tap. It must not open a game.
     */
    private fun openGame(backend: BackendName) {
        if (System.nanoTime() - resumeTime < TOUCH_GUARD_NANOS) return
        startActivity(
            Intent(this, GamePlay::class.java).apply {
                data = Uri.fromParts(GamePlay.OUR_SCHEME, backend.toString(), null)
            },
        )
    }

    private fun toggleStarred(backend: BackendName) {
        if (System.nanoTime() - resumeTime < TOUCH_GUARD_NANOS) return
        prefs.edit { putBoolean(starredKey(backend), !isStarred(backend)) }
        starred = readStarred()
    }

    private fun readStarred(): Set<BackendName> =
        BackendName.all.filterTo(mutableSetOf()) { isStarred(it) }

    private fun isStarred(game: BackendName): Boolean =
        prefs.getBoolean(starredKey(game), game in DEFAULT_STARRED)

    private fun starredKey(game: BackendName) = "starred_$game"

    private fun isCompact() = prefs.getString(CHOOSER_STYLE_KEY, "list") == "grid"

    /** The chooser style used to live in the state preferences. */
    private fun migrateChooserStyle() {
        val state = getSharedPreferences(STATE_PREFS_NAME, MODE_PRIVATE)
        val oldStyle = state.getString(CHOOSER_STYLE_KEY, null) ?: return
        prefs.edit { putString(CHOOSER_STYLE_KEY, oldStyle) }
        state.edit { remove(CHOOSER_STYLE_KEY) }
    }

    companion object {
        private const val TOUCH_GUARD_NANOS = 300_000_000L

        private val DEFAULT_STARRED = setOf(GUESS, KEEN, LIGHTUP, NET, SIGNPOST, SOLO, TOWERS)
    }
}
