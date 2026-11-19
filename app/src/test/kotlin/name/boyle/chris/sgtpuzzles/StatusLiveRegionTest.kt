package name.boyle.chris.sgtpuzzles

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status-line live region of D-01 section 6, without a device.
 * Level: unit.
 *
 * After the eighth enablement wave every ported backend has board units,
 * so a launchable game always takes the silent branch. This pins both
 * branches, so the polite fallback for a future game without the seam
 * does not rot.
 */
class StatusLiveRegionTest {

    @Test
    fun `a unit board keeps the status line silent`() {
        assertEquals(
            View.ACCESSIBILITY_LIVE_REGION_NONE,
            statusLiveRegion("Moves: 1", boardHasUnits = true),
        )
    }

    @Test
    fun `a board without units keeps the polite line`() {
        assertEquals(
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            statusLiveRegion("Moves: 1", boardHasUnits = false),
        )
    }

    @Test
    fun `an empty status is not a message`() {
        assertEquals(
            View.ACCESSIBILITY_LIVE_REGION_NONE,
            statusLiveRegion("", boardHasUnits = false),
        )
    }
}
