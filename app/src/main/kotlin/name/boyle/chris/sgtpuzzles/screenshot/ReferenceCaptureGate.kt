package name.boyle.chris.sgtpuzzles.screenshot

/**
 * Decides whether a captured screen can be compared with the chooser
 * goldens.
 *
 * The reference display is the "Screen" row in `tools/perf/baseline.md`:
 * 1080x2400 at 420 dpi. The goldens are recorded at that size and
 * density, so another display cannot be compared without new goldens.
 * A capture at the reference density with the sides swapped is the
 * reference display left in landscape. That is a test setup mistake, so
 * it must fail instead of skipping the goldens.
 *
 * The function is pure, so the three cases are checked on the JVM in
 * `ReferenceCaptureGateTest`.
 */
internal object ReferenceCaptureGate {

    const val WIDTH = 1080
    const val HEIGHT = 2400
    const val DENSITY_DPI = 420

    enum class Verdict {
        /** The capture is the reference: compare the goldens. */
        REFERENCE,

        /** The reference display is rotated: fail, do not skip. */
        ROTATED,

        /** Another device: the goldens do not apply. */
        OTHER,
    }

    fun classify(width: Int, height: Int, densityDpi: Int): Verdict = when {
        width == WIDTH && height == HEIGHT && densityDpi == DENSITY_DPI -> Verdict.REFERENCE
        width == HEIGHT && height == WIDTH && densityDpi == DENSITY_DPI -> Verdict.ROTATED
        else -> Verdict.OTHER
    }
}
