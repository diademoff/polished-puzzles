package name.boyle.chris.sgtpuzzles

import android.content.SharedPreferences
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.isEmpty
import androidx.preference.minusAssign
import androidx.preference.plusAssign
import name.boyle.chris.sgtpuzzles.NightModeHelper.ActivityWithNightMode
import name.boyle.chris.sgtpuzzles.Utils.copyVersionToClipboard
import name.boyle.chris.sgtpuzzles.Utils.sendFeedbackDialog
import name.boyle.chris.sgtpuzzles.backend.BRIDGES
import name.boyle.chris.sgtpuzzles.backend.BackendName
import name.boyle.chris.sgtpuzzles.backend.BackendName.Companion.byLowerCase
import name.boyle.chris.sgtpuzzles.backend.GameEngineImpl
import name.boyle.chris.sgtpuzzles.backend.KotlinGames
import name.boyle.chris.sgtpuzzles.backend.KotlinPrefsEngine
import name.boyle.chris.sgtpuzzles.backend.UNEQUAL
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder
import name.boyle.chris.sgtpuzzles.config.ConfigBuilder.Event.CFG_PREFS
import name.boyle.chris.sgtpuzzles.config.ConfigPreferencesBuilder
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.BRIDGES_SHOW_H_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CATEGORY_BUTTON_PRESSES
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CATEGORY_CHOOSER
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CATEGORY_THIS_GAME
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.CATEGORY_THIS_GAME_DISPLAY_AND_INPUT
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LATIN_SHOW_M_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.LONG_PRESS_TIMEOUT
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.MOUSE_BACK_KEY
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.PLACEHOLDER_SEND_FEEDBACK
import name.boyle.chris.sgtpuzzles.config.PrefsConstants.UNEQUAL_SHOW_H_KEY
import name.boyle.chris.sgtpuzzles.ui.SystemColors
import name.boyle.chris.sgtpuzzles.ui.applyToolbarSideInsets
import name.boyle.chris.sgtpuzzles.ui.capReadingWidth

class PrefsActivity : ActivityWithNightMode(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    /**
     * The XML screens get the shell palette from their theme, which the
     * activity reads when it is created. A change of that setting
     * therefore rebuilds this screen; the game in the background is not
     * touched.
     */
    private val systemColorsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SystemColors.KEY) recreate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PrefsMainFragment())
                .commit()
        } else {
            title = savedInstanceState.getCharSequence(TITLE_TAG)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setTitle(R.string.Settings)
            }
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(systemColorsListener)
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(systemColorsListener)
        super.onDestroy()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(TITLE_TAG, title)
    }

    override fun onSupportNavigateUp(): Boolean {
        return supportFragmentManager.popBackStackImmediate() || super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment!!
        )
        fragment.arguments = args
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
        title = pref.title
        return true
    }

    abstract class CurrentGamePrefFragment : PreferenceFragmentCompat() {

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val root = super.onCreateView(inflater, container, savedInstanceState)
            val appCompatActivity = activity as AppCompatActivity
            val toolbar = root.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            toolbar.applyToolbarSideInsets()
            appCompatActivity.setSupportActionBar(toolbar)
            appCompatActivity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            return root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // On a medium or expanded window the rows keep the shell
            // reading measure instead of one long line across the screen.
            listView.capReadingWidth()
        }

        fun onCreateCurrentGamePrefs(backend: BackendName?, thisGameCategory: PreferenceCategory) {
            if (backend == null) {
                preferenceScreen -= thisGameCategory
            } else {
                thisGameCategory.title = backend.displayName
                if (KotlinGames.handles(backend)) {
                    // The Kotlin page comes from the engine schema, so it
                    // creates no midend and generates no game.
                    val engine = KotlinPrefsEngine.forBackend(backend, requireContext())
                    engine.configEvent(
                        CFG_PREFS.jni,
                        ConfigPreferencesBuilder(
                            thisGameCategory,
                            requireContext(),
                            engine,
                            backend,
                            engine::savePrefs,
                        ),
                    )
                } else {
                    with(GameEngineImpl.forPreferencesOnly(backend, requireContext())) {
                        configEvent(
                            CFG_PREFS.jni,
                            ConfigPreferencesBuilder(
                                thisGameCategory,
                                requireContext(),
                                this as ConfigBuilder.EngineCallbacks,
                                backend,
                                this::savePrefs,
                            ),
                        )
                    }
                }
            }
        }

        protected fun requirePref(key: CharSequence): Preference = findPreference(key)!!

        protected fun requireCategory(key: CharSequence): PreferenceCategory = findPreference(key)!!
    }

    class PrefsMainFragment : CurrentGamePrefFragment() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.preferences)
            val backend = byLowerCase(requireActivity().intent.getStringExtra(BACKEND_EXTRA))
            val chooserCategory = requireCategory(CATEGORY_CHOOSER)
            val thisGameCategory = requireCategory(CATEGORY_THIS_GAME)
            onCreateCurrentGamePrefs(backend, thisGameCategory)
            if (backend != null) {
                preferenceScreen -= chooserCategory
                addGameSpecificAndroidPreferences(backend, thisGameCategory)
            }
            requirePref("about_content").apply {
                summary = String.format(getString(R.string.about_content), BuildConfig.VERSION_NAME)
                setOnPreferenceClickListener {
                    copyVersionToClipboard(requireContext())
                    true
                }
            }
            requirePref(PLACEHOLDER_SEND_FEEDBACK).setOnPreferenceClickListener {
                sendFeedbackDialog(requireContext())
                true
            }
            requirePref(SystemColors.KEY).isVisible = SystemColors.isAvailable()
        }

        private fun addGameSpecificAndroidPreferences(
            whichBackend: BackendName,
            thisGameCategory: PreferenceCategory
        ) {
            if (!whichBackend.isLatin)    thisGameCategory -= requirePref(LATIN_SHOW_M_KEY)
            if (whichBackend !== BRIDGES) thisGameCategory -= requirePref(BRIDGES_SHOW_H_KEY)
            if (whichBackend !== UNEQUAL) thisGameCategory -= requirePref(UNEQUAL_SHOW_H_KEY)
            thisGameCategory += SwitchPreferenceCompat(requireContext()).apply {
                order = 1000  // after upstream prefs, before XML prefs
                isIconSpaceReserved = false
                key = GamePlay.getArrowKeysPrefName(whichBackend, resources.configuration)
                setDefaultValue(GamePlay.getArrowKeysDefault(whichBackend, resources))
                setTitle(R.string.showArrowKeys)
                if (whichBackend.isLatin) setSummary(R.string.arrowKeysLatinSummary)
            }
        }

        companion object {
            const val BACKEND_EXTRA = "backend"
        }
    }

    class PrefsDisplayAndInputFragment : CurrentGamePrefFragment() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_display_and_input, rootKey)
            val backend = byLowerCase(requireActivity().intent.getStringExtra(PrefsMainFragment.BACKEND_EXTRA))
            val thisGameCategory = requireCategory(CATEGORY_THIS_GAME_DISPLAY_AND_INPUT)
            onCreateCurrentGamePrefs(backend, thisGameCategory)
            if (thisGameCategory.isEmpty()) preferenceScreen -= thisGameCategory
            if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
                requirePref(MOUSE_BACK_KEY).isVisible = false
            }
            requireCategory(CATEGORY_BUTTON_PRESSES) += SeekBarPreference(requireContext()).apply {
                order = 0
                isIconSpaceReserved = false
                key = LONG_PRESS_TIMEOUT
                setTitle(R.string.longPressTimeout)
                min = 100
                max = 2000
                seekBarIncrement = 50
                showSeekBarValue = true
                // Calling this after the pref is inflated from XML doesn't work :-( hence creating the pref here
                setDefaultValue(ViewConfiguration.getLongPressTimeout())
            }
        }

    }

    companion object {
        private const val TITLE_TAG = "prefsActivityTitle"
    }
}
