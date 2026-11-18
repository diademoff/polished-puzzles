package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Level: semantic. Game: solo, the first generation of a process.
 *
 * Every other solo test generates inside the test JVM, where the
 * generator has already run and the JIT has settled. The first call of a
 * process is the one that used to disagree with the oracle at 5x5, so
 * this test owns that state: it spawns a JVM per case through
 * [SoloFreshJvm] and compares the whole save, which covers `DESC` and
 * `AUXINFO`.
 *
 * The child disables on-stack replacement. HotSpot 17 miscompiles it for
 * `Solo.solver`; the port itself agrees with C in every other mode.
 * `docs/PARITY.md` records the measurement.
 */
class SoloFirstGenerationTest {

    private val timeoutSeconds = 300L

    /** Runs [generations] generations in one fresh JVM, saves in order. */
    private fun freshSaves(
        params: String,
        seed: String,
        generations: Int,
    ): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = mutableListOf(java, "-cp", System.getProperty("java.class.path"))
        command += SoloFreshJvm.JVM_ARGS
        command += listOf(
            SoloFreshJvm::class.java.name,
            params,
            seed,
            generations.toString(),
        )
        val process = ProcessBuilder(command).start()
        val output = process.inputStream.bufferedReader().readLines()
        val errors = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("$params '$seed' child JVM timed out")
        }
        assertEquals("$params '$seed' child JVM exit: $errors", 0, process.exitValue())

        val saves = mutableListOf<String>()
        var i = 0
        while (i < output.size) {
            if (output[i] == "===SAVE") {
                val end = output.subList(i, output.size).indexOf("===END") + i
                assertTrue("$params '$seed' child JVM save is not closed", end > i)
                // `readLines` drops the newline that ends every record.
                saves += output.subList(i + 1, end).joinToString("\n") + "\n"
                i = end
            }
            i++
        }
        assertEquals("$params '$seed' generations", generations, saves.size)
        return saves
    }

    @Test(timeout = 600_000)
    fun `the first 25 symbol generation in a fresh JVM matches the oracle`() =
        OracleClient().use { oracle ->
            // The two 25-symbol presets that the cap test uses: Simple
            // and Intersect. Both used to vary with the JVM state.
            for (params in listOf("5x5db", "5x5di")) {
                val expected = oracle.newGame("solo", params, "12345")
                val save = freshSaves(params, "12345", 1).single()
                assertEquals(
                    "$params fresh DESC",
                    SgtpSave.parse(expected.state).value("DESC"),
                    SgtpSave.parse(save).value("DESC"),
                )
                assertEquals("$params fresh save", expected.state, save)
            }
        }

    @Test(timeout = 600_000)
    fun `a later generation in the same JVM matches the first`() = OracleClient().use { oracle ->
        val expected = oracle.newGame("solo", "5x5db", "12345")
        val saves = freshSaves("5x5db", "12345", 3)
        for ((generation, save) in saves.withIndex()) {
            assertEquals("generation $generation save", expected.state, save)
        }
    }
}
