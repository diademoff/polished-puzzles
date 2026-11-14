package name.boyle.chris.sgtpuzzles.parity

import name.boyle.chris.sgtpuzzles.engine.SgtpSave
import java.io.File

/**
 * Names used by the save corpus. The generator and the tests share
 * them, so a changed layout is a compile-time change in one place.
 */
object SaveNames {
    /** A preset name that is safe on every file system. */
    fun fileName(params: String): String =
        if (params.isEmpty()) {
            "default"
        } else {
            params.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
                .joinToString("")
        }

    /** The initial state of one preset. */
    fun initialPath(game: String, params: String): String =
        "initial/$game/${fileName(params)}.sgtp"

    /** A hand-picked state that covers a record type the presets do not. */
    const val CASES = "cases"
}

/**
 * Freezes the cross-compatibility save corpus.
 *
 * Usage (normally through tools/gen-saves.sh):
 *
 *   genSaves <testdata dir> <UPSTREAM.lock>
 *
 * Output:
 *
 *   testdata/saves/initial/<game>/<params>.sgtp
 *   testdata/saves/cases/fifteen-4x4-*.sgtp
 *
 * The corpus is read by `SaveParityTest`, which parses every file with
 * the Kotlin engine, writes it back, and asks the oracle to load both
 * versions. A save change is a reviewed change: never regenerate the
 * corpus to make a red test green.
 */
object GenSaves {

    private const val CURSOR_DOWN = 0x020A
    private const val CURSOR_LEFT = 0x020B
    private const val CURSOR_RIGHT = 0x020C
    private const val CURSOR_UP = 0x0209
    private const val CURSOR_SELECT = 0x020D

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: genSaves <testdata dir> <UPSTREAM.lock>" }
        val testdata = File(args[0])
        val lock = File(args[1])
        val upstreamCommit = UpstreamLock.commit(lock)

        val saves = File(testdata, "saves")
        saves.deleteRecursively()

        var files = 0
        val skipped = ArrayList<String>()

        OracleClient().use { oracle ->
            for (game in oracle.games().sorted()) {
                val presets = linkedSetOf("")
                oracle.presets(game).mapTo(presets) { it.params }
                val sorted = presets.sorted()
                val names = HashSet<String>()
                for (params in sorted) {
                    check(names.add(SaveNames.fileName(params))) {
                        "$game: preset names collide after sanitizing: $params"
                    }

                    val state = oracle.newGame(game, params, GenGoldens.SEED)
                    val save = SgtpSave.parse(state.state)
                    check(save.serialise() == state.state) {
                        "$game '$params': the oracle save is not canonical"
                    }

                    val reload = try {
                        oracle.id(state.state)
                    } catch (e: OracleException) {
                        /*
                         * `pegs 5x5random` is an upstream preset that
                         * serialises long-term params which upstream
                         * rejects on load. The trace path uses traceNew
                         * for the same reason. Do not write a save that
                         * nothing can read.
                         */
                        skipped += "$game '$params': ${e.message}"
                        continue
                    }
                    check(reload.id == state.id) {
                        "$game '$params': reload changed the game id"
                    }

                    write(saves, SaveNames.initialPath(game, params), save)
                    files++
                }
            }

            val new = oracle.newGame("fifteen", "4x4", GenGoldens.SEED)
            val moves = oracle.keys(
                new.state,
                listOf(
                    CURSOR_LEFT, CURSOR_SELECT,
                    CURSOR_RIGHT, CURSOR_SELECT,
                    CURSOR_DOWN, CURSOR_SELECT,
                    CURSOR_UP, CURSOR_SELECT,
                ),
            )
            val undo = oracle.undo(moves.state)
            val solve = oracle.solve(new.state)

            writeCase(oracle, saves, "fifteen-4x4-moves", moves)
            writeCase(oracle, saves, "fifteen-4x4-undo", undo)
            writeCase(oracle, saves, "fifteen-4x4-solve", solve)
            files += 3
        }

        println("saves: $files files, upstream ${upstreamCommit.take(7)}")
        for (skip in skipped) println("skipped: $skip")
    }

    private fun writeCase(oracle: OracleClient, saves: File, name: String, state: GameState) {
        val save = SgtpSave.parse(state.state)
        check(save.serialise() == state.state) { "$name: the oracle save is not canonical" }
        check(oracle.id(save.serialise()).id == state.id) { "$name: the case does not reload" }
        write(saves, "${SaveNames.CASES}/$name.sgtp", save)
    }

    private fun write(root: File, relative: String, save: SgtpSave) {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText(save.serialise())
    }
}
