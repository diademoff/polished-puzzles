package name.boyle.chris.sgtpuzzles.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.res.Resources
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Level: instrumentation. Screenshot comparison on one fixed AVD.
 *
 * Goldens are PNG files in the androidTest assets under `screens/`. A
 * golden is a picture of the whole screen below the status bar. The
 * status bar is system UI with a real clock, so its rows are excluded
 * from the comparison. The AVD must be the one in
 * `tools/perf/baseline.md`: another density or renderer changes every
 * pixel.
 *
 * Goldens are recorded locally on the reference AVD and are not
 * committed: the `screens/` directory is ignored by git. Record with
 * `-e recordScreens true`; the image is written to the app's external
 * files directory, pull it with `adb pull` and review it. Never record a
 * golden to make a red test green: a difference is either a bug or a
 * deliberate change with a reviewed picture.
 */
object ScreenshotGoldens {

    /** Per-pixel channel sum above which a pixel counts as different. */
    private const val PIXEL_TOLERANCE = 24

    /** Share of pixels that may differ before the test fails. */
    private const val MAX_DIFF_RATIO = 0.002

    private const val ASSET_DIR = "screens"

    /*
     * The reference AVD is the row "Screen" in
     * `tools/perf/baseline.md`: 1080x2400 at 420 dpi. The three recorded
     * goldens in `app/src/androidTest/assets/screens/` are 1080x2400.
     * The layout, the text, and the anti-aliasing all depend on the pixel
     * size and the density, so another device cannot be compared without
     * a new golden. The gate skips instead of failing.
     */
    private const val REFERENCE_WIDTH = 1080
    private const val REFERENCE_HEIGHT = 2400
    private const val REFERENCE_DENSITY_DPI = 420

    /** A frame can still be settling right after launch; retry a few times. */
    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 400L

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val recording: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordScreens") == "true"

    /**
     * Skips the whole class unless this is the reference device.
     *
     * The gate uses the captured screen, not a configuration value: the
     * capture is the artifact that is compared. A display left in
     * landscape by another test is 2340x1080 here, so the gate skips it
     * instead of letting the golden comparison fail on the size.
     */
    fun requireReferenceDevice() {
        val densityDpi = instrumentation.targetContext.resources.displayMetrics.densityDpi
        val capture = screenshot()
        if (capture == null) {
            fail("the device did not return a screenshot")
            return
        }
        assumeTrue(
            "skipped: not the reference device. The chooser goldens were recorded on the " +
                "1080x2400 420 dpi AVD (tools/perf/baseline.md); this device captures " +
                "${capture.width}x${capture.height} at $densityDpi dpi",
            capture.width == REFERENCE_WIDTH &&
                capture.height == REFERENCE_HEIGHT &&
                densityDpi == REFERENCE_DENSITY_DPI,
        )
    }

    fun compare(name: String) {
        if (recording) {
            val captured = screenshot()
            if (captured == null) {
                fail("the device did not return a screenshot")
                return
            }
            val file = writeGolden(name, captured)
            println("recorded $name to ${file.absolutePath}")
            return
        }
        val golden = readGolden(name)
        if (golden == null) {
            // Goldens are recorded locally on the reference AVD and are not
            // committed. Without them there is nothing to compare.
            assumeTrue(
                "no golden '$name': record it on the reference AVD, see ChooserScreenshotTest",
                false,
            )
            return
        }
        var lastActual: Bitmap? = null
        var lastRatio = 0.0
        repeat(ATTEMPTS) { attempt ->
            val actual = screenshot()
            if (actual == null) {
                fail("the device did not return a screenshot")
                return
            }
            lastActual = actual
            val ratio = diffRatio(name, golden, actual)
            if (ratio <= MAX_DIFF_RATIO) return
            lastRatio = ratio
            if (attempt < ATTEMPTS - 1) SystemClock.sleep(RETRY_DELAY_MS)
        }
        val file = lastActual?.let { writeGolden("$name-actual", it) }
        fail(
            "$name: ${"%.3f".format(lastRatio * 100)}% of pixels differ " +
                "(limit ${"%.3f".format(MAX_DIFF_RATIO * 100)}%). " +
                "The captured screen is ${file?.absolutePath}. " +
                "Review it against the golden; regenerate only for a deliberate change.",
        )
    }

    private fun screenshot(): Bitmap? =
        instrumentation.uiAutomation.takeScreenshot()

    private fun diffRatio(name: String, golden: Bitmap, actual: Bitmap): Double {
        assertEquals("$name: width", golden.width, actual.width)
        assertEquals("$name: height", golden.height, actual.height)

        // The status bar carries a real clock and changing signal icons.
        // It is system UI, not ours, so it is not compared.
        val firstRow = statusBarHeight()
        val goldenRow = IntArray(golden.width)
        val actualRow = IntArray(actual.width)
        var different = 0
        for (y in firstRow until golden.height) {
            golden.getPixels(goldenRow, 0, golden.width, 0, y, golden.width, 1)
            actual.getPixels(actualRow, 0, actual.width, 0, y, actual.width, 1)
            for (x in 0 until golden.width) {
                val a = goldenRow[x]
                val b = actualRow[x]
                val delta = abs(Color.red(a) - Color.red(b)) +
                    abs(Color.green(a) - Color.green(b)) +
                    abs(Color.blue(a) - Color.blue(b))
                if (delta > PIXEL_TOLERANCE) {
                    different++
                }
            }
        }
        val comparedPixels = golden.width.toLong() * (golden.height - firstRow)
        return different.toDouble() / comparedPixels
    }

    private fun statusBarHeight(): Int {
        val id = Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) Resources.getSystem().getDimensionPixelSize(id) else 0
    }

    private fun readGolden(name: String): Bitmap? {
        val assets = instrumentation.context.assets
        return runCatching {
            assets.open("$ASSET_DIR/$name.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun writeGolden(name: String, bitmap: Bitmap): File {
        val dir = instrumentation.targetContext.getExternalFilesDir(ASSET_DIR) ?: File("/data/local/tmp")
        dir.mkdirs()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun compareBitmaps(name: String, golden: Bitmap, actual: Bitmap) {
        assertEquals("$name: width", golden.width, actual.width)
        assertEquals("$name: height", golden.height, actual.height)

        // The status bar carries a real clock and changing signal icons.
        // It is system UI, not ours, so it is not compared.
        val firstRow = statusBarHeight()
        val goldenRow = IntArray(golden.width)
        val actualRow = IntArray(actual.width)
        var different = 0
        for (y in firstRow until golden.height) {
            golden.getPixels(goldenRow, 0, golden.width, 0, y, golden.width, 1)
            actual.getPixels(actualRow, 0, actual.width, 0, y, actual.width, 1)
            for (x in 0 until golden.width) {
                val a = goldenRow[x]
                val b = actualRow[x]
                val delta = abs(Color.red(a) - Color.red(b)) +
                    abs(Color.green(a) - Color.green(b)) +
                    abs(Color.blue(a) - Color.blue(b))
                if (delta > PIXEL_TOLERANCE) {
                    different++
                }
            }
        }
        val comparedPixels = golden.width.toLong() * (golden.height - firstRow)
        val ratio = different.toDouble() / comparedPixels
        if (ratio > MAX_DIFF_RATIO) {
            val actualFile = writeGolden("$name-actual", actual)
            fail(
                "$name: ${"%.3f".format(ratio * 100)}% of pixels differ " +
                    "(limit ${"%.3f".format(MAX_DIFF_RATIO * 100)}%). " +
                    "The captured screen is ${actualFile.absolutePath}. " +
                    "Review it against the golden; regenerate only for a deliberate change.",
            )
        }
    }
}
