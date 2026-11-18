package name.boyle.chris.sgtpuzzles

import android.view.View
import android.view.ViewParent
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Assume.assumeNotNull

object TestUtils {
    val fastlaneDeviceTypeOrSkipTest: String
        get() {
            val deviceType = InstrumentationRegistry.getArguments().getString("device_type")
            assumeNotNull(deviceType) // only available under fastlane
            return deviceType!!
        }

    /**
     * The editable view of the text field whose label is [label].
     *
     * A Material text field keeps its label in the [TextInputLayout] as the
     * floating hint, so the label is not a hint of the `EditText` and not a
     * sibling of it; the layout above the view holds it.
     */
    fun hasFieldLabel(label: String): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun matchesSafely(view: View): Boolean {
            var parent: ViewParent? = view.parent
            while (parent != null) {
                if (parent is TextInputLayout) return parent.hint?.toString() == label
                parent = parent.parent
            }
            return false
        }

        override fun describeTo(description: Description) {
            description.appendText("view inside the text field labelled '$label'")
        }
    }
}
