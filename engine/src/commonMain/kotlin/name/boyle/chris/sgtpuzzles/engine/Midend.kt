package name.boyle.chris.sgtpuzzles.engine

/**
 * The portable part of upstream `midend.c`.
 *
 * A midend owns the parameters, the seed, the game description, the
 * history of states, and the position in that history. It is the
 * semantic layer: it serialises to the same `SAVEFILE` text as C, it
 * adds and removes history entries the same way, and it exposes the
 * same public flags.
 *
 * The shell-facing parts of upstream `midend.c` (menus, timers, size
 * negotiation, preferences, animations) are not here yet. They have no
 * meaning without the Android shell and they do not affect a saved
 * state. `ADR 0006` records that the midend is ported with its first
 * game, and that the semantic subset is the part the parity matrix
 * checks.
 */
class Midend private constructor(
    val game: Game,
    val params: Game.Params,
    val currentParams: Game.Params,
    val seed: String?,
    val desc: String,
    val auxInfo: String?,
    private val entries: MutableList<Entry>,
    var statePos: Int,
) {

    enum class MoveType { NEWGAME, MOVE, SOLVE, RESTART }

    data class Entry(val state: Game.State, val move: String?, val type: MoveType)

    val state: Game.State get() = entries[statePos - 1].state

    /** The frontend state, mirroring upstream `game_ui`. */
    var ui: Game.Ui = game.newUi(state)
        private set

    /** Set when `changedState` reports a completion; cleared by a take. */
    private var completedEvent = false

    val stateCount: Int get() = entries.size

    val canUndo: Boolean get() = statePos > 1

    val canRedo: Boolean get() = statePos < entries.size

    /** The tile size the oracle reports for a large window. */
    val tileSize: Int get() = game.preferredTileSize

    val size: Size get() = game.computeSize(params, tileSize)

    /**
     * `midend_size(..., user_size = true)`: the largest tile size that
     * fits in [width] x [height]. The doubling and the binary search are
     * the same as C, so the result is identical for odd and even tiles.
     */
    fun largestTileFor(width: Int, height: Int): Int {
        var maximum = 1
        while (true) {
            val candidate = game.computeSize(params, maximum)
            if (candidate.width > width || candidate.height > height) break
            maximum *= 2
        }
        var minimum = 1
        while (maximum - minimum > 1) {
            val middle = (maximum + minimum) / 2
            val candidate = game.computeSize(params, middle)
            if (candidate.width <= width && candidate.height <= height) {
                minimum = middle
            } else {
                maximum = middle
            }
        }
        return minimum
    }

    /**
     * The geometry the app should draw and read input with for a view of
     * [viewWidth] x [viewHeight]. The board is centred in the view.
     */
    fun geometryFor(viewWidth: Int, viewHeight: Int): BoardGeometry {
        val tile = largestTileFor(viewWidth, viewHeight)
        val board = game.computeSize(params, tile)
        return BoardGeometry(
            tileSize = tile,
            originX = (viewWidth - board.width) / 2,
            originY = (viewHeight - board.height) / 2,
            width = board.width,
            height = board.height,
        )
    }

    /** `midend_get_game_id`: short parameters, a colon, and the description. */
    fun id(): String = game.encodeParams(currentParams, false) + ":" + desc

    fun setCursorVisible(visible: Boolean) = game.setCursorVisible(ui, visible)

    fun cursorLocation(tileSize: Int): Game.CursorRect? =
        game.cursorLocation(ui, state, tileSize)

    fun status(): Int = game.status(state)

    fun statusText(): String = game.statusText(state, ui)

    fun textFormat(): String = game.textFormat(state)

    /**
     * Consumes the "the last transition completed the game" event. The
     * midend sets it where upstream calls `android_completed`, so each
     * completion is observed exactly once. A caller that never asks
     * cannot observe a stale event twice; the next take returns false.
     */
    fun takeCompletedEvent(): Boolean {
        val raised = completedEvent
        completedEvent = false
        return raised
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        if (game.changedState(ui, state, entries[statePos - 2].state)) {
            completedEvent = true
        }
        statePos--
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        if (game.changedState(ui, state, entries[statePos].state)) {
            completedEvent = true
        }
        statePos++
        return true
    }

    /**
     * Starts the same description again, as `midend_restart_game`. The
     * new state is a `RESTART` entry, so undo returns to where the
     * restart was pressed.
     */
    fun restart(): Boolean {
        val previous = state
        val restarted = game.newState(params, desc)
        purgeRedo()
        entries += Entry(restarted, desc, MoveType.RESTART)
        statePos++
        if (game.changedState(ui, previous, restarted)) completedEvent = true
        return true
    }

    /** Drops the redo branch, as `midend_purge_states`. */
    fun purgeStates() = purgeRedo()

    /** The `SOLVE` history entry, or false when the game cannot solve. */
    fun solve(): Boolean {
        val previous = state
        val move = game.solve(state, auxInfo) ?: return false
        val solved = game.executeMove(state, move) ?: return false
        purgeRedo()
        entries += Entry(solved, move, MoveType.SOLVE)
        statePos++
        // `midend_solve` calls changed_state for its UI effect but ignores
        // the return value, so a solve never raises the completion event.
        game.changedState(ui, previous, solved)
        return true
    }

    /**
     * Applies one frontend key. The state history follows upstream: a
     * real move truncates the redo branch and appends one entry; a
     * no-effect key changes nothing.
     */
    fun processKey(x: Int, y: Int, button: Int, tileSize: Int): KeyOutcome {
        val stripped = Buttons.stripModifiers(button)
        when (stripped) {
            Buttons.UI_UNDO -> return if (undo()) KeyOutcome.SomeEffect else KeyOutcome.NoEffect
            Buttons.UI_REDO -> return if (redo()) KeyOutcome.SomeEffect else KeyOutcome.NoEffect
            Buttons.UI_SOLVE -> return if (solve()) KeyOutcome.SomeEffect else KeyOutcome.NoEffect
        }

        return when (val result = game.interpretMove(state, ui, x, y, stripped, tileSize)) {
            is MoveResult.Move -> {
                val previous = state
                val next = game.executeMove(state, result.text) ?: return KeyOutcome.NoEffect
                purgeRedo()
                entries += Entry(next, result.text, MoveType.MOVE)
                statePos++
                if (game.changedState(ui, previous, next)) completedEvent = true
                KeyOutcome.SomeEffect
            }

            MoveResult.UiUpdate -> KeyOutcome.SomeEffect
            MoveResult.NoEffect -> KeyOutcome.NoEffect
            MoveResult.Unused -> KeyOutcome.Unused
        }
    }

    /**
     * Draws a complete board into [sink], including the operations the
     * midend adds around a game redraw. This is the first redraw of a
     * fresh drawstate: it clears the window and updates the whole area.
     */
    fun recordRedraw(sink: DrawSink) {
        sink.startDraw()
        sink.rect(0, 0, size.width, size.height, 0)
        game.redraw(state, ui, sink, tileSize)
        if (game.wantsStatusBar) sink.status(statusText())
        sink.update(0, 0, size.width, size.height)
        sink.endDraw()
    }

    /** The first redraw of a new game: `changed_state` comes before the draw. */
    fun recordNewGameTrace(sink: DrawSink) {
        sink.changedState(false, false)
        recordRedraw(sink)
    }

    /** Serialises the state exactly as upstream `midend_serialise`. */
    fun serialise(): SgtpSave {
        val records = ArrayList<SaveRecord>()
        records += SaveRecord("SAVEFILE", SgtpSave.MAGIC)
        records += SaveRecord("VERSION", SgtpSave.VERSION)
        records += SaveRecord("GAME", game.name)
        records += SaveRecord("PARAMS", game.encodeParams(params, true))
        records += SaveRecord("CPARAMS", game.encodeParams(currentParams, true))
        seed?.let { records += seedRecord(it) }
        records += SaveRecord("DESC", desc)
        auxInfo?.let { records += SaveRecord("AUXINFO", AuxInfo.encode(it)) }
        records += SaveRecord("NSTATES", entries.size.toString())
        records += SaveRecord("STATEPOS", statePos.toString())
        for (index in 1 until entries.size) {
            val entry = entries[index]
            val move = entry.move ?: error("history entry $index has no move")
            val key = when (entry.type) {
                MoveType.MOVE -> "MOVE"
                MoveType.SOLVE -> "SOLVE"
                MoveType.RESTART -> "RESTART"
                MoveType.NEWGAME -> error("only the first entry may be NEWGAME")
            }
            records += SaveRecord(key, move)
        }
        return SgtpSave.of(records)
    }

    private fun purgeRedo() {
        while (entries.size > statePos) entries.removeAt(entries.size - 1)
    }

    private fun seedRecord(value: String): SaveRecord {
        val printable = value.all { it.code in 32..126 }
        return if (printable && value.isNotEmpty()) {
            SaveRecord("SEED", value)
        } else {
            SaveRecord("HEXSEED", value.map { (it.code and 0xFF).toString(16).padStart(2, '0') }.joinToString(""))
        }
    }

    companion object {
        /** Creates a new game and generates its description from [seed]. */
        fun newGame(game: Game, paramsText: String, seed: String): Midend {
            val effective = if (paramsText.isEmpty()) {
                game.encodeParams(game.defaultParams(), false)
            } else {
                paramsText
            }
            val currentParams = game.decodeParams(effective)
            game.validateParams(currentParams, true)?.let { throw IllegalArgumentException(it) }
            val params = game.decodeParams(game.encodeParams(currentParams, false))
            val random = RandomState.fromSeed(seed)
            val generated = game.newDesc(currentParams, random)
            game.validateDesc(currentParams, generated.text)?.let {
                throw IllegalArgumentException(it)
            }
            val state = game.newState(currentParams, generated.text)
            return Midend(
                game = game,
                params = params,
                currentParams = currentParams,
                seed = seed,
                desc = generated.text,
                auxInfo = generated.auxInfo,
                entries = mutableListOf(Entry(state, null, MoveType.NEWGAME)),
                statePos = 1,
            )
        }

        /** Builds a game from `params:desc`, as a game ID does. */
        fun fromGameId(game: Game, gameId: String): Midend {
            val colon = gameId.indexOf(':')
            require(colon >= 0) { "game ID has no colon: $gameId" }
            val paramsText = gameId.substring(0, colon)
            val desc = gameId.substring(colon + 1)
            val currentParams = game.decodeParams(paramsText)
            game.validateParams(currentParams, true)?.let { error(it) }
            game.validateDesc(currentParams, desc)?.let { error(it) }
            val params = game.decodeParams(game.encodeParams(currentParams, false))
            return Midend(
                game = game,
                params = params,
                currentParams = currentParams,
                seed = null,
                desc = desc,
                auxInfo = null,
                entries = mutableListOf(
                    Entry(game.newState(currentParams, desc), null, MoveType.NEWGAME),
                ),
                statePos = 1,
            )
        }

        /** Reads a save file. The game must already be known. */
        fun fromSave(game: Game, save: SgtpSave): Midend {
            require(save.value("GAME") == game.name) {
                "save file is from a different game: ${save.value("GAME")}"
            }
            val paramsText = requireNotNull(save.value("PARAMS")) {
                "long-term parameters in save file are missing"
            }
            val currentText = requireNotNull(save.value("CPARAMS")) {
                "short-term parameters in save file are missing"
            }
            val params = game.decodeParams(paramsText)
            game.validateParams(params, true)?.let { error("long-term parameters: $it") }
            val currentParams = game.decodeParams(currentText)
            game.validateParams(currentParams, false)?.let { error("short-term parameters: $it") }

            val seed = when {
                save.value("HEXSEED") != null -> decodeHexSeed(save.value("HEXSEED")!!)
                else -> save.value("SEED")
            }
            val desc = requireNotNull(save.value("DESC")) {
                "game description in save file is missing"
            }
            game.validateDesc(currentParams, desc)?.let { error("game description: $it") }

            val stateCount = requireNotNull(save.value("NSTATES")).toInt()
            require(stateCount > 0) { "state count is not positive" }
            val statePos = requireNotNull(save.value("STATEPOS")).toInt()
            require(statePos in 1..stateCount) { "state position is out of range" }

            val entries = ArrayList<Entry>(stateCount)
            entries += Entry(game.newState(currentParams, desc), null, MoveType.NEWGAME)

            val history = save.records.filter {
                it.key == "MOVE" || it.key == "SOLVE" || it.key == "RESTART"
            }
            require(history.size == stateCount - 1) {
                "save file has ${history.size} history records for $stateCount states"
            }
            for (record in history) {
                val previous = entries.last().state
                val type = when (record.key) {
                    "MOVE" -> MoveType.MOVE
                    "SOLVE" -> MoveType.SOLVE
                    else -> MoveType.RESTART
                }
                val next = if (type == MoveType.RESTART) {
                    game.validateDesc(currentParams, record.value)?.let {
                        error("restart move: $it")
                    }
                    game.newState(currentParams, record.value)
                } else {
                    game.executeMove(previous, record.value)
                        ?: error("save file contained an invalid move: ${record.value}")
                }
                entries += Entry(next, record.value, type)
            }

            return Midend(
                game,
                params,
                currentParams,
                seed,
                desc,
                save.value("AUXINFO")?.let { AuxInfo.decode(it) },
                entries,
                statePos,
            )
        }

        private fun decodeHexSeed(text: String): String {
            require(text.length % 2 == 0) { "hex seed has an odd length" }
            val out = StringBuilder(text.length / 2)
            var i = 0
            while (i < text.length) {
                val value = text.substring(i, i + 2).toIntOrNull(16)
                    ?: error("hex seed has a non-hex digit: $text")
                out.append((value and 0xFF).toChar())
                i += 2
            }
            return out.toString()
        }
    }
}

enum class KeyOutcome { SomeEffect, NoEffect, Unused }
