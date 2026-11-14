package name.boyle.chris.sgtpuzzles.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePadding

/**
 * Keeps the content of a toolbar clear of a side display cutout and a
 * side navigation bar.
 *
 * The top inset stays with the [com.google.android.material.appbar.AppBarLayout],
 * which already offsets the toolbar. The side insets are read from the
 * window root because the CoordinatorLayout consumes the dispatched ones
 * when it fits the system windows itself.
 */
fun View.applyToolbarSideInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, _ ->
        val bars = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(systemBars() or displayCutout())
        view.updatePadding(
            left = bars?.left ?: 0,
            right = bars?.right ?: 0,
        )
        WindowInsetsCompat.CONSUMED
    }
}
