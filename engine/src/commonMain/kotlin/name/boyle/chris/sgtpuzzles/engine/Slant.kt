package name.boyle.chris.sgtpuzzles.engine

/**
 * A port of upstream `slant.c`.
 *
 * The description parser, the move strings, the completion check, the
 * solver, the text format, the palette, and the drawing primitives match
 * the C game. The generator and solver live in [SlantGeneration].
 *
 * The cursor and the two preferences are frontend state, like upstream
 * `game_ui`. They are not part of the save file.
 */
object Slant : Game {

    private const val PREFERRED_TILE_SIZE = 32

    private const val COL_BACKGROUND = 0
    private const val COL_GRID = 1
    private const val COL_INK = 2
    private const val COL_SLANT1 = 3
    private const val COL_SLANT2 = 4
    private const val COL_ERROR = 5
    private const val COL_CURSOR = 6
    private const val COL_FILLEDSQUARE = 7
    private const val COL_GROUNDED = 8
    private const val NCOLOURS = 9

    private const val ERR_VERTEX = 1
    private const val ERR_SQUARE = 2
    private const val BORDER_EDGE = 4

    private const val BACKSLASH = 0x00000001
    private const val FORWSLASH = 0x00000002
    private const val L_T = 0x00000004
    private const val ERR_L_T = 0x00000008
    private const val L_B = 0x00000010
    private const val ERR_L_B = 0x00000020
    private const val T_L = 0x00000040
    private const val ERR_T_L = 0x00000080
    private const val T_R = 0x00000100
    private const val ERR_T_R = 0x00000200
    private const val C_TL = 0x00000400
    private const val ERR_C_TL = 0x00000800
    private const val FLASH = 0x00001000
    private const val ERRSLASH = 0x00002000
    private const val ERR_TL = 0x00004000
    private const val ERR_TR = 0x00008000
    private const val ERR_BL = 0x00010000
    private const val ERR_BR = 0x00020000
    private const val CURSOR = 0x00040000
    private const val GROUNDED = 0x00080000

    class Ui : Game.Ui {
        var curX = 0
        var curY = 0
        var curVisible = false
        var swapButtons = false
        var fadeGrounded = false
    }

    /**
     * `clues` and `errors` are indexed by the (w+1) x (h+1) clue grid,
     * except for `BORDER_EDGE`, which uses the square grid as in C.
     */
    class State(
        val params: SlantGeneration.Params,
        val clues: IntArray,
        val soln: IntArray,
        val errors: IntArray,
        val completed: Boolean,
        val usedSolve: Boolean,
    ) : Game.State {

        override fun copy(): Game.State = State(
            params,
            clues,
            soln.copyOf(),
            errors.copyOf(),
            completed,
            usedSolve,
        )

        override fun equals(other: Any?): Boolean =
            other is State &&
                params == other.params &&
                clues.contentEquals(other.clues) &&
                soln.contentEquals(other.soln) &&
                errors.contentEquals(other.errors) &&
                completed == other.completed &&
                usedSolve == other.usedSolve

        override fun hashCode(): Int =
            31 * (31 * (31 * params.hashCode() + soln.contentHashCode()) +
                errors.contentHashCode()) + completed.hashCode()
    }

    override val name: String = "Slant"
    override val backendName: String = "slant"
    override val preferredTileSize: Int = PREFERRED_TILE_SIZE
    override val wantsStatusBar: Boolean = false

    override fun defaultParams(): Game.Params = SlantGeneration.defaultParams()

    override fun presets(): List<Preset> = SlantGeneration.presets()

    override fun decodeParams(text: String): Game.Params = SlantGeneration.decode(text)

    override fun encodeParams(params: Game.Params, full: Boolean): String =
        SlantGeneration.encode(params as SlantGeneration.Params, full)

    override fun validateParams(params: Game.Params, full: Boolean): String? =
        SlantGeneration.validateParams(params as SlantGeneration.Params, full)

    /** `game_configure` (`slant.c:190-216`). */
    override fun configure(params: Game.Params): List<ConfigItem> {
        val p = params as SlantGeneration.Params
        return listOf(
            ConfigItem.Text("Width", p.width.toString()),
            ConfigItem.Text("Height", p.height.toString()),
            ConfigItem.Choices(
                name = "Difficulty",
                choices = ":Easy:Hard",
                keywords = null,
                selected = p.difficulty,
            ),
        )
    }

    /** `custom_params` (`slant.c:218-227`): `atoi` and the selected index. */
    override fun customParams(items: List<ConfigItem>): Game.Params =
        SlantGeneration.Params(
            atoi(text(items, "Width")),
            atoi(text(items, "Height")),
            (items.lastOrNull { it.name == "Difficulty" } as? ConfigItem.Choices)?.selected
                ?: 0,
        )

    /** True: the C backend has a non-NULL `get_prefs`. */
    override val declaresPrefs: Boolean get() = true

    /** `get_prefs` (`slant.c:1705-1728`). */
    override val prefs: List<ConfigItem>
        get() = listOf(
            ConfigItem.Choices(
                name = "Mouse button order",
                choices = ":Left \\, right /:Left /, right \\",
                keywords = ":\\:/",
                selected = 0,
                kw = "left-button",
            ),
            ConfigItem.Bool(
                "Fade grounded components",
                false,
                "fade-grounded",
            ),
        )

    /** `set_prefs` (`slant.c:1730-1734`). */
    override fun setPrefs(ui: Game.Ui, prefs: Prefs) {
        val u = ui as Ui
        u.swapButtons = prefs.choice("left-button") == 1
        u.fadeGrounded = prefs.bool("fade-grounded")
    }

    override fun newDesc(params: Game.Params, random: RandomState): Description {
        val generated = SlantGeneration.newDesc(params as SlantGeneration.Params, random)
        return Description(generated.text, generated.auxInfo)
    }

    override fun validateDesc(params: Game.Params, desc: String): String? =
        SlantGeneration.validateDesc(params as SlantGeneration.Params, desc)

    override fun newState(params: Game.Params, desc: String): Game.State {
        val p = params as SlantGeneration.Params
        val bigW = p.width + 1
        val area = bigW * (p.height + 1)
        val clues = IntArray(area) { -1 }
        var squares = 0
        for (c in desc) {
            when (c) {
                in 'a'..'z' -> squares += c - 'a' + 1
                in '0'..'4' -> clues[squares++] = c - '0'
                else -> throw IllegalArgumentException("Invalid character in game description")
            }
        }
        require(squares == area) { "description does not fill the clue grid" }
        return State(
            params = p,
            clues = clues,
            soln = IntArray(p.width * p.height),
            errors = IntArray(area),
            completed = false,
            usedSolve = false,
        )
    }

    override fun solve(state: Game.State, auxInfo: String?): String? =
        solveGame(state, auxInfo).move

    override fun solveGame(state: Game.State, auxInfo: String?): SolveOutcome =
        solveOutcome(state, auxInfo, state)

    /**
     * `solve_game` with the first state of the history, the `orig`
     * argument of `midend_solve` (`midend.c:2109-2111`). C reads
     * `state`, not `currstate`, for both the clues and the diff
     * (`slant.c:1550-1601`), so a cell that a move already set to the
     * answer value stays in the move.
     */
    override fun solveGame(
        state: Game.State,
        auxInfo: String?,
        initialState: Game.State,
    ): SolveOutcome = solveOutcome(initialState, auxInfo, initialState)

    /**
     * `solve_game` (`slant.c:1544-1592`). A stored answer is used
     * directly. Otherwise `slant_solve` reports 0 for a contradiction
     * and 2 for a board it cannot finish, with the two C messages. A
     * short `aux` is a seam guard: C reads `w*h` bytes without a length,
     * so no C message applies.
     */
    private fun solveOutcome(
        state: Game.State,
        auxInfo: String?,
        against: Game.State,
    ): SolveOutcome {
        val s = state as State
        val board = against as State
        val w = s.params.width
        val h = s.params.height
        val soln = IntArray(w * h)
        if (auxInfo != null) {
            if (auxInfo.length != w * h) return SolveOutcome(null, null)
            for (i in 0 until w * h) soln[i] = if (auxInfo[i] == '\\') -1 else 1
        } else {
            val ret = SlantGeneration.solve(
                w, h, s.clues, soln, SlantGeneration.Scratch(w, h), SlantGeneration.HARD,
            )
            if (ret != 1) {
                return SolveOutcome(
                    null,
                    if (ret == 0) {
                        "This puzzle is not self-consistent"
                    } else {
                        "Unable to find a unique solution for this puzzle"
                    },
                )
            }
        }

        val move = StringBuilder("S")
        for (y in 0 until h) {
            for (x in 0 until w) {
                val value = if (soln[y * w + x] == -1) -1 else 1
                // C diffs `state->soln`, the first board of the history.
                if (board.soln[y * w + x] != value) {
                    move.append(';')
                    move.append(if (value < 0) '\\' else '/')
                    move.append(x).append(',').append(y)
                }
            }
        }
        return SolveOutcome(move.toString(), null)
    }

    override fun executeMove(state: Game.State, move: String): Game.State? {
        val s = state as State
        val w = s.params.width
        val h = s.params.height
        val next = s.copy() as State
        val soln = next.soln.copyOf()
        var usedSolve = next.usedSolve
        var index = 0
        while (index < move.length) {
            when (val c = move[index]) {
                'S' -> {
                    usedSolve = true
                    index++
                }

                '\\', '/', 'C' -> {
                    index++
                    val xStart = index
                    if (index < move.length && move[index] == '-') index++
                    while (index < move.length && move[index].isDigit()) index++
                    if (index == xStart || index >= move.length || move[index] != ',') return null
                    val x = move.substring(xStart, index).toIntOrNull() ?: return null
                    index++
                    val yStart = index
                    if (index < move.length && move[index] == '-') index++
                    while (index < move.length && move[index].isDigit()) index++
                    if (index == yStart) return null
                    val y = move.substring(yStart, index).toIntOrNull() ?: return null
                    if (x < 0 || y < 0 || x >= w || y >= h) return null
                    soln[y * w + x] = when (c) {
                        '\\' -> -1
                        '/' -> 1
                        else -> 0
                    }
                }

                else -> return null
            }
            if (index < move.length) {
                if (move[index] == ';') {
                    index++
                } else {
                    return null
                }
            }
        }

        val result = State(
            next.params,
            next.clues,
            soln,
            next.errors.copyOf(),
            next.completed,
            usedSolve,
        )
        val completed = checkCompletion(result) || result.completed
        return State(
            result.params,
            result.clues,
            result.soln,
            result.errors,
            completed,
            result.usedSolve,
        )
    }

    override fun status(state: Game.State): Int =
        if ((state as State).completed) 1 else 0

    /** Slant has no status bar text, as `wants_statusbar` is false. */
    override fun statusText(state: Game.State, ui: Game.Ui): String = ""

    /** `game_changed_state`: a real move completed the puzzle. */
    override fun changedState(
        ui: Game.Ui,
        oldState: Game.State?,
        newState: Game.State,
    ): Boolean {
        val s = newState as State
        return s.completed &&
            !s.usedSolve &&
            oldState != null &&
            !(oldState as State).completed
    }

    override fun textFormat(state: Game.State): String {
        val s = state as State
        val w = s.params.width
        val h = s.params.height
        val bigW = w + 1
        val bigH = h + 1
        val out = StringBuilder((h + bigH) * (w + bigW + 1) + 1)

        for (y in 0 until bigH) {
            for (x in 0 until bigW) {
                val clue = s.clues[y * bigW + x]
                out.append(if (clue >= 0) '0' + clue else '+')
                if (x < w) out.append('-')
            }
            out.append('\n')
            if (y < h) {
                for (x in 0 until bigW) {
                    out.append('|')
                    if (x < w) {
                        val slash = s.soln[y * w + x]
                        out.append(
                            when {
                                slash < 0 -> '\\'
                                slash > 0 -> '/'
                                else -> ' '
                            },
                        )
                    }
                }
                out.append('\n')
            }
        }
        return out.toString()
    }

    override fun requestKeys(params: Game.Params): KeyRequest =
        KeyRequest(emptyList(), ArrowMode.ARROWS_LEFT_RIGHT)

    override fun computeSize(params: Game.Params, tileSize: Int): Size {
        val p = params as SlantGeneration.Params
        return Size(
            (p.width + 2) * tileSize + 1,
            (p.height + 2) * tileSize + 1,
        )
    }

    override fun newUi(state: Game.State): Game.Ui = Ui()

    override fun setCursorVisible(ui: Game.Ui, visible: Boolean) {
        (ui as Ui).curVisible = visible
    }

    override fun cursorLocation(
        ui: Game.Ui,
        state: Game.State,
        tileSize: Int,
    ): Game.CursorRect? {
        val u = ui as Ui
        if (!u.curVisible) return null
        return Game.CursorRect(
            u.curX * tileSize + tileSize,
            u.curY * tileSize + tileSize,
            tileSize,
            tileSize,
        )
    }

    /**
     * The play unit is the cell, as `game_colours` and `interpret_move`
     * draw and read it: one slash per square. The four clues of the
     * square's corners join the label, because a clue belongs to a
     * vertex and four squares share it. A left click turns the slash
     * clockwise and a right click turns it back, so those two buttons
     * are the unit actions; the `left-button` preference swaps them, and
     * the label follows the preference.
     */
    override fun spokenBoard(
        state: Game.State,
        ui: Game.Ui,
        tileSize: Int,
    ): SpokenBoard {
        val s = state as State
        val u = ui as Ui
        val w = s.params.width
        val h = s.params.height
        val bigW = w + 1

        fun clue(x: Int, y: Int): String {
            val value = s.clues[y * bigW + x]
            if (value < 0) return ""
            return "clue $value"
        }

        val cells = gridSpeech(
            width = w,
            height = h,
            tileSize = tileSize,
            left = tileSize,
            top = tileSize,
            summary = "",
            text = { x, y ->
                val slash = s.soln[y * w + x]
                val shape = when {
                    slash < 0 -> "backslash"
                    slash > 0 -> "slash"
                    else -> "blank"
                }
                // The clue circle sits on a vertex, so a square names the
                // clues of its four corners in the order it draws them.
                val corners = listOf(
                    "up and left" to clue(x, y),
                    "up and right" to clue(x + 1, y),
                    "down and left" to clue(x, y + 1),
                    "down and right" to clue(x + 1, y + 1),
                ).filter { it.second.isNotEmpty() }
                val clueWords = corners.joinToString("") { ", ${it.second} ${it.first}" }
                val cursor = if (u.curVisible && u.curX == x && u.curY == y) {
                    ", cursor"
                } else {
                    ""
                }
                val wrong = if (s.errors[y * bigW + x] and ERR_SQUARE != 0) {
                    SPOKEN_WRONG
                } else {
                    ""
                }
                "$shape$clueWords$cursor$wrong"
            },
            actions = { _, _ -> slashButtons(u.swapButtons) },
        )
        return cells.capped(cells.units.getOrNull(u.curY * w + u.curX))
    }

    /**
     * The two pointer buttons of a slash, in the game's words. The
     * `left-button` preference swaps clockwise and anticlockwise, and a
     * desk with the preference set hears the order it will get.
     */
    private fun slashButtons(swap: Boolean): List<SpokenAction> =
        if (swap) {
            listOf(
                SpokenAction("Anticlockwise", Buttons.LEFT_BUTTON),
                SpokenAction("Clockwise", Buttons.RIGHT_BUTTON),
            )
        } else {
            listOf(
                SpokenAction("Clockwise", Buttons.LEFT_BUTTON),
                SpokenAction("Anticlockwise", Buttons.RIGHT_BUTTON),
            )
        }

    override fun interpretMove(
        state: Game.State,
        ui: Game.Ui,
        x: Int,
        y: Int,
        button: Int,
        tileSize: Int,
    ): MoveResult {
        val s = state as State
        val u = ui as Ui
        val w = s.params.width
        val h = s.params.height
        var action = Action.NONE
        var targetX = 0
        var targetY = 0

        /*
         * C compares the exact button, so a modified cursor move is not
         * a cursor move. The midend keeps CTRL and SHFT on a cursor key,
         * so it falls through to `Unused` here, as in `slant.c`.
         */
        if (button == Buttons.LEFT_BUTTON || button == Buttons.RIGHT_BUTTON) {
            var effective = button
            if (u.swapButtons) {
                effective = if (button == Buttons.LEFT_BUTTON) {
                    Buttons.RIGHT_BUTTON
                } else {
                    Buttons.LEFT_BUTTON
                }
            }
            action = if (effective == Buttons.LEFT_BUTTON) Action.CLOCKWISE else Action.ANTICLOCKWISE

            targetX = fromCoord(x, tileSize)
            targetY = fromCoord(y, tileSize)
            if (targetX < 0 || targetY < 0 || targetX >= w || targetY >= h) {
                return MoveResult.Unused
            }
            u.curVisible = false
        } else if (button == Buttons.CURSOR_SELECT || button == Buttons.CURSOR_SELECT2) {
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            targetX = u.curX
            targetY = u.curY
            action = if (button == Buttons.CURSOR_SELECT2) {
                Action.ANTICLOCKWISE
            } else {
                Action.CLOCKWISE
            }
        } else if (Buttons.isCursorMove(button)) {
            val oldX = u.curX
            val oldY = u.curY
            when (button) {
                Buttons.CURSOR_UP -> u.curY = maxOf(u.curY - 1, 0)
                Buttons.CURSOR_DOWN -> u.curY = minOf(u.curY + 1, h - 1)
                Buttons.CURSOR_LEFT -> u.curX = maxOf(u.curX - 1, 0)
                Buttons.CURSOR_RIGHT -> u.curX = minOf(u.curX + 1, w - 1)
                else -> return MoveResult.Unused
            }
            if (!u.curVisible) {
                u.curVisible = true
                return MoveResult.UiUpdate
            }
            return if (u.curX != oldX || u.curY != oldY) {
                MoveResult.UiUpdate
            } else {
                MoveResult.NoEffect
            }
        } else if (button == '\\'.code || button == '\b'.code || button == '/'.code) {
            val slash = s.soln[u.curY * w + u.curX]
            val current = when (slash) {
                -1 -> '\\'
                0 -> '\b'
                else -> '/'
            }
            if (button == current.code) return MoveResult.NoEffect
            return MoveResult.Move(
                (if (button == '\b'.code) 'C' else button.toChar()).toString() +
                    u.curX + "," + u.curY,
            )
        }

        if (action == Action.NONE) return MoveResult.Unused

        val value = if (action == Action.CLOCKWISE) {
            val next = s.soln[targetY * w + targetX] - 1
            if (next == -2) 1 else next
        } else {
            val next = s.soln[targetY * w + targetX] + 1
            if (next == 2) -1 else next
        }
        return MoveResult.Move(
            (when (value) {
                -1 -> '\\'
                1 -> '/'
                else -> 'C'
            }).toString() + targetX + "," + targetY,
        )
    }

    private enum class Action { CLOCKWISE, ANTICLOCKWISE, NONE }

    private fun fromCoord(value: Int, tileSize: Int): Int =
        (value - tileSize + tileSize) / tileSize - 1

    /** The value of the named settings text item. */
    private fun text(items: List<ConfigItem>, name: String): String =
        (items.lastOrNull { it.name == name } as? ConfigItem.Text)?.value
            ?: error("no text setting named '$name'")

    /** C `atoi`: leading whitespace, an optional sign, then decimal digits. */
    private fun atoi(text: String): Int {
        var index = 0
        while (index < text.length && text[index].isWhitespace()) index++
        var sign = 1
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            if (text[index] == '-') sign = -1
            index++
        }
        var value = 0L
        while (index < text.length && text[index] in '0'..'9') {
            value = value * 10 + (text[index] - '0')
            if (value > Int.MAX_VALUE) return if (sign < 0) Int.MIN_VALUE else Int.MAX_VALUE
            index++
        }
        return sign * value.toInt()
    }

    override fun colours(): FloatArray =
        colours(floatArrayOf(0.8f, 0.8f, 0.8f))

    override fun colours(background: FloatArray): FloatArray {
        val ret = FloatArray(NCOLOURS * 3)
        for (i in 0 until 3) ret[COL_BACKGROUND * 3 + i] = background[i]
        Colour.mkHighlight(ret, COL_BACKGROUND, COL_CURSOR, -1)

        for (i in 0 until 3) {
            ret[COL_FILLEDSQUARE * 3 + i] = ret[COL_BACKGROUND * 3 + i]
            ret[COL_GRID * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.7f
            ret[COL_INK * 3 + i] = 0.0f
            ret[COL_SLANT1 * 3 + i] = 0.0f
            ret[COL_SLANT2 * 3 + i] = 0.0f
            ret[COL_GROUNDED * 3 + i] = ret[COL_BACKGROUND * 3 + i] * 0.8f
        }
        ret[COL_ERROR * 3 + 0] = 1.0f
        ret[COL_ERROR * 3 + 1] = 0.0f
        ret[COL_ERROR * 3 + 2] = 0.0f
        for (i in 0 until 3) {
            ret[COL_CURSOR * 3 + i] =
                minOf(ret[COL_BACKGROUND * 3 + i] * 1.4f, 1.0f)
        }
        return ret
    }

    override fun redraw(
        state: Game.State,
        ui: Game.Ui,
        drawState: Game.DrawState,
        sink: DrawSink,
        tileSize: Int,
    ) {
        val s = state as State
        val u = ui as Ui
        val w = s.params.width
        val h = s.params.height
        val bigW = w + 1
        val bigH = h + 1
        val stride = w + 2
        val toDraw = IntArray(stride * (h + 2))

        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                val error = s.errors[y * bigW + x] and ERR_SQUARE != 0
                val slash = s.soln[index]
                if (slash < 0) {
                    toDraw[(y + 1) * stride + (x + 1)] = toDraw[(y + 1) * stride + (x + 1)] or BACKSLASH
                    toDraw[(y + 2) * stride + (x + 1)] = toDraw[(y + 2) * stride + (x + 1)] or T_R
                    toDraw[(y + 1) * stride + (x + 2)] = toDraw[(y + 1) * stride + (x + 2)] or L_B
                    toDraw[(y + 2) * stride + (x + 2)] = toDraw[(y + 2) * stride + (x + 2)] or C_TL
                    if (error) {
                        toDraw[(y + 1) * stride + (x + 1)] =
                            toDraw[(y + 1) * stride + (x + 1)] or
                            ERRSLASH or ERR_T_L or ERR_L_T or ERR_C_TL
                        toDraw[(y + 2) * stride + (x + 1)] =
                            toDraw[(y + 2) * stride + (x + 1)] or ERR_T_R
                        toDraw[(y + 1) * stride + (x + 2)] =
                            toDraw[(y + 1) * stride + (x + 2)] or ERR_L_B
                        toDraw[(y + 2) * stride + (x + 2)] =
                            toDraw[(y + 2) * stride + (x + 2)] or ERR_C_TL
                    }
                } else if (slash > 0) {
                    toDraw[(y + 1) * stride + (x + 1)] = toDraw[(y + 1) * stride + (x + 1)] or FORWSLASH
                    toDraw[(y + 1) * stride + (x + 2)] =
                        toDraw[(y + 1) * stride + (x + 2)] or L_T or C_TL
                    toDraw[(y + 2) * stride + (x + 1)] =
                        toDraw[(y + 2) * stride + (x + 1)] or T_L or C_TL
                    if (error) {
                        toDraw[(y + 1) * stride + (x + 1)] =
                            toDraw[(y + 1) * stride + (x + 1)] or ERRSLASH or ERR_L_B or ERR_T_R
                        toDraw[(y + 1) * stride + (x + 2)] =
                            toDraw[(y + 1) * stride + (x + 2)] or ERR_L_T or ERR_C_TL
                        toDraw[(y + 2) * stride + (x + 1)] =
                            toDraw[(y + 2) * stride + (x + 1)] or ERR_T_L or ERR_C_TL
                    }
                }
                if (u.curVisible && u.curX == x && u.curY == y) {
                    toDraw[(y + 1) * stride + (x + 1)] =
                        toDraw[(y + 1) * stride + (x + 1)] or CURSOR
                }
            }
        }

        for (y in 0 until bigH) {
            for (x in 0 until bigW) {
                if (s.errors[y * bigW + x] and ERR_VERTEX != 0) {
                    toDraw[y * stride + x] = toDraw[y * stride + x] or ERR_BR
                    toDraw[y * stride + x + 1] = toDraw[y * stride + x + 1] or ERR_BL
                    toDraw[(y + 1) * stride + x] = toDraw[(y + 1) * stride + x] or ERR_TR
                    toDraw[(y + 1) * stride + x + 1] =
                        toDraw[(y + 1) * stride + x + 1] or ERR_TL
                }
            }
        }

        /*
         * Upstream marks the centre tile of a square whose BORDER_EDGE
         * bit is set. Its comment says "dunno why but it works this
         * way"; the index is copied as written.
         */
        if (u.fadeGrounded) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (s.errors[y * w + x] and BORDER_EDGE != 0) {
                        toDraw[(y + 1) * stride + (x + 1)] =
                            toDraw[(y + 1) * stride + (x + 1)] or GROUNDED
                    }
                }
            }
        }

        for (y in -1..h) {
            for (x in -1..w) {
                drawTile(
                    sink,
                    s,
                    x,
                    y,
                    toDraw[(y + 1) * stride + (x + 1)],
                    tileSize,
                )
            }
        }
    }

    private fun drawClue(
        sink: DrawSink,
        x: Int,
        y: Int,
        value: Int,
        error: Boolean,
        background: Int,
        colour: Int,
        tileSize: Int,
    ) {
        if (value < 0) return
        val circleColour = if (colour >= 0) {
            colour
        } else if ((x xor y) and 1 != 0) {
            COL_SLANT1
        } else {
            COL_SLANT2
        }
        val textColour = if (colour >= 0) colour else if (error) COL_ERROR else COL_INK
        sink.circle(
            x * tileSize + tileSize,
            y * tileSize + tileSize,
            tileSize / 3,
            if (background >= 0) background else COL_BACKGROUND,
            circleColour,
        )
        sink.text(
            x * tileSize + tileSize,
            y * tileSize + tileSize,
            Text.FONT_VARIABLE,
            tileSize / 2,
            Text.ALIGN_VCENTRE or Text.ALIGN_HCENTRE,
            textColour,
            value.toString(),
        )
    }

    private fun drawTile(
        sink: DrawSink,
        state: State,
        x: Int,
        y: Int,
        value: Int,
        tileSize: Int,
    ) {
        val w = state.params.width
        val h = state.params.height
        val bigW = w + 1
        val chess = (x xor y) and 1
        val forwardColour = if (chess != 0) COL_SLANT2 else COL_SLANT1
        val backColour = if (chess != 0) COL_SLANT1 else COL_SLANT2
        val coordX = x * tileSize + tileSize
        val coordY = y * tileSize + tileSize

        sink.clip(coordX, coordY, tileSize, tileSize)
        sink.rect(
            coordX,
            coordY,
            tileSize,
            tileSize,
            when {
                value and FLASH != 0 -> COL_GRID
                value and CURSOR != 0 -> COL_CURSOR
                value and (BACKSLASH or FORWSLASH) != 0 -> COL_FILLEDSQUARE
                else -> COL_BACKGROUND
            },
        )

        if (x in 0 until w && y >= 0) {
            sink.rect(coordX, coordY, tileSize + 1, 1, COL_GRID)
        }
        if (x in 0 until w && y < h) {
            sink.rect(coordX, coordY + tileSize, tileSize + 1, 1, COL_GRID)
        }
        if (y in 0 until h && x >= 0) {
            sink.rect(coordX, coordY, 1, tileSize + 1, COL_GRID)
        }
        if (y in 0 until h && x < w) {
            sink.rect(coordX + tileSize, coordY, 1, tileSize + 1, COL_GRID)
        }
        if (x == -1 && y == -1) sink.rect(coordX + tileSize, coordY + tileSize, 1, 1, COL_GRID)
        if (x == -1 && y == h) sink.rect(coordX + tileSize, coordY, 1, 1, COL_GRID)
        if (x == w && y == -1) sink.rect(coordX, coordY + tileSize, 1, 1, COL_GRID)
        if (x == w && y == h) sink.rect(coordX, coordY, 1, 1, COL_GRID)

        if (value and BACKSLASH != 0) {
            val colour = when {
                value and ERRSLASH != 0 -> COL_ERROR
                value and GROUNDED != 0 -> COL_GROUNDED
                else -> backColour
            }
            sink.line(coordX, coordY, coordX + tileSize, coordY + tileSize, colour)
            sink.line(coordX + 1, coordY, coordX + tileSize, coordY + tileSize - 1, colour)
            sink.line(coordX, coordY + 1, coordX + tileSize - 1, coordY + tileSize, colour)
        } else if (value and FORWSLASH != 0) {
            val colour = when {
                value and ERRSLASH != 0 -> COL_ERROR
                value and GROUNDED != 0 -> COL_GROUNDED
                else -> forwardColour
            }
            sink.line(coordX + tileSize, coordY, coordX, coordY + tileSize, colour)
            sink.line(coordX + tileSize - 1, coordY, coordX, coordY + tileSize - 1, colour)
            sink.line(coordX + tileSize, coordY + 1, coordX + 1, coordY + tileSize, colour)
        }

        if (value and (L_T or BACKSLASH) != 0) {
            sink.rect(
                coordX,
                coordY + 1,
                1,
                1,
                if (value and ERR_L_T != 0) COL_ERROR else backColour,
            )
        }
        if (value and (L_B or FORWSLASH) != 0) {
            sink.rect(
                coordX,
                coordY + tileSize - 1,
                1,
                1,
                if (value and ERR_L_B != 0) COL_ERROR else forwardColour,
            )
        }
        if (value and (T_L or BACKSLASH) != 0) {
            sink.rect(
                coordX + 1,
                coordY,
                1,
                1,
                if (value and ERR_T_L != 0) COL_ERROR else backColour,
            )
        }
        if (value and (T_R or FORWSLASH) != 0) {
            sink.rect(
                coordX + tileSize - 1,
                coordY,
                1,
                1,
                if (value and ERR_T_R != 0) COL_ERROR else forwardColour,
            )
        }
        if (value and (C_TL or BACKSLASH) != 0) {
            sink.rect(
                coordX,
                coordY,
                1,
                1,
                if (value and ERR_C_TL != 0) COL_ERROR else backColour,
            )
        }

        if (x >= 0 && y >= 0) {
            drawClue(
                sink, x, y, state.clues[y * bigW + x],
                value and ERR_TL != 0, -1, -1, tileSize,
            )
        }
        if (x < w && y >= 0) {
            drawClue(
                sink, x + 1, y, state.clues[y * bigW + (x + 1)],
                value and ERR_TR != 0, -1, -1, tileSize,
            )
        }
        if (x >= 0 && y < h) {
            drawClue(
                sink, x, y + 1, state.clues[(y + 1) * bigW + x],
                value and ERR_BL != 0, -1, -1, tileSize,
            )
        }
        if (x < w && y < h) {
            drawClue(
                sink, x + 1, y + 1, state.clues[(y + 1) * bigW + (x + 1)],
                value and ERR_BR != 0, -1, -1, tileSize,
            )
        }

        sink.unclip()
        sink.update(coordX, coordY, tileSize, tileSize)
    }

    private fun checkCompletion(state: State): Boolean {
        val w = state.params.width
        val h = state.params.height
        val bigW = w + 1
        val bigH = h + 1
        state.errors.fill(0)
        var error = false

        val connected = Dsf.new(bigW * bigH)
        for (x in 0..w) {
            connected.merge(x, 0)
            connected.merge(h * bigW + x, 0)
        }
        for (y in 0..h) {
            connected.merge(y * bigW, 0)
            connected.merge(y * bigW + w, 0)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                when (state.soln[y * w + x]) {
                    -1 -> connected.merge(y * bigW + x, (y + 1) * bigW + (x + 1))
                    1 -> connected.merge(y * bigW + (x + 1), (y + 1) * bigW + x)
                    else -> continue
                }
            }
        }

        val rootNorthWest = connected.canonify(0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val slash = state.soln[y * w + x]
                if (slash != 0 && connected.canonify(y * bigW + x + if (slash == 1) 1 else 0) ==
                    rootNorthWest
                ) {
                    state.errors[y * w + x] = state.errors[y * w + x] or BORDER_EDGE
                }
            }
        }

        val findLoop = FindLoopState(bigW * bigH)
        if (findLoop.run(SlantNeighbour(state))) error = true
        for (y in 0 until h) {
            for (x in 0 until w) {
                val slash = state.soln[y * w + x]
                if (slash == 0) continue
                val u: Int
                val v: Int
                if (slash > 0) {
                    u = y * bigW + (x + 1)
                    v = (y + 1) * bigW + x
                } else {
                    u = (y + 1) * bigW + (x + 1)
                    v = y * bigW + x
                }
                if (findLoop.isLoopEdge(u, v)) {
                    state.errors[y * bigW + x] = state.errors[y * bigW + x] or ERR_SQUARE
                }
            }
        }

        for (y in 0 until bigH) {
            for (x in 0 until bigW) {
                val clue = state.clues[y * bigW + x]
                if (clue < 0) continue
                if (vertexDegree(w, h, state.soln, x, y, false) > clue ||
                    vertexDegree(w, h, state.soln, x, y, true) > 4 - clue
                ) {
                    state.errors[y * bigW + x] = state.errors[y * bigW + x] or ERR_VERTEX
                    error = true
                }
            }
        }

        if (error) return false
        for (slash in state.soln) if (slash == 0) return false
        return true
    }

    private fun vertexDegree(
        w: Int,
        h: Int,
        soln: IntArray,
        x: Int,
        y: Int,
        anti: Boolean,
    ): Int {
        val a = if (anti) 1 else 0
        var ret = 0
        if (x > 0 && y > 0 && soln[(y - 1) * w + (x - 1)] - a < 0) ret++
        if (x > 0 && y < h && soln[y * w + (x - 1)] + a > 0) ret++
        if (x < w && y > 0 && soln[(y - 1) * w + x] + a > 0) ret++
        if (x < w && y < h && soln[y * w + x] - a < 0) ret++
        return if (anti) 4 - ret else ret
    }

    private class SlantNeighbour(private val state: State) : Neighbour {
        private val neighbours = IntArray(4)
        private var count = 0
        private var index = 0

        override fun next(vertex: Int): Int {
            if (vertex >= 0) {
                val w = state.params.width
                val h = state.params.height
                val bigW = w + 1
                val x = vertex % bigW
                val y = vertex / bigW
                count = 0
                index = 0
                if (x < w && y < h && state.soln[y * w + x] < 0) {
                    neighbours[count++] = (y + 1) * bigW + (x + 1)
                }
                if (x > 0 && y > 0 && state.soln[(y - 1) * w + (x - 1)] < 0) {
                    neighbours[count++] = (y - 1) * bigW + (x - 1)
                }
                if (x > 0 && y < h && state.soln[y * w + (x - 1)] > 0) {
                    neighbours[count++] = (y + 1) * bigW + (x - 1)
                }
                if (x < w && y > 0 && state.soln[(y - 1) * w + x] > 0) {
                    neighbours[count++] = (y - 1) * bigW + (x + 1)
                }
            }
            return if (index < count) neighbours[index++] else -1
        }
    }
}
