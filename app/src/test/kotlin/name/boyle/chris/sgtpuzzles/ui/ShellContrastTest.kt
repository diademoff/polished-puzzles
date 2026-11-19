package name.boyle.chris.sgtpuzzles.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Level: unit. Contrast of the shell theme tokens.
 *
 * Reads the two palettes that `SgtPuzzlesTheme` uses and computes the
 * WCAG 2.1 ratio of every foreground/background pair the shell draws
 * text on. The rule is `docs/DESIGN.md` section 3: text at least 4.5:1,
 * UI lines at least 3:1. `outline_variant` is not checked because it is
 * documented as decoration inside a section, never a boundary or text.
 */
class ShellContrastTest {

    private val root = File(
        System.getProperty("testdata") ?: error("testdata system property is not set"),
    ).parentFile

    @Test
    fun `the light theme meets the text and outline minimums`() {
        check("values/colors_shell.xml")
    }

    @Test
    fun `the dark theme meets the text and outline minimums`() {
        check("values-night/colors_shell.xml")
    }

    private fun check(resource: String) {
        val colors = parse(File(root, "app/src/main/res/$resource"))
        for ((foreground, background) in TEXT_PAIRS) {
            val ratio = ratio(colors.getValue(foreground), colors.getValue(background))
            assertTrue(
                "$resource: $foreground on $background is ${"%.2f".format(ratio)}:1, " +
                    "below 4.5:1",
                ratio >= 4.5,
            )
        }
        val outline = ratio(colors.getValue("shell_outline"), colors.getValue("shell_surface"))
        assertTrue(
            "$resource: shell_outline on shell_surface is ${"%.2f".format(outline)}:1, " +
                "below 3:1",
            outline >= 3.0,
        )
    }

    private fun parse(file: File): Map<String, Int> {
        assertTrue("missing ${file.path}", file.isFile)
        return COLOR.findAll(file.readText()).associate {
            it.groupValues[1] to it.groupValues[2].toLong(16).toInt()
        }
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((color shr shift) and 0xff) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        val COLOR = Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{8})</color>""")

        val TEXT_PAIRS = listOf(
            "shell_on_surface" to "shell_surface",
            "shell_on_surface_variant" to "shell_surface",
            "shell_on_surface" to "shell_surface_variant",
            "shell_on_primary" to "shell_primary",
            "shell_on_primary_container" to "shell_primary_container",
            "shell_on_secondary" to "shell_secondary",
            "shell_on_secondary_container" to "shell_secondary_container",
            "shell_on_tertiary" to "shell_tertiary",
            "shell_on_tertiary_container" to "shell_tertiary_container",
            "shell_on_error" to "shell_error",
            "shell_on_error_container" to "shell_error_container",
            "shell_inverse_on_surface" to "shell_inverse_surface",
            "shell_primary" to "shell_surface",
            "shell_secondary" to "shell_surface",
            "shell_tertiary" to "shell_surface",
            "shell_error" to "shell_surface",
        )
    }
}
