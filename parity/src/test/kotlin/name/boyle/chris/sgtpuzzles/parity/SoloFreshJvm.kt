package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.Midend
import name.boyle.chris.sgtpuzzles.engine.Solo

/**
 * One solo generation in a fresh JVM, for [SoloFirstGenerationTest].
 *
 * The first generation of a process is the only one that runs against
 * an unsolved JVM: no classes loaded, no profile, no settled JIT. That
 * state is part of what the test pins, so it cannot share the test JVM.
 */
object SoloFreshJvm {

    /**
     * `-XX:-UseOnStackReplacement` keeps this test on the port.
     *
     * On this machine (Arch OpenJDK 17.0.21) the JIT miscompiles the
     * on-stack replacement of `Solo.solver`, the 5.4 KB deduction loop,
     * so the first 5x5 generation of a process sometimes returns a
     * different description with the same `AUXINFO`. Interpreted, C1,
     * compiled up front, and Java 25 all agree with the oracle, which
     * shows the port is right and the compiled loop is wrong. The
     * measurement is in `docs/PARITY.md`; the flag keeps the JIT bug
     * out of the parity claim.
     */
    val JVM_ARGS = listOf("-XX:-UseOnStackReplacement")

    /** Prints one save per generation, between `===SAVE` and `===END`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val params = args[0]
        val seed = args[1]
        val generations = args.getOrNull(2)?.toInt() ?: 1
        repeat(generations) {
            val save = Midend.newGame(Solo, params, seed).serialise().serialise()
            println("===SAVE")
            print(save)
            println("===END")
        }
    }
}
