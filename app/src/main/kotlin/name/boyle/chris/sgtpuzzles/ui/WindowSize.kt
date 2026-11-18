package name.boyle.chris.sgtpuzzles.ui

import android.app.Dialog
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import name.boyle.chris.sgtpuzzles.R

/**
 * The shell's window size classes, in one place.
 *
 * The classes are the Material 3 breakpoints: compact is a window below
 * 600dp of width, medium is 600dp to 839dp, and expanded is 840dp and
 * wider. The class is read from the [Configuration] of the activity,
 * which is the size of the app window, so a View screen and a Compose
 * screen reach the same answer through this one function. No screen
 * compares a width with a breakpoint of its own.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun windowSizeClass(configuration: Configuration): WindowSizeClass =
    WindowSizeClass.calculateFromSize(
        DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp),
    )

/** True from the medium width class up: the shell has room to be generous. */
val WindowSizeClass.isMediumOrWider: Boolean
    get() = widthSizeClass != WindowWidthSizeClass.Compact

/**
 * Caps a view at the reading measure of [R.dimen.shell_content_max_width]
 * and centres it while the window is wider than that measure. A narrower
 * window keeps the full width, so compact phones are unchanged.
 *
 * The cap is applied again on every layout, so a resize, a split-screen
 * drag, or a density change keeps the rule. Only the shell's own list and
 * web content call this; the game board is never capped.
 */
fun View.capReadingWidth() {
    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        // A layout param change during a layout pass would be dropped, so
        // the cap is applied in the next message.
        view.post { view.applyReadingWidth() }
    }
}

private fun View.applyReadingWidth() {
    val available = (parent as? View)?.width ?: return
    if (available <= 0) return
    val cap = resources.getDimensionPixelSize(R.dimen.shell_content_max_width)
    val width = if (available > cap) cap else ViewGroup.LayoutParams.MATCH_PARENT
    val params = layoutParams ?: return
    if (params.width == width) return
    params.width = width
    when (params) {
        is FrameLayout.LayoutParams -> params.gravity = Gravity.CENTER_HORIZONTAL
        is CoordinatorLayout.LayoutParams -> params.gravity = Gravity.CENTER_HORIZONTAL
    }
    layoutParams = params
}

/**
 * Caps a dialog at the Material 3 maximum width of
 * [R.dimen.shell_dialog_max_width]. A compact window keeps the platform
 * dialog width, because the cap is wider than the window there.
 */
fun Dialog.capReadingWidth() {
    val window = window ?: return
    val sizeClass = windowSizeClass(context.resources.configuration)
    if (!sizeClass.isMediumOrWider) return
    window.setLayout(
        context.resources.getDimensionPixelSize(R.dimen.shell_dialog_max_width),
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
