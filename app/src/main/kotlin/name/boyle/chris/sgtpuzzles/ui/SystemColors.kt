package name.boyle.chris.sgtpuzzles.ui

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import name.boyle.chris.sgtpuzzles.R

/**
 * The user setting that takes the shell palette from the wallpaper.
 *
 * Android 12 and later provide a system palette; `docs/DESIGN.md` section
 * 3 allows the user to choose it. It applies to the shell only: the game
 * board palette comes from the engine and never follows the theme.
 *
 * The XML shell gets the palette as a theme overlay, which a theme can
 * apply once, when the screen is created. The Compose shell builds its
 * scheme directly (see `SgTPuzzlesTheme`) and needs no overlay.
 */
object SystemColors {

    /** The stored key of the setting. */
    const val KEY = "systemColors"

    /** Dynamic color exists from Android 12. */
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** True when the user asked for the wallpaper palette and the platform has one. */
    fun isEnabled(activity: Activity): Boolean =
        isAvailable() && PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean(KEY, true)

    /** Applies the wallpaper palette to this screen, if the user asked for it. */
    fun applyTo(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!isEnabled(activity)) return
        applyOverlay(activity)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyOverlay(activity: Activity) {
        activity.theme.applyStyle(R.style.ThemeOverlay_SgTPuzzles_SystemColors, true)
    }
}
