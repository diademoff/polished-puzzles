package name.boyle.chris.sgtpuzzles.screenshot

import name.boyle.chris.sgtpuzzles.screenshot.ReferenceCaptureGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level: unit. The chooser golden gate.
 *
 * The gate reads the captured screen, not a configuration value. The
 * three cases must be distinguishable: the reference capture compares
 * the goldens, the reference capture with the sides swapped fails as a
 * rotated display, and every other capture skips.
 */
class ReferenceCaptureGateTest {

    @Test
    fun `the reference size and density compare the goldens`() {
        assertEquals(Verdict.REFERENCE, ReferenceCaptureGate.classify(1080, 2400, 420))
    }

    @Test
    fun `the reference capture with the sides swapped is rotated`() {
        assertEquals(Verdict.ROTATED, ReferenceCaptureGate.classify(2400, 1080, 420))
    }

    @Test
    fun `another size is not the reference device`() {
        assertEquals(Verdict.OTHER, ReferenceCaptureGate.classify(1080, 2340, 420))
    }

    @Test
    fun `the reference size at another density is not the reference device`() {
        assertEquals(Verdict.OTHER, ReferenceCaptureGate.classify(1080, 2400, 480))
    }
}
